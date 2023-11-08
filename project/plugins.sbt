// Comment to get more information during initialization
logLevel := Level.Warn

resolvers += Resolver.typesafeRepo("releases")

addSbtPlugin("org.playframework" % "sbt-plugin" % "3.0.0")

addSbtPlugin("com.eed3si9n" % "sbt-buildinfo" % "0.7.0")

addSbtPlugin("com.typesafe.sbt" % "sbt-native-packager" % "1.3.25")

addSbtPlugin("com.gu" % "sbt-riffraff-artifact" % "1.1.18")

addSbtPlugin("org.scalameta" % "sbt-scalafmt" % "2.5.0")

addSbtPlugin("org.jmotor.sbt" % "sbt-dependency-updates" % "1.2.7")

addSbtPlugin("com.github.sbt" % "sbt-pgp" % "2.2.1") // Needed for membership-common

addSbtPlugin("org.xerial.sbt" % "sbt-sonatype" % "3.9.15") // Needed for membership-common


libraryDependencies += "org.vafer" % "jdeb" % "1.10"

dependencyOverrides += "org.scala-lang.modules" %% "scala-xml" % "2.1.0"
