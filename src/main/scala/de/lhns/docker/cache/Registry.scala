package de.lhns.docker.cache

import cats.effect.{IO, Resource}
import org.http4s.*
import org.http4s.dsl.io.Path as _
import org.log4s.getLogger

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}
import scala.jdk.CollectionConverters.*
import scala.util.control.NonFatal

trait Registry[F[_]] {
  def uri: Uri

  require(uri.path.isEmpty, "registry uri path must be empty!")
  require(uri.host.isDefined, "registry uri host must not be empty!")

  val host: String = uri.host.get.toString

  def setup(port: Int, variables: Map[String, String]): Resource[F, Uri]
}

object Registry {
  private val logger = getLogger

  private val defaultRootDirectory: Path = Paths.get("/var/lib/registry")

  // The internal registry persists its proxy TTL scheduler state to
  // "scheduler-state.json" at the storage root. If that file is corrupt (e.g.
  // truncated by a crash or a stalled filesystem mid-write) the registry fails to
  // start, taking the upstream's proxy down. Prioritising availability, we detect a
  // corrupt file and delete it so the registry can rebuild its state from scratch.
  // Best-effort and defensive: the directory or file may be absent, unreadable or
  // locked, so any problem here is logged and ignored, never fatal, and we never
  // delete a file we could not actually read and parse.
  private def deleteCorruptSchedulerState(directory: Path): Unit =
    try {
      val stateFile = directory.resolve("scheduler-state.json")
      if (Files.exists(stateFile) && Files.isRegularFile(stateFile)) {
        val content = new String(Files.readAllBytes(stateFile), StandardCharsets.UTF_8)
        if (io.circe.parser.parse(content).isLeft) {
          logger.warn(s"scheduler-state.json at $stateFile is corrupt; deleting it so the registry can start")
          Files.deleteIfExists(stateFile)
        }
      }
    } catch {
      case NonFatal(e) =>
        logger.warn(e)(s"could not validate scheduler-state.json in $directory; leaving it untouched")
    }

  def externalProxy(uri: Uri, proxyUri: Uri): Registry[IO] = {
    val _uri = uri
    new Registry[IO] {
      override def uri: Uri = _uri

      override def setup(port: Int, variables: Map[String, String]): Resource[IO, Uri] =
        Resource.pure(proxyUri)
    }
  }

  def apply(uri: Uri): Registry[IO] = {
    val _uri = uri
    new Registry[IO] {
      override def uri: Uri = _uri

      override def setup(port: Int, variables: Map[String, String]): Resource[IO, Uri] = {
        val addr = s"localhost:$port"
        Resource.make(IO.blocking {
          val builder = new ProcessBuilder("registry", "serve", "/etc/distribution/config.yml")
          builder.environment().put("REGISTRY_HTTP_ADDR", addr)
          builder.environment().put("REGISTRY_HTTP_DEBUG", "{}") // disable the debug port which would be on 5001 by default and cause conflicts
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
              Registry.deleteCorruptSchedulerState(directory)
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
