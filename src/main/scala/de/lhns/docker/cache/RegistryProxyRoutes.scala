package de.lhns.docker.cache

import cats.effect.IO
import cats.syntax.all.*
import de.lolhens.http4s.proxy.Http4sProxy.*
import fs2.Stream
import io.circe.Json
import io.circe.syntax.*
import org.http4s.*
import org.http4s.circe.*
import org.http4s.client.Client
import org.http4s.dsl.io.{Path as _, *}
import org.http4s.implicits.*
import org.log4s.getLogger

import java.util.concurrent.TimeoutException
import scala.concurrent.duration.*

class RegistryProxyRoutes(
                           client: Client[IO],
                           registryProxies: Seq[(Registry[IO], Uri)],
                           responseHeaderTimeout: FiniteDuration,
                           manifestRequestTimeout: FiniteDuration
                         ) {
  private val logger = getLogger

  // toHttpApp.run completes once the upstream response headers arrive; the body
  // streams lazily afterward. Timing it therefore gives time-to-first-byte
  // semantics: a wedged storage read (no headers ever) fails fast, but a slow
  // but progressing body transfer is never cut off. On timeout we surface a 504
  // so the Docker client can retry, and the in-flight fiber is cancelled so its
  // connection is released rather than held open (which would starve other upstreams).
  private def withHeaderTimeout(what: => String)(io: IO[Response[IO]]): IO[Response[IO]] =
    io.timeoutTo(
      responseHeaderTimeout,
      IO(logger.warn(s"upstream did not send response headers within $responseHeaderTimeout: $what")) *>
        IO.raiseError(new TimeoutException(s"response header timeout after $responseHeaderTimeout"))
    )

  private val timeoutResponse: PartialFunction[Throwable, Response[IO]] = {
    case _: TimeoutException => Response[IO](Status.GatewayTimeout)
  }

  def proxyTo(client: Client[IO], request: Request[IO], destination: Uri): IO[Response[IO]] =
    client.toHttpApp.run(
      request
        .withHttpVersion(HttpVersion.`HTTP/1.1`)
        .withDestination(request.uri.withSchemeAndAuthority(destination))
        .filterHeaders { header =>
          val name = header.name.toString.toLowerCase
          !(name == "x-real-ip" || name.startsWith("x-forwarded-"))
        }
    )

  private val registries = registryProxies.map(_._1)
  private val proxyUriByRegistry = registryProxies.toMap

  val toRoutes: HttpRoutes[IO] = HttpRoutes.of {
    case request =>
      request.uri.path.renderString match {
        case "/v2/" =>
          Ok(Json.obj())

        case "/v2/_catalog" =>
          for {
            repositories <- (for {
              (registry, proxyUri) <- Stream.iterable(registryProxies)
              json <- Stream.eval(client.expect[Json](proxyUri.withPath(path"/v2/_catalog")).timeout(responseHeaderTimeout))
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
          // Manifests are small and must return promptly: apply the header timeout
          // AND a hard total-request cap. A stuck manifest read fails fast as 504.
          withHeaderTimeout(s"manifest $image:$tag") {
            proxyTo(client, request.withUri(request.uri.withPath(newPath)), proxyUriByRegistry(image.registry))
          }
            .timeoutTo(
              manifestRequestTimeout,
              IO(logger.warn(s"manifest request exceeded $manifestRequestTimeout: $image:$tag")) *>
                IO.raiseError(new TimeoutException(s"manifest request timeout after $manifestRequestTimeout"))
            )
            .recover(timeoutResponse)

        case s"/v2/$label/blobs/$blob" =>
          val image = Image.fromString(label, registries)
          logger.debug(s"requesting blob $image $blob")
          val newPath = Uri.Path.unsafeFromString(s"/v2/${image.labelWithoutRegistry}/blobs/$blob")
          // Blobs may be large and legitimately slow over HDD-backed storage:
          // apply ONLY the time-to-first-byte timeout, never a total cap, so a
          // progressing transfer is never interrupted but a wedged read fails fast.
          withHeaderTimeout(s"blob $image $blob") {
            proxyTo(client, request.withUri(request.uri.withPath(newPath)), proxyUriByRegistry(image.registry))
          }
            .recover(timeoutResponse)

        case path =>
          logger.warn("unsupported path " + path)
          NotFound()
      }
  }
}
