import play.core.PlayVersion.current
import sbt._

object AppDependencies {

  val compile = Seq(
    "uk.gov.hmrc"                %% "bootstrap-backend-play-28"   % "7.13.0",
    "uk.gov.hmrc.mongo"          %% "hmrc-mongo-play-28"          % "0.68.0",
    "com.enragedginger"          %% "akka-quartz-scheduler"       % "1.9.1-akka-2.6.x",
    "org.typelevel"              %% "cats-effect"                 % "2.2.0-RC3",
    "org.apache.commons"          % "commons-text"                % "1.10.0",
    "org.apache.commons"          % "commons-io"                % "1.3.2",
    "org.xhtmlrenderer"           % "flying-saucer-pdf"           % "9.2.2",
    "org.apache.poi"              % "poi-ooxml"                   % "5.2.3",
    "org.julienrf"               %% "play-json-derived-codecs"    % "10.0.2",
    "com.chuusai"                %% "shapeless"                   % "2.3.3",
    "uk.gov.hmrc.objectstore"    %% "object-store-client-play-28" % "1.0.0",
  )

  val test = Seq(
    "uk.gov.hmrc"            %% "bootstrap-test-play-28"    % "7.13.0"  % Test,
    "org.scalatest"          %% "scalatest"                 % "3.1.4"   % Test,
    "org.scalacheck"         %% "scalacheck"                % "1.14.3"  % Test,
    "org.scalatestplus"      %% "scalacheck-1-14"           % "3.1.0.0" % Test,
    "de.flapdoodle.embed"     % "de.flapdoodle.embed.mongo" % "3.5.3"   % Test,
    "com.typesafe.play"      %% "play-test"                 % current   % Test,
    "org.mockito"            %% "mockito-scala"             % "1.16.42" % Test,
    "org.mockito"            %% "mockito-scala-scalatest"   % "1.16.23" % Test,
    "com.typesafe.akka"      %% "akka-testkit"              % "2.6.20"  % Test,
    "com.softwaremill.diffx" %% "diffx-scalatest"           % "0.5.6"   % Test,
    "com.vladsch.flexmark"    % "flexmark-all"              % "0.35.10" % "test, it",
    "org.scalatestplus.play" %% "scalatestplus-play"        % "5.1.0"   % "test, it",
    "com.github.tomakehurst"  % "wiremock-jre8"             % "2.27.1"  % "test, it"
  )
}
