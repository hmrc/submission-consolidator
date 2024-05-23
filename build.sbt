val appName = "submission-consolidator"

lazy val IntegrationTest = config("it") extend (Test)

ThisBuild / majorVersion := 1
ThisBuild / scalaVersion := "2.13.12"

lazy val microservice = Project(appName, file("."))
  .enablePlugins(
    play.sbt.PlayScala,
    SbtDistributablesPlugin
  )
  .settings(
    organization := "uk.gov.hmrc",
    majorVersion := 0,
    PlayKeys.playDefaultPort := 9198,
    scalafmtOnCompile := true,
    libraryDependencies ++= AppDependencies.compile ++ AppDependencies.test,
    scalacOptions ++= Seq(
      "-Xfatal-warnings",
      "-Xlint:-missing-interpolator,-byname-implicit,_",
      "-Ywarn-numeric-widen",
      //"-Ywarn-value-discard",
      //"-Ywarn-dead-code",
      "-deprecation",
      "-feature",
      "-unchecked",
      "-language:higherKinds",
      // silence all warnings on autogenerated files
      "-Wconf:src=routes/.*:silent"
    ),
    routesImport ++= Seq(
      "consolidator.repositories.NotificationStatus",
      "consolidator.repositories.CorrelationId",
      "common.ValueClassBinder._"
    )
  )
  .configs(IntegrationTest)
  .settings(resolvers += Resolver.jcenterRepo)
