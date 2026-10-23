import play.core.PlayVersion.current
import sbt.*

object AppDependencies {

  val hmrcMongoVersion = "2.14.0"
  val bootstrapPlayVersion = "10.8.0"
  val pekkoVersion = "1.7.1"

  val compile = Seq(
    "uk.gov.hmrc"             %% "bootstrap-backend-play-30"   % bootstrapPlayVersion,
    "uk.gov.hmrc.mongo"       %% "hmrc-mongo-play-30"          % hmrcMongoVersion,
    "io.github.samueleresca"  %% "pekko-quartz-scheduler"      % "1.3.0-pekko-1.1.x",
    "org.typelevel"           %% "cats-effect"                 % "2.2.0-RC3",
    "org.apache.commons"       % "commons-text"                % "1.15.0",
    "org.apache.commons"       % "commons-io"                  % "1.3.2",
    "org.xhtmlrenderer"        % "flying-saucer-pdf"           % "10.6.0",
    "org.apache.poi"           % "poi-ooxml"                   % "5.5.1",
    "org.julienrf"            %% "play-json-derived-codecs"    % "11.0.0",
    "com.chuusai"             %% "shapeless"                   % "2.3.13",
    "uk.gov.hmrc.objectstore" %% "object-store-client-play-30" % "2.6.0",
    "org.apache.pekko"        %% "pekko-protobuf-v3"           % pekkoVersion,
    "org.apache.pekko"        %% "pekko-serialization-jackson" % pekkoVersion,
    "org.apache.pekko"        %% "pekko-stream"                % pekkoVersion,
    "org.apache.pekko"        %% "pekko-actor-typed"           % pekkoVersion,
    "org.apache.pekko"        %% "pekko-slf4j"                 % pekkoVersion
  )

  val test = Seq(
    "uk.gov.hmrc"            %% "bootstrap-test-play-30"      % bootstrapPlayVersion % Test,
    "uk.gov.hmrc.mongo"      %% "hmrc-mongo-test-play-30"     % hmrcMongoVersion     % Test,
    "org.scalatest"          %% "scalatest"                   % "3.2.20"             % Test,
    "org.scalacheck"         %% "scalacheck"                  % "1.20.0"             % Test,
    "org.scalatestplus"      %% "scalacheck-1-14"             % "3.2.2.0"            % Test,
    "org.playframework"      %% "play-test"                   % current              % Test,
    "org.mockito"            %% "mockito-scala"               % "1.17.45"            % Test,
    "org.mockito"            %% "mockito-scala-scalatest"     % "1.17.45"            % Test,
    "org.apache.pekko"       %% "pekko-testkit"               % pekkoVersion         % Test,
    "org.apache.pekko"       %% "pekko-protobuf-v3"           % pekkoVersion         % Test,
    "org.apache.pekko"       %% "pekko-slf4j"                 % pekkoVersion         % Test,
    "org.apache.pekko"       %% "pekko-serialization-jackson" % pekkoVersion         % Test,
    "org.apache.pekko"       %% "pekko-actor-typed"           % pekkoVersion         % Test,
    "org.apache.pekko"       %% "pekko-stream"                % pekkoVersion         % Test,
    "com.softwaremill.diffx" %% "diffx-scalatest-should"      % "0.9.0"              % Test,
    "com.vladsch.flexmark"    % "flexmark-all"                % "0.64.8"             % Test,
    "org.scalatestplus.play" %% "scalatestplus-play"          % "7.0.2"              % Test
  )

  val dependencyOverrides = Seq(
    "com.mchange" % "mchange-commons-java" % "0.6.1",
    "com.mchange" % "c3p0"                 % "0.14.2"
  )
}
