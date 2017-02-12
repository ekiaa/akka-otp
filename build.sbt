import sbt.Keys.{artifactPath, libraryDependencies, mainClass, managedClasspath, name, organization, packageBin, resolvers, version}


lazy val projectName = "akka-otp-example"
lazy val projectVersion = "0.0.1"
lazy val akkaVersion = "2.4.17"

lazy val commonSettings = Seq(
  resolvers ++= Seq(
    "Sonatype OSS" at "https://oss.sonatype.org/content/repositories/releases/"
  ),
  organization := "ekiaa",
  scalaVersion := "2.11.7"
)

lazy val root = (project in file(".")).aggregate(service)

lazy val service =
  project.in(file("service")).
      configs(IntegrationTest).
      settings(commonSettings: _*).
      settings(Defaults.itSettings: _*).
      settings(
        scalacOptions ++= Seq("-unchecked", "-deprecation", "-feature"),
        version := projectVersion,
        name := projectName,
        libraryDependencies ++= Seq(
          "com.typesafe" % "config" % "1.3.0",
          "com.typesafe.scala-logging" %% "scala-logging" % "3.1.0",
          "ch.qos.logback" % "logback-classic" % "1.1.3",
          "org.scalamock" %% "scalamock-scalatest-support" % "3.2" % "test",
          "org.scalatest" %% "scalatest" % "2.2.4" % "it,test",

          "com.typesafe.akka" %% "akka-actor" % akkaVersion,
          "com.typesafe.akka" %% "akka-slf4j" % akkaVersion,
          "com.typesafe.akka" %% "akka-testkit" % akkaVersion,
          "com.typesafe.akka" %% "akka-persistence" % akkaVersion,

          "com.github.dnvriend" %% "akka-persistence-jdbc" % "2.6.12",
          "org.iq80.leveldb" % "leveldb" % "0.7",
          "org.fusesource.leveldbjni" % "leveldbjni-all"% "1.8",

          "com.typesafe.slick" %% "slick" % "3.1.1",
          "com.typesafe.slick" %% "slick-hikaricp" % "3.1.1",
          "com.zaxxer" % "HikariCP" % "2.3.7",
          "org.postgresql" % "postgresql" % "9.4.1207",
          "com.github.nscala-time" %% "nscala-time" % "2.10.0",
          "joda-time" % "joda-time" % "2.7",
          "org.joda" % "joda-convert" % "1.8",
          "com.github.tototoshi" %% "slick-joda-mapper" % "2.1.0",
          "com.github.tminglei" %% "slick-pg" % "0.10.0"
        ),
        parallelExecution in Test := false
      )