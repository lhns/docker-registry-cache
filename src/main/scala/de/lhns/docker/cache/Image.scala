package de.lhns.docker.cache

import org.http4s.dsl.io.{Path => _}

import scala.util.chaining._

case class Image(registry: Registry, namespace: String, name: String) {
  lazy val labelWithoutRegistry: String = s"$namespace/$name"
}

object Image {
  def fromString(label: String, registries: Seq[Registry]): Image = {
    label.split("/", -1).toSeq.pipe {
      case parts@registryName +: (remainingParts@_ +: _) =>
        registries.find(_.host.equalsIgnoreCase(registryName)) match {
          case Some(registry) => (registry, remainingParts)
          case None => (registries.head, parts)
        }
      case parts => (registries.head, parts)
    }.pipe {
      case (registry, namespace :+ label) => (registry, namespace, label)
      case (_, _) => throw new RuntimeException("image label must not be empty")
    }.pipe {
      case (registry, namespace@_ +: _, name) => Image(registry, namespace.mkString("/"), name)
      case (registry, _, name) => Image(registry, "library", name)
    }
  }
}
