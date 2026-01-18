package de.lhns.docker.cache

import cats.data.OptionT
import cats.effect.std.Env
import cats.effect.{IO, Sync}
import io.circe.generic.semiauto.*
import io.circe.{Codec, Decoder, Encoder}
import org.http4s.Uri
import org.http4s.dsl.io.Path as _

case class RegistryUri(uri: Uri)

object RegistryUri {
  implicit val codec: Codec[RegistryUri] = Codec.from(
    Decoder[String].map(string => RegistryUri(Registry.registryUriFromString(string))),
    Encoder[String].contramap[RegistryUri](_.uri.renderString)
  )
}

case class RegistryConfig(registry: RegistryUri,
                          variables: Option[Map[String, String]],
                          externalUri: Option[RegistryUri],
                          healthcheck: Option[Boolean]) {
  val variablesOrDefault: Map[String, String] = variables.getOrElse(Map.empty)

  val healthcheckOrDefault: Boolean = healthcheck.getOrElse(true)

  val toRegistry: Registry[IO] = externalUri match {
    case Some(RegistryUri(proxyUri)) =>
      Registry.externalProxy(registry.uri, proxyUri)

    case None =>
      Registry(registry.uri)
  }
}

object RegistryConfig {
  implicit val codec: Codec[RegistryConfig] = {
    val defaultCodec = deriveCodec[RegistryConfig]
    Codec.from(
      Decoder[RegistryUri].map(RegistryConfig(_, None, None, None)).or(defaultCodec),
      defaultCodec
    )
  }

  def fromEnv[F[_] : Sync](env: Env[F]): F[Seq[RegistryConfig]] =
    OptionT(env.get("CONFIG"))
      .toRight(new IllegalArgumentException("Missing environment variable: CONFIG"))
      // https://github.com/circe/circe-config/issues/195
      .subflatMap(io.circe.parser.decode[Seq[RegistryConfig]](_))
      .rethrowT
}
