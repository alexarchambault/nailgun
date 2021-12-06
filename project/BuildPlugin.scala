package nailgun.build
import ch.epfl.scala.sbt.pom.{PomBuild, SbtPomKeys}
import sbt.{Compile, Def, Keys, file, url, Developer, Global}

object NailgunBuild extends PomBuild {
  override def projectDefinitions(baseDirectory: sbt.File) =
    super.projectDefinitions(baseDirectory).map { p =>
      p.settings(projectSettings)
    }

  val projectSettings: Seq[Def.Setting[_]] = List(
    SbtPomKeys.isJavaOnly := true,
    Keys.crossPaths := false,
    Keys.startYear := Some(2017),
    Keys.autoAPIMappings := true,
    Keys.publishMavenStyle := true,
    Keys.homepage := Some(url(s"https://github.com/alexarchambault/nailgun")),
    Keys.licenses := Seq("Apache-2.0" -> url("http://www.apache.org/licenses/LICENSE-2.0")),
    Keys.developers := List(
      Developer("alexarchambault", "Alex Archambault", "", url(s"https://github.com/alexarchambault"))
    ),
    Keys.organization := "io.github.alexarchambault.bleep"
  )
}
