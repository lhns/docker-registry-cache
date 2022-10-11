package de.lhns.docker.cache

import cats.effect.IO
import io.circe.{Codec, Decoder, Encoder}
import org.http4s._
import org.http4s.dsl.io.{Path => _}

import java.nio.file.{Files, Paths}
import scala.jdk.CollectionConverters._

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
          Main.defaultRootDirectory.toAbsolutePath.toString
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
