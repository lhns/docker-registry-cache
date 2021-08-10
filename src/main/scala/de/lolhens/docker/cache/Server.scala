package de.lolhens.docker.cache

import cats.effect.{ExitCode, IO, IOApp, Resource}
import cats.instances.list._
import cats.syntax.traverse._
import com.typesafe.scalalogging.Logger
import de.lolhens.http4s.proxy.Http4sProxy._
import fs2.Stream
import io.circe.generic.semiauto._
import io.circe.syntax._
import io.circe.{Codec, Decoder, Encoder, Json}
import org.http4s._
import org.http4s.blaze.server.BlazeServerBuilder
import org.http4s.circe._
import org.http4s.client.Client
import org.http4s.dsl.io.{Path => _, _}
import org.http4s.implicits._
import org.http4s.jdkhttpclient.JdkHttpClient

import java.nio.file.{Files, Path, Paths}
import scala.concurrent.duration._
import scala.jdk.CollectionConverters._
import scala.util.chaining._

object Server extends IOApp {
  private val logger = Logger[Server.type]

  val defaultRootDirectory: Path = Paths.get("/var/lib/registry")

  case class Registry private(uri: Uri) {
    lazy val host: String = uri.host.get.toString()

    def startProxy(port: Int, variables: Map[String, String]): IO[Uri] = IO {
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
      label.split("/", -1).toSeq.pipe {
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

  override def run(args: List[String]): IO[ExitCode] = {
    val registries: List[RegistryConfig] =
      io.circe.parser.parse(System.getenv("CONFIG")).toTry.get.as[List[RegistryConfig]].toTry.get

    logger.info("Registries:\n" + registries.map(_ + "\n").mkString)
    logger.info("starting proxies")

    (for {
      registryProxies <- Resource.eval(registries.zipWithIndex.map {
        case (registryConfig, index) =>
          registryConfig.registry.startProxy(5001 + index, registryConfig.variablesOrDefault)
            .map((registryConfig.registry, _))
      }.sequence)
      client <- JdkHttpClient.simple[IO]
      _ <- Resource.eval(IO.parSequenceN(registryProxies.size)(registryProxies.map {
        case (registry, proxyUri) =>
          lazy val retryLoop: IO[String] =
            client.expect[String](proxyUri.withPath(path"/v2/"))
              .handleErrorWith { throwable =>
                IO.sleep(1.second) *>
                  retryLoop
              }

          retryLoop.timeoutTo(20.seconds, IO.raiseError(new RuntimeException(s"error starting proxy for ${registry.host}")))
      }))
      _ = logger.info("proxies started")
      ec <- Resource.eval(IO.executionContext)
      result <-
        BlazeServerBuilder[IO](ec)
          .bindHttp(5000, "0.0.0.0")
          .withHttpApp(service(client, registryProxies).orNotFound)
          .resource
    } yield
      result)
      .use(_ => IO.never)
  }

  def proxyTo(client: Client[IO], request: Request[IO], destination: Uri): IO[Response[IO]] =
    client.toHttpApp.run(
      request.withDestination(request.uri.withSchemeAndAuthority(destination))
        .filterHeaders { header =>
          val name = header.name.toString.toLowerCase
          !(name == "x-real-ip" || name.startsWith("x-forwarded-"))
        }
    )

  def service(client: Client[IO], registryProxies: Seq[(Registry, Uri)]): HttpRoutes[IO] = {
    val registries = registryProxies.map(_._1)
    val proxyUriByRegistry = registryProxies.toMap

    HttpRoutes.of {
      case request =>
        request.uri.path.renderString match {
          case "/v2/" =>
            Ok(Json.obj())

          case "/v2/_catalog" =>
            for {
              repositories <- (for {
                (registry, proxyUri) <- Stream.iterable(registryProxies)
                json <- Stream.eval(client.expect[Json](proxyUri.withPath(path"/v2/_catalog")))
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
            val newPath = Uri.Path.unsafeFromString(s"/v2/${image.labelWithoutRegistry}/manifests/$tag")
            proxyTo(client, request.withUri(request.uri.withPath(newPath)), proxyUriByRegistry(image.registry))

          case s"/v2/$label/blobs/$blob" =>
            val image = Image.fromString(label, registries)
            logger.debug(s"requesting blob $image $blob")
            val newPath = Uri.Path.unsafeFromString(s"/v2/${image.labelWithoutRegistry}/blobs/$blob")
            proxyTo(client, request.withUri(request.uri.withPath(newPath)), proxyUriByRegistry(image.registry))

          case path =>
            logger.warn("unsupported path " + path)
            NotFound()
        }
    }
  }

}
