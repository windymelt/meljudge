scalaVersion := "3.9.0"

lazy val root = project
  .in(file("."))
  .enablePlugins(ScalaNativePlugin)
  .settings(
    name := "meljudge",
    libraryDependencies ++= Seq(
      "com.lihaoyi" %% "upickle" % "4.4.3",
      "org.scalameta" %% "munit" % "1.3.5" % Test,
    ),
  )
