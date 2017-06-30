name := "akka-sample-persistence-scala"

version := "2.4.19-dg-1.0.0-SNAPSHOT"

scalaVersion := "2.11.8"

libraryDependencies ++= Seq(
  "com.typesafe.akka" %% "akka-actor" % "2.4.19-dg-1.0.0-SNAPSHOT",
  "com.typesafe.akka" %% "akka-persistence" % "2.4.19-dg-1.0.0-SNAPSHOT",
  "org.iq80.leveldb"            % "leveldb"          % "0.7",
  "org.fusesource.leveldbjni"   % "leveldbjni-all"   % "1.8"
)

licenses := Seq(("CC0", url("http://creativecommons.org/publicdomain/zero/1.0")))
