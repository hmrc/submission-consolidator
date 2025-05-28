import play.core.PlayVersion.current
import sbt.*

object AppDependencies {

  val hmrcMongoVersion = "2.6.0"
  val bootstrapPlayVersion = "8.6.0"

  val compile = Seq(
    "uk.gov.hmrc"                %% "bootstrap-backend-play-30"   % bootstrapPlayVersion,
    "uk.gov.hmrc.mongo"          %% "hmrc-mongo-play-30"          % hmrcMongoVersion,
    "io.github.samueleresca"     %% "pekko-quartz-scheduler"      % "1.0.0-pekko-1.0.x",
    "org.typelevel"              %% "cats-effect"                 % "2.2.0-RC3",
    "org.apache.commons"          % "commons-text"                % "1.10.0",
    "org.apache.commons"          % "commons-io"                  % "1.3.2",
    "org.xhtmlrenderer"           % "flying-saucer-pdf"           % "9.2.2",
    "org.apache.poi"              % "poi-ooxml"                   % "5.2.3",
    "org.julienrf"               %% "play-json-derived-codecs"    % "11.0.0",
    "com.chuusai"                %% "shapeless"                   % "2.3.3",
    "uk.gov.hmrc.objectstore"    %% "object-store-client-play-30" % "2.2.0",
  )

  val test = Seq(
    "uk.gov.hmrc"            %% "bootstrap-test-play-30"  % bootstrapPlayVersion % Test,
    "uk.gov.hmrc.mongo"      %% "hmrc-mongo-test-play-30" % hmrcMongoVersion     % Test,
    "org.scalatest"          %% "scalatest"               % "3.2.19"             % Test,
    "org.scalacheck"         %% "scalacheck"              % "1.18.1"             % Test,
    "org.scalatestplus"      %% "scalacheck-1-14"         % "3.2.2.0"            % Test,
    "org.playframework"      %% "play-test"               % current              % Test,
    "org.mockito"            %% "mockito-scala"           % "1.17.45"            % Test,
    "org.mockito"            %% "mockito-scala-scalatest" % "1.17.45"            % Test,
    "org.apache.pekko"       %% "pekko-testkit"           % "1.1.3"              % Test,
    "org.apache.pekko"       %% "pekko-protobuf-v3"       % "1.1.3"              % Test,
    "org.apache.pekko"       %% "pekko-slf4j"             % "1.1.3"              % Test,
    "org.apache.pekko"       %% "pekko-serialization-jackson" % "1.1.3"          % Test,
    "org.apache.pekko"       %% "pekko-actor-typed"       % "1.1.3"              % Test,
    "org.apache.pekko"       %% "pekko-stream"            % "1.1.3"              % Test,
    "com.softwaremill.diffx" %% "diffx-scalatest"         % "0.5.6"              % Test,
    "com.vladsch.flexmark"    % "flexmark-all"            % "0.35.10"            % Test,
    "org.scalatestplus.play" %% "scalatestplus-play"      % "7.0.1"              % Test
  )
}
