name := "akka-sample-remote-scala"

isSnapshot := false

version := "2.4.19-dg-1.1.0" + (if (isSnapshot.value) "-SNAPSHOT" else "")

scalaVersion := "2.11.8"

libraryDependencies ++= Seq(
  "com.typesafe.akka" %% "akka-actor" % ("2.4.19-dg-1.1.0" + (if (isSnapshot.value) "-SNAPSHOT" else "")),
  "com.typesafe.akka" %% "akka-remote" % ("2.4.19-dg-1.1.0" + (if (isSnapshot.value) "-SNAPSHOT" else ""))
)

licenses := Seq(("CC0", url("http://creativecommons.org/publicdomain/zero/1.0")))
