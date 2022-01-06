import play.core.PlayVersion.current
import sbt._

object AppDependencies {

  val compile = Seq(
    "uk.gov.hmrc"           %% "bootstrap-backend-play-28" % "5.16.0",
    "uk.gov.hmrc"           %% "simple-reactivemongo"      % "8.0.0-play-28",
    "org.reactivemongo"     %% "reactivemongo-akkastream"  % "0.20.9",
    "com.enragedginger"     %% "akka-quartz-scheduler"     % "1.9.1-akka-2.6.x",
    "org.typelevel"         %% "cats-effect"               % "2.2.0-RC3",
    "uk.gov.hmrc"           %% "mongo-lock"                % "7.0.0-play-28",
    "org.apache.commons"     % "commons-text"              % "1.9",
    "org.xhtmlrenderer"      % "flying-saucer-pdf"         % "9.1.20",
    "org.apache.poi"         % "poi-ooxml"                 % "4.1.2",
    "org.julienrf"          %% "play-json-derived-codecs"  % "10.0.2",
    "com.chuusai"           %% "shapeless"                 % "2.3.3"
  )

  val test = Seq(
    "uk.gov.hmrc"            %% "bootstrap-test-play-28"    % "5.16.0"  % Test,
    "org.scalatest"          %% "scalatest"                 % "3.1.4"   % Test,
    "org.scalacheck"         %% "scalacheck"                % "1.14.3"  % Test,
    "org.scalatestplus"      %% "scalacheck-1-14"           % "3.1.0.0" % Test,
    "de.flapdoodle.embed"     % "de.flapdoodle.embed.mongo" % "2.2.0"   % Test,
    "com.typesafe.play"      %% "play-test"                 % current   % Test,
    "org.mockito"            %% "mockito-scala"             % "1.16.42" % Test,
    "org.mockito"            %% "mockito-scala-scalatest"   % "1.16.23" % Test,
    "com.typesafe.akka"      %% "akka-testkit"              % "2.6.17"  % Test,
    "com.softwaremill.diffx" %% "diffx-scalatest"           % "0.5.6"   % Test,
    "com.vladsch.flexmark"    % "flexmark-all"              % "0.35.10" % "test, it",
    "org.scalatestplus.play" %% "scalatestplus-play"        % "5.1.0"   % "test, it",
    "com.github.tomakehurst"  % "wiremock-jre8"             % "2.27.1"  % "test, it"
  )
}
