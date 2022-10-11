package de.lhns.docker.cache

import cats.effect.{ExitCode, IO, IOApp, Resource}
import cats.syntax.traverse._
import com.comcast.ip4s._
import org.http4s.HttpApp
import org.http4s.dsl.io.{Path => _}
import org.http4s.ember.server.EmberServerBuilder
import org.http4s.implicits._
import org.http4s.jdkhttpclient.JdkHttpClient
import org.http4s.server.Server
import org.http4s.server.middleware.ErrorAction
import org.log4s.getLogger

import java.nio.file.{Path, Paths}
import scala.concurrent.duration._

object Main extends IOApp {
  private val logger = getLogger

  val defaultRootDirectory: Path = Paths.get("/var/lib/registry")

  override def run(args: List[String]): IO[ExitCode] = {
    val registries: Seq[RegistryConfig] = RegistryConfig.fromEnv

    logger.info("Registries:\n" + registries.map(_ + "\n").mkString)
    logger.info("starting proxies")

    applicationResource(registries).use(_ => IO.never)
  }

  def applicationResource(registries: Seq[RegistryConfig]): Resource[IO, Unit] =
    for {
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
      _ <- serverResource(
        host"0.0.0.0",
        port"5000",
        new RegistryProxyRoutes(client, registryProxies).toRoutes.orNotFound
      )
    } yield ()

  def serverResource(host: Host, port: Port, http: HttpApp[IO]): Resource[IO, Server] =
    EmberServerBuilder
      .default[IO]
      .withHost(host)
      .withPort(port)
      .withHttpApp(ErrorAction.log(
        http = http,
        messageFailureLogAction = (t, msg) => IO(logger.debug(t)(msg)),
        serviceErrorLogAction = (t, msg) => IO(logger.error(t)(msg))
      ))
      .build
}
