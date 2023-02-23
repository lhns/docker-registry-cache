package de.lhns.docker.cache

import cats.effect.IO
import de.lolhens.http4s.proxy.Http4sProxy._
import fs2.Stream
import io.circe.Json
import io.circe.syntax._
import org.http4s._
import org.http4s.circe._
import org.http4s.client.Client
import org.http4s.dsl.io.{Path => _, _}
import org.http4s.implicits._
import org.log4s.getLogger

class RegistryProxyRoutes(
                           client: Client[IO],
                           registryProxies: Seq[(Registry[IO], Uri)]
                         ) {
  private val logger = getLogger

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
