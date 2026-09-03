scalaVersion := "3.9.0"

lazy val root = project
  .in(file("."))
  .enablePlugins(ScalaNativePlugin)
  .settings(
    name := "meljudge",
    libraryDependencies ++= Seq(
      "com.lihaoyi" %% "upickle" % "4.4.3",
      "com.lihaoyi" %% "fastparse" % "3.1.1",
      "com.monovore" %% "decline" % "2.6.2",
      "org.scalameta" %% "munit" % "1.3.5" % Test,
    ),
  )
