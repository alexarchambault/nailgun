
inThisBuild(List(
  organization := "io.github.alexarchambault.bleep",
  homepage := Some(url(s"https://github.com/alexarchambault/nailgun")),
  licenses := Seq("Apache-2.0" -> url("http://www.apache.org/licenses/LICENSE-2.0")),
  developers := List(
    Developer("alexarchambault", "Alex Archambault", "", url(s"https://github.com/alexarchambault"))
  )
))

lazy val sonatypeSetting = Def.settings(
  sonatypeProfileName := "io.github.alexarchambault"
)

lazy val `nailgun-server` = project
  .settings(
    crossPaths := false,
    autoScalaLibrary := false,
    libraryDependencies ++= Seq(
      "net.java.dev.jna" % "jna" % "4.4.0",
      "net.java.dev.jna" % "jna-platform" % "4.4.0",
      "org.slf4j" % "slf4j-api" % "1.7.26"
    ),
    sonatypeSetting
  )

skip.in(publish) := true
sonatypeSetting
