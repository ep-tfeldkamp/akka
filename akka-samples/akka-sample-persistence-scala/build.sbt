name := "akka-sample-persistence-scala"

isSnapshot := false

version := "2.4.19-dg-1.1.0" + (if (isSnapshot.value) "-SNAPSHOT" else "")

scalaVersion := "2.11.8"

libraryDependencies ++= Seq(
  "com.typesafe.akka" %% "akka-actor" % ("2.4.19-dg-1.1.0" + (if (isSnapshot.value) "-SNAPSHOT" else "")),
  "com.typesafe.akka" %% "akka-persistence" % ("2.4.19-dg-1.1.0" + (if (isSnapshot.value) "-SNAPSHOT" else "")),
  "org.iq80.leveldb"            % "leveldb"          % "0.7",
  "org.fusesource.leveldbjni"   % "leveldbjni-all"   % "1.8"
)

licenses := Seq(("CC0", url("http://creativecommons.org/publicdomain/zero/1.0")))
