import play.core.PlayVersion.current
import play.sbt.PlayImport._
import sbt.Keys.libraryDependencies
import sbt._

object AppDependencies {

  val compile = Seq(
    "uk.gov.hmrc"       %% "bootstrap-backend-play-27" % "2.23.0",
    "uk.gov.hmrc"       %% "simple-reactivemongo"      % "7.30.0-play-27",
    "com.enragedginger" %% "akka-quartz-scheduler"     % "1.8.1-akka-2.5.x"
  )

  val test = Seq(
    "uk.gov.hmrc"            %% "bootstrap-test-play-27"    % "2.23.0"  % Test,
    "org.scalatest"          %% "scalatest"                 % "3.1.2"   % Test,
    "org.scalacheck"         %% "scalacheck"                % "1.14.3"  % Test,
    "org.scalatestplus"      %% "scalacheck-1-14"           % "3.1.0.0" % Test,
    "de.flapdoodle.embed"     % "de.flapdoodle.embed.mongo" % "2.2.0"   % Test,
    "com.typesafe.play"      %% "play-test"                 % current   % Test,
    "org.mockito"            %% "mockito-scala"             % "1.14.8"  % Test,
    "org.mockito"            %% "mockito-scala-scalatest"   % "1.14.8"  % Test,
    "com.typesafe.akka"      %% "akka-testkit"              % "2.5.31"  % Test,
    "com.softwaremill.diffx" %% "diffx-scalatest"           % "0.3.29"  % Test,
    "com.vladsch.flexmark"    % "flexmark-all"              % "0.35.10" % "test, it",
    "org.scalatestplus.play" %% "scalatestplus-play"        % "4.0.3"   % "test, it"
  )
}
