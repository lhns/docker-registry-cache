package de.lolhens.docker.cache

import cats.Monad
import cats.effect.{BracketThrow, ExitCode, Resource}
import cats.instances.list._
import cats.syntax.apply._
import cats.syntax.functor._
import cats.syntax.traverse._
import com.typesafe.scalalogging.Logger
import fs2.Stream
import io.circe.generic.semiauto._
import io.circe.syntax._
import io.circe.{Codec, Decoder, Encoder, Json}
import monix.eval.{Task, TaskApp}
import org.http4s._
import org.http4s.circe._
import org.http4s.client.Client
import org.http4s.client.jdkhttpclient.JdkHttpClient
import org.http4s.dsl.task.{Path => _, _}
import org.http4s.headers.Host
import org.http4s.implicits._
import org.http4s.server.blaze.BlazeServerBuilder

import java.net.http.HttpClient
import java.nio.file.{Files, Path, Paths}
import scala.concurrent.duration._
import scala.jdk.CollectionConverters._
import scala.util.chaining._

object Server extends TaskApp {
  private val logger = Logger[Server.type]

  val defaultRootDirectory: Path = Paths.get("/var/lib/registry")

  case class Registry private(uri: Uri) {
    lazy val host: String = uri.host.get.toString()

    def startProxy(port: Int, variables: Map[String, String]): Task[Uri] = Task {
      val addr = s"localhost:$port"

      val builder = new ProcessBuilder("registry", "serve", "/etc/docker/registry/config.yml")
      builder.environment().put("REGISTRY_HTTP_ADDR", addr)
      builder.environment().put("REGISTRY_PROXY_REMOTEURL", uri.toString())
      builder.environment().put("REGISTRY_STORAGE_REDIRECT_DISABLE", "true")

      def appendDirectoryVar(variable: String, append: String, default: String = ""): String = {
        val newValue = (Option(System.getenv(variable)).getOrElse(default)
          .split("/").toSeq.filterNot(_.isEmpty) :+ append).mkString("/")

        builder.environment().put(variable, newValue)
        newValue
      }

      Option(System.getenv("REGISTRY_STORAGE")) match {
        case Some("gcs") => appendDirectoryVar("REGISTRY_STORAGE_GCS_ROOTDIRECTORY", host)
        case Some("s3") => appendDirectoryVar("REGISTRY_STORAGE_S3_ROOTDIRECTORY", host)
        case Some("swift") => appendDirectoryVar("REGISTRY_STORAGE_SWIFT_ROOTDIRECTORY", host)
        case Some("oss") => appendDirectoryVar("REGISTRY_STORAGE_OSS_ROOTDIRECTORY", host)
        case _ =>
          val directory = Paths.get(appendDirectoryVar(
            "REGISTRY_STORAGE_FILESYSTEM_ROOTDIRECTORY",
            host,
            defaultRootDirectory.toAbsolutePath.toString
          ))
          Files.createDirectories(directory)
      }

      builder.environment().putAll(variables.asJava)
      builder.inheritIO().start()
      Uri.unsafeFromString(s"http://$addr")
    }
  }

  object Registry {
    def apply(uri: Uri): Registry = {
      require(uri.path.isEmpty, "registry uri path must be empty!")
      require(uri.host.isDefined, "registry uri host must not be empty!")
      new Registry(uri)
    }

    def fromString(string: String): Registry = {
      val newString = string match {
        case string@s"http://$_" => string
        case string@s"https://$_" => string
        case string => s"https://$string"
      }
      Registry(Uri.unsafeFromString(newString))
    }

    implicit val codec: Codec[Registry] = Codec.from(
      Decoder[String].map(fromString),
      Encoder[String].contramap[Registry](_.uri.toString())
    )
  }

  case class Image(registry: Registry, namespace: String, name: String) {
    lazy val labelWithoutRegistry: String = s"$namespace/$name"
  }

  object Image {
    def fromString(label: String, registries: Seq[Registry]): Image = {
      label.split("/").toSeq.pipe {
        case parts@registryName +: (remainingParts@_ +: _) =>
          registries.find(_.host.equalsIgnoreCase(registryName)) match {
            case Some(registry) => (registry, remainingParts)
            case None => (registries.head, parts)
          }
        case parts => (registries.head, parts)
      }.pipe {
        case (registry, namespace :+ label) => (registry, namespace, label)
        case (_, _) => throw new RuntimeException("image label must not be empty")
      }.pipe {
        case (registry, namespace@_ +: _, name) => Image(registry, namespace.mkString("/"), name)
        case (registry, _, name) => Image(registry, "library", name)
      }
    }
  }

  case class RegistryConfig(registry: Registry,
                            variables: Option[Map[String, String]]) {
    def variablesOrDefault: Map[String, String] = variables.getOrElse(Map.empty)
  }

  object RegistryConfig {
    implicit val codec: Codec[RegistryConfig] = {
      val defaultCodec = deriveCodec[RegistryConfig]
      Codec.from(
        Decoder[Registry].map(RegistryConfig(_, None)).or(defaultCodec),
        defaultCodec
      )
    }
  }

  override def run(args: List[String]): Task[ExitCode] = {
    val registries: List[RegistryConfig] =
      io.circe.parser.parse(System.getenv("CONFIG")).toTry.get.as[List[RegistryConfig]].toTry.get

    logger.info("Registries:\n" + registries.map(_ + "\n").mkString)
    logger.info("starting proxies")

    for {
      registryProxies <- registries.zipWithIndex.map {
        case (registryConfig, index) =>
          registryConfig.registry.startProxy(5001 + index, registryConfig.variablesOrDefault)
            .map((registryConfig.registry, _))
      }.sequence
      _ <- clientResource.use { client =>
        Task.parSequenceUnordered(registryProxies.map {
          case (registry, proxyUri) =>
            lazy val retryLoop: Task[String] =
              client.expect[String](proxyUri.withPath("/v2/"))
                .onErrorHandleWith { throwable =>
                  Task.sleep(1.second) *>
                    retryLoop
                }

            retryLoop.timeoutWith(20.seconds, new RuntimeException(s"error starting proxy for ${registry.host}"))
        })
      }
      _ = logger.info("proxies started")
      result <- Task.deferAction { scheduler =>
        BlazeServerBuilder[Task](scheduler)
          .bindHttp(5000, "0.0.0.0")
          .withHttpApp(service(registryProxies).orNotFound)
          .resource
          .use(_ => Task.never)
      }
    } yield
      result
  }

  lazy val clientResource: Resource[Task, Client[Task]] =
    Resource.eval(Task(JdkHttpClient[Task](
      HttpClient.newBuilder()
        .sslParameters {
          val ssl = javax.net.ssl.SSLContext.getDefault
          val params = ssl.getDefaultSSLParameters
          params.setProtocols(Array("TLSv1.2"))
          params
        }
        .build()
    )).memoizeOnSuccess)

  private def responseFromResource[F[_] : BracketThrow](resource: Resource[F, Response[F]]): F[Response[F]] =
    resource.allocated.map {
      case (response, release) =>
        response.withBodyStream(Stream.resource(Resource.make(Monad[F].unit)(_ => release)) *> response.body)
    }

  def withDestination[F[_]](request: Request[F], destination: Uri): Request[F] =
    request
      .withUri(destination)
      .putHeaders(Host.parse(destination.host.map(_.value).getOrElse("") + destination.port.map(":" + _).getOrElse("")).toTry.get)

  def proxyTo(request: Request[Task], destination: Uri): Task[Response[Task]] =
    responseFromResource {
      for {
        client <- clientResource
        newRequest = withDestination(
          request,
          request.uri.copy(scheme = destination.scheme, authority = destination.authority)
        )
          .filterHeaders { header =>
            val name = header.name.value.toLowerCase
            !(name == "x-real-ip" || name.startsWith("x-forwarded-"))
          }
        response <- client.run(newRequest)
      } yield
        response
    }

  def service(registryProxies: Seq[(Registry, Uri)]): HttpRoutes[Task] = {
    val registries = registryProxies.map(_._1)
    val proxyUriByRegistry = registryProxies.toMap

    HttpRoutes.of {
      case request =>
        request.uri.path match {
          case "/v2/" =>
            Ok(Json.obj())

          case "/v2/_catalog" =>
            for {
              repositories <- (for {
                client <- Stream.resource(clientResource)
                (registry, proxyUri) <- Stream.iterable(registryProxies)
                json <- Stream.eval(client.expect[Json](proxyUri.withPath("/v2/_catalog")))
                json <- Stream.iterable(json.asObject)
                json <- Stream.iterable(json("repositories"))
                json <- Stream.iterable(json.asArray)
                json <- Stream.iterable(json)
                repository <- Stream.iterable(json.asString)
              } yield
                s"${registry.host}/$repository")
                .compile.toVector
              json = Map("repositories" -> repositories).asJson
              response <- Ok(json)
            } yield
              response

          case s"/v2/$label/manifests/$tag" =>
            val image = Image.fromString(label, registries)
            logger.debug(s"requesting manifest $image:$tag")
            val newPath = s"/v2/${image.labelWithoutRegistry}/manifests/$tag"
            proxyTo(request.withUri(request.uri.withPath(newPath)), proxyUriByRegistry(image.registry))

          case s"/v2/$label/blobs/$blob" =>
            val image = Image.fromString(label, registries)
            logger.debug(s"requesting blob $image $blob")
            val newPath = s"/v2/${image.labelWithoutRegistry}/blobs/$blob"
            proxyTo(request.withUri(request.uri.withPath(newPath)), proxyUriByRegistry(image.registry))

          case path =>
            logger.warn("unsupported path " + path)
            NotFound()
        }
    }
  }

}
