package de.lhns.docker.cache

import cats.effect.{IO, Resource}
import org.http4s._
import org.http4s.dsl.io.{Path => _}

import java.nio.file.{Files, Path, Paths}
import scala.jdk.CollectionConverters._

trait Registry[F[_]] {
  def uri: Uri

  require(uri.path.isEmpty, "registry uri path must be empty!")
  require(uri.host.isDefined, "registry uri host must not be empty!")

  lazy val host: String = uri.host.get.toString

  def setup(port: Int, variables: Map[String, String]): Resource[F, Uri]
}

object Registry {
  private val defaultRootDirectory: Path = Paths.get("/var/lib/registry")

  def externalProxy(uri: Uri, proxyUri: Uri): Registry[IO] = {
    val _uri = uri
    new Registry[IO] {
      override val uri: Uri = _uri

      override def setup(port: Int, variables: Map[String, String]): Resource[IO, Uri] =
        Resource.pure(proxyUri)
    }
  }

  def apply(uri: Uri): Registry[IO] = {
    val _uri = uri
    new Registry[IO] {
      override val uri: Uri = _uri

      override def setup(port: Int, variables: Map[String, String]): Resource[IO, Uri] = {
        val addr = s"localhost:$port"
        Resource.make(IO.blocking {
          val builder = new ProcessBuilder("registry", "serve", "/etc/docker/registry/config.yml")
          builder.environment().put("REGISTRY_HTTP_ADDR", addr)
          builder.environment().put("REGISTRY_PROXY_REMOTEURL", uri.toString)
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
                Registry.defaultRootDirectory.toAbsolutePath.toString
              ))
              Files.createDirectories(directory)
          }

          builder.environment().putAll(variables.asJava)
          builder.inheritIO().start()
        })(process => IO.blocking {
          process.destroy()
        }).map(_ =>
          Uri.unsafeFromString(s"http://$addr")
        )
      }
    }
  }

  def registryUriFromString(string: String): Uri = {
    val newString = string match {
      case string@s"http://$_" => string
      case string@s"https://$_" => string
      case string => s"https://$string"
    }
    Uri.unsafeFromString(newString)
  }
}
