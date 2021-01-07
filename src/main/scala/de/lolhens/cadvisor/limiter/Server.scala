package de.lolhens.cadvisor.limiter

import cats.effect.{ExitCode, Resource}
import cats.instances.list._
import cats.syntax.traverse._
import io.circe.generic.semiauto._
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
import org.slf4j.LoggerFactory

import java.net.http.HttpClient
import java.nio.file.{Files, Path, Paths}
import scala.jdk.CollectionConverters._
import scala.util.chaining._

object Server extends TaskApp {
  private val logger = LoggerFactory.getLogger(getClass)

  val rootDirectory: Path = Paths.get("/var/lib/registry")

  case class Registry private(uri: Uri) {
    lazy val host: String = uri.host.get.toString()

    def directory: Path = rootDirectory.resolve(host)

    def startProxy(port: Int, variables: Map[String, String]): Task[Uri] = Task {
      val addr = s"localhost:$port"
      Files.createDirectories(directory)
      val builder = new ProcessBuilder("registry", "serve", "/etc/docker/registry/config.yml")
      builder.environment().put("REGISTRY_HTTP_ADDR", addr)
      builder.environment().put("REGISTRY_PROXY_REMOTEURL", uri.toString())
      builder.environment().put("REGISTRY_STORAGE_FILESYSTEM_ROOTDIRECTORY", directory.toAbsolutePath.toString)
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
      Registry(Uri.fromString(newString).toTry.get)
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
        /*case (registry, namespace :+ labelAndTag) if nameAndTag.contains(":") =>
          val (name, s":$tag") = nameAndTag.splitAt(nameAndTag.lastIndexOf(":"))
          (registry, namespace, name, tag)*/
        case (registry, namespace :+ label) => (registry, namespace, label /*, "latest"*/ )
        case (_, _) => throw new RuntimeException("image label cannot be empty")
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
    implicit val codec: Codec[RegistryConfig] = deriveCodec
  }

  override def run(args: List[String]): Task[ExitCode] = {
    val registries: List[RegistryConfig] =
      io.circe.parser.parse(System.getenv("CONFIG")).toTry.get.as[List[RegistryConfig]].toTry.get

    logger.info("Registries:\n" + registries.mkString("\n"))

    for {
      registryProxies <- registries.zipWithIndex.map {
        case (registryConfig, index) =>
          registryConfig.registry.startProxy(5001 + index, registryConfig.variablesOrDefault)
            .map((registryConfig.registry, _))
      }.sequence
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
    Resource.liftF(Task(JdkHttpClient[Task](
      HttpClient.newBuilder()
        .sslParameters {
          val ssl = javax.net.ssl.SSLContext.getDefault
          val params = ssl.getDefaultSSLParameters
          params.setProtocols(Array("TLSv1.2"))
          params
        }
        //.connectTimeout(java.time.Duration.ofMillis(timeout.toMillis))
        .build()
    )).memoizeOnSuccess)

  def setDestination(request: Request[Task], destination: Uri): Request[Task] =
    request.withUri(destination).putHeaders(Host.parse(destination.host.map(_.value).getOrElse("")).toTry.get)
      .filterHeaders { header =>
        val name = header.name.value.toLowerCase
        !(name == "x-real-ip" || name.startsWith("x-forwarded-"))
      }

  def proxyTo(request: Request[Task], destination: Uri): Task[Response[Task]] =
    (for {
      client <- clientResource
      newRequest = setDestination(request, request.uri.copy(scheme = destination.scheme, authority = destination.authority))
      response <- client.run(newRequest)
    } yield
      response)
      .allocated.map(_._1)

  def service(registryProxies: Seq[(Registry, Uri)]): HttpRoutes[Task] = {
    val registries = registryProxies.map(_._1)
    val proxyUriByRegistry = registryProxies.toMap

    HttpRoutes.of {
      case request =>
        request.uri.path match {
          case "/v2/" =>
            Ok(Json.obj())
          //proxyTo(request, registries.head.uri)

          case s"/v2/$label/manifests/$tag" =>
            val image = Image.fromString(label, registries)
            logger.info("manifest " + image + " " + tag)
            val newPath = s"/v2/${image.labelWithoutRegistry}/manifests/$tag"
            proxyTo(request.withUri(request.uri.withPath(newPath)), proxyUriByRegistry(image.registry))

          case s"/v2/$label/blobs/$blob" =>
            val image = Image.fromString(label, registries)
            logger.info("blobs " + image + " " + blob)
            val newPath = s"/v2/${image.labelWithoutRegistry}/blobs/$blob"
            proxyTo(request.withUri(request.uri.withPath(newPath)), proxyUriByRegistry(image.registry))

          case path =>
            println("unsupported path " + path)
            NotFound()
        }
    }
  }

}
