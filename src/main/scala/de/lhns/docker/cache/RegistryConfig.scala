package de.lhns.docker.cache

import cats.data.OptionT
import cats.effect.Sync
import cats.effect.std.Env
import io.circe.generic.semiauto._
import io.circe.{Codec, Decoder}
import org.http4s.dsl.io.{Path => _}

case class RegistryConfig(registry: Registry,
                          variables: Option[Map[String, String]]) {
  val variablesOrDefault: Map[String, String] = variables.getOrElse(Map.empty)
}

object RegistryConfig {
  implicit val codec: Codec[RegistryConfig] = {
    val defaultCodec = deriveCodec[RegistryConfig]
    Codec.from(
      Decoder[Registry].map(RegistryConfig(_, None)).or(defaultCodec),
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
