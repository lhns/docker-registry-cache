package de.lhns.docker.cache

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

  lazy val fromEnv: Seq[RegistryConfig] =
    Option(System.getenv("CONFIG"))
      .toRight(new IllegalArgumentException("Missing variable: CONFIG"))
      // https://github.com/circe/circe-config/issues/195
      .flatMap(io.circe.parser.decode[Seq[RegistryConfig]](_))
      .toTry.get
}
