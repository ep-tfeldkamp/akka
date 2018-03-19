/**
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */
package akka

import sbt._
import sbt.Keys._
import java.io.File

object Publish extends AutoPlugin {

  val defaultPublishTo = settingKey[File]("Default publish directory")

  override def trigger = allRequirements

  override lazy val projectSettings = Seq(
    crossPaths := false,
    pomExtra := akkaPomExtra,
    publishTo := {
      if (isSnapshot.value) localRepo(defaultPublishTo.value) else Some(artifactoryPublishTo)
    },
    credentials ++= akkaCredentials,
    organizationName := "Lightbend Inc.",
    organizationHomepage := Some(url("http://www.lightbend.com")),
    publishMavenStyle := true,
    pomIncludeRepository := { x => false },
    defaultPublishTo := crossTarget.value / "repository"
  )

  def akkaPomExtra = {
    <inceptionYear>2009</inceptionYear>
    <scm>
      <url>git://github.com/akka/akka.git</url>
      <connection>scm:git:git@github.com:akka/akka.git</connection>
    </scm>
    <developers>
      <developer>
        <id>akka-contributors</id>
        <name>Akka Contributors</name>
        <email>akka-dev@googlegroups.com</email>
        <url>https://github.com/akka/akka/graphs/contributors</url>
      </developer>
    </developers>
  }

  def artifactoryPublishTo: Resolver = Resolver.url(
    "Artifactory third party library releases",
    new URL(s"http://artifactory.zentrale.local/ext-release-local")
  )(Resolver.mavenStylePatterns)

  private def localRepo(repository: File): Option[Resolver] =
    Some(Resolver.file("Default Local Repository", repository))

  private def akkaCredentials: Seq[Credentials] =
    Option(System.getProperty("akka.publish.credentials", null)).map(f => Credentials(new File(f))).toSeq

}
