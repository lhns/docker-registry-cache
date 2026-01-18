package de.lhns.docker.cache

import cats.effect.*
import cats.effect.std.Env
import cats.effect.syntax.all.*
import cats.syntax.all.*
import com.comcast.ip4s.*
import io.circe.syntax.*
import org.http4s.client.Client
import org.http4s.dsl.io.Path as _
import org.http4s.ember.server.EmberServerBuilder
import org.http4s.implicits.*
import org.http4s.jdkhttpclient.JdkHttpClient
import org.http4s.server.Server
import org.http4s.server.middleware.ErrorAction
import org.http4s.{HttpApp, Uri}
import org.log4s.getLogger

import scala.concurrent.duration.*

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
                if (registryConfig.healthcheckOrDefault) {
                  awaitReady(
                    client,
                    registryUri = proxyUri,
                    interval = 1.second,
                    timeout = 20.seconds,
                    s"error starting proxy for ${registry.host}"
                  )
                } else {
                  IO.unit
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

  def awaitReady[F[_] : Async](
                                client: Client[F],
                                registryUri: Uri,
                                interval: FiniteDuration,
                                timeout: FiniteDuration,
                                errorMessage: => String
                              ): F[Unit] =
    Ref[F].of[Option[Throwable]](None).flatMap { latestError =>
      lazy val retryLoop: F[Unit] =
        client.expect[String](registryUri.withPath(path"/v2/"))
          .void
          .handleErrorWith { error =>
            latestError.set(Some(error)) *>
              Async[F].sleep(interval) *>
              retryLoop
          }

      retryLoop.timeoutTo(
        timeout,
        latestError.get.flatMap { error =>
          Async[F].raiseError(new RuntimeException(errorMessage, error.orNull))
        }
      )
    }

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
