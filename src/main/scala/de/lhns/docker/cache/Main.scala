package de.lhns.docker.cache

import cats.effect._
import cats.effect.std.Env
import cats.syntax.parallel._
import com.comcast.ip4s._
import io.circe.syntax._
import org.http4s.HttpApp
import org.http4s.dsl.io.{Path => _}
import org.http4s.ember.server.EmberServerBuilder
import org.http4s.implicits._
import org.http4s.jdkhttpclient.JdkHttpClient
import org.http4s.server.Server
import org.http4s.server.middleware.ErrorAction
import org.log4s.getLogger

import scala.concurrent.duration._

object Main extends IOApp {
  private val logger = getLogger

  override def run(args: List[String]): IO[ExitCode] =
    applicationResource.use(_ => IO.never)

  def applicationResource: Resource[IO, Unit] =
    for {
      registries <- Resource.eval(RegistryConfig.fromEnv(Env.make[IO]))
      client <- JdkHttpClient.simple[IO]
      _ <- Resource.eval(IO(logger.info("Registries:\n" + registries.map(_.asJson.noSpaces + "\n").mkString)))
      _ <- Resource.eval(IO(logger.info("starting proxies")))
      registryProxies <- registries
        .zipWithIndex
        .map { case (registryConfig, index) =>
          val registry = registryConfig.toRegistry
          registry.setup(
            port = 5001 + index,
            variables = registryConfig.variablesOrDefault
          )
            .map { proxyUri =>
              (registry, proxyUri)
            }
            .evalTap {
              case (registry, proxyUri) =>
                Ref[IO].of[Option[Throwable]](None).flatMap { latestError =>
                  lazy val retryLoop: IO[String] =
                    client.expect[String](proxyUri.withPath(path"/v2/"))
                      .handleErrorWith { error =>
                        latestError.set(Some(error)) *>
                          IO.sleep(1.second) *>
                          retryLoop
                      }

                  retryLoop.timeoutTo(
                    20.seconds,
                    latestError.get.flatMap { error =>
                      IO.raiseError(new RuntimeException(s"error starting proxy for ${registry.host}", error.orNull))
                    }
                  )
                }
            }
        }
        .parSequence
      _ <- Resource.eval(IO(logger.info("proxies started")))
      _ <- serverResource(
        SocketAddress(host"0.0.0.0", port"5000"),
        new RegistryProxyRoutes(client, registryProxies).toRoutes.orNotFound
      )
    } yield ()

  def serverResource[F[_] : Async](socketAddress: SocketAddress[Host], http: HttpApp[F]): Resource[F, Server] =
    EmberServerBuilder
      .default[F]
      .withHost(socketAddress.host)
      .withPort(socketAddress.port)
      .withHttpApp(
        ErrorAction.log(
          http = http,
          messageFailureLogAction = (t, msg) => Async[F].delay(logger.debug(t)(msg)),
          serviceErrorLogAction = (t, msg) => Async[F].delay(logger.error(t)(msg))
        ))
      .withShutdownTimeout(1.second)
      .build
}
