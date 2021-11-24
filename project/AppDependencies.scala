import play.core.PlayVersion.current
import sbt._

object AppDependencies {

  val compile = Seq(
    "uk.gov.hmrc"           %% "bootstrap-backend-play-27" % "2.23.0",
    "uk.gov.hmrc"           %% "simple-reactivemongo"      % "7.30.0-play-27",
    "org.reactivemongo"     %% "reactivemongo-akkastream"  % "0.18.8",
    "com.enragedginger"     %% "akka-quartz-scheduler"     % "1.8.1-akka-2.5.x",
    "org.typelevel"         %% "cats-effect"               % "2.2.0-RC3",
    "uk.gov.hmrc"           %% "mongo-lock"                % "6.23.0-play-27",
    "org.apache.commons"     % "commons-text"              % "1.9",
    "commons-io"             % "commons-io"                % "2.9.0",
    "org.xhtmlrenderer"      % "flying-saucer-pdf"         % "9.1.20",
    "org.apache.poi"         % "poi-ooxml"                 % "4.1.2",
    "org.julienrf"          %% "play-json-derived-codecs"  % "4.0.1",
    "com.chuusai"           %% "shapeless"                 % "2.3.3",
    "software.amazon.awssdk" % "s3"                        % "2.16.66"
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
    "io.findify"             %% "s3mock"                    % "0.2.6"   % Test,
    "com.vladsch.flexmark"    % "flexmark-all"              % "0.35.10" % "test, it",
    "org.scalatestplus.play" %% "scalatestplus-play"        % "4.0.3"   % "test, it",
    "com.github.tomakehurst"  % "wiremock-jre8"             % "2.27.1"  % "test, it"
  )
}
