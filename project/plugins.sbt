// Comment to get more information during initialization
logLevel := Level.Warn

resolvers += Resolver.typesafeRepo("releases")

addSbtPlugin("com.typesafe.play" % "sbt-plugin" % "2.8.18")

addSbtPlugin("com.eed3si9n" % "sbt-buildinfo" % "0.11.0")

addSbtPlugin("com.github.sbt" % "sbt-native-packager" % "1.9.11")

addSbtPlugin("com.gu" % "sbt-riffraff-artifact" % "1.1.18")

addSbtPlugin("org.scalameta" % "sbt-scalafmt" % "2.5.0")

libraryDependencies += "org.vafer" % "jdeb" % "1.3" artifacts (Artifact("jdeb", "jar", "jar"))
dependencyOverrides += "org.scala-lang.modules" %% "scala-xml" % "2.1.0"

addSbtPlugin("org.jmotor.sbt" % "sbt-dependency-updates" % "1.2.7")

addDependencyTreePlugin