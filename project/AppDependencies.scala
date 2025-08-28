import play.core.PlayVersion.current
import sbt.*

object AppDependencies {

  val hmrcMongoVersion = "2.7.0"
  val bootstrapPlayVersion = "10.1.0"

  val compile = Seq(
    "uk.gov.hmrc"                %% "bootstrap-backend-play-30"   % bootstrapPlayVersion,
    "uk.gov.hmrc.mongo"          %% "hmrc-mongo-play-30"          % hmrcMongoVersion,
    "io.github.samueleresca"     %% "pekko-quartz-scheduler"      % "1.3.0-pekko-1.1.x",
    "org.typelevel"              %% "cats-effect"                 % "2.2.0-RC3",
    "org.apache.commons"          % "commons-text"                % "1.14.0",
    "org.apache.commons"          % "commons-io"                  % "1.3.2",
    "org.xhtmlrenderer"           % "flying-saucer-pdf"           % "9.13.3",
    "org.apache.poi"              % "poi-ooxml"                   % "5.4.1",
    "org.julienrf"               %% "play-json-derived-codecs"    % "11.0.0",
    "com.chuusai"                %% "shapeless"                   % "2.3.13",
    "uk.gov.hmrc.objectstore"    %% "object-store-client-play-30" % "2.4.0",
    "org.apache.pekko"           %% "pekko-protobuf-v3"           % "1.1.3",
    "org.apache.pekko"           %% "pekko-serialization-jackson" % "1.1.3",
    "org.apache.pekko"           %% "pekko-stream"                % "1.1.3",
    "org.apache.pekko"           %% "pekko-actor-typed"           % "1.1.3",
    "org.apache.pekko"           %% "pekko-slf4j"                 % "1.1.3"
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
    "com.softwaremill.diffx" %% "diffx-scalatest-should"  % "0.9.0"              % Test,
    "com.vladsch.flexmark"    % "flexmark-all"            % "0.64.8"             % Test,
    "org.scalatestplus.play" %% "scalatestplus-play"      % "7.0.2"              % Test
  )
}
