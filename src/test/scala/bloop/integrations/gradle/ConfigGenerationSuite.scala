package bloop.integrations.gradle

import java.io.File
import java.net.URLClassLoader
import java.nio.charset.StandardCharsets
import java.nio.file.Files

import bloop.cli.Commands
import bloop.config.Config
import bloop.config.ConfigEncoderDecoders._
import bloop.engine.{Build, Run, State}
import bloop.io.AbsolutePath
import bloop.logging.BloopLogger
import bloop.util.TestUtil
import io.circe.parser._
import org.gradle.testkit.runner.{BuildResult, GradleRunner}
import org.gradle.testkit.runner.TaskOutcome._
import org.junit._
import org.junit.Assert._
import org.junit.rules.TemporaryFolder
import bloop.engine.BuildLoader

import scala.collection.JavaConverters._

class ConfigGenerationSuite {
  private val gradleVersion: String = "4.8.1"
  private val testProjectDir_ = new TemporaryFolder()
  @Rule def testProjectDir: TemporaryFolder = testProjectDir_

  @Test def pluginCanBeApplied(): Unit = {
    val buildFile = testProjectDir.newFile("build.gradle")
    testProjectDir.newFolder("src", "main", "scala")
    writeBuildScript(
      buildFile,
      """
        |plugins {
        |  id 'bloop'
        |}
        |
        |apply plugin: 'scala'
        |apply plugin: 'bloop'
      """.stripMargin
    )

    val result: BuildResult =
      GradleRunner
        .create()
        .withGradleVersion(gradleVersion)
        .withProjectDir(testProjectDir.getRoot)
        .withPluginClasspath(getClasspath.asJava)
        .withArguments("tasks")
        .build()

    assertEquals(SUCCESS, result.task(":tasks").getOutcome)
  }

  @Test def bloopInstallTaskAdded(): Unit = {
    val buildFile = testProjectDir.newFile("build.gradle")
    testProjectDir.newFolder("src", "main", "scala")
    writeBuildScript(
      buildFile,
      """
        |plugins {
        |  id 'bloop'
        |}
        |
        |apply plugin: 'scala'
        |apply plugin: 'bloop'
      """.stripMargin
    )

    val result: BuildResult =
      GradleRunner
        .create()
        .withGradleVersion(gradleVersion)
        .withProjectDir(testProjectDir.getRoot)
        .withPluginClasspath(getClasspath.asJava)
        .withArguments("tasks", "--all")
        .build()

    assertTrue(result.getOutput.lines.contains("bloopInstall"))
  }

  @Test def worksWithScala211Project(): Unit = {
    worksWithGivenScalaVersion("2.11.12")
  }

  @Test def worksWithScala212Project(): Unit = {
    worksWithGivenScalaVersion("2.12.6")
  }

  @Test def worksWithTransientProjectDependencies(): Unit = {
    val buildSettings = testProjectDir.newFile("settings.gradle")
    val buildDirA = testProjectDir.newFolder("a")
    testProjectDir.newFolder("a", "src", "main", "scala")
    testProjectDir.newFolder("a", "src", "test", "scala")
    val buildDirB = testProjectDir.newFolder("b")
    testProjectDir.newFolder("b", "src", "main", "scala")
    testProjectDir.newFolder("b", "src", "test", "scala")
    val buildDirC = testProjectDir.newFolder("c")
    testProjectDir.newFolder("c", "src", "main", "scala")
    testProjectDir.newFolder("c", "src", "test", "scala")
    val buildDirD = testProjectDir.newFolder("d")
    testProjectDir.newFolder("d", "src", "main", "scala")
    testProjectDir.newFolder("d", "src", "test", "scala")
    val buildFileA = new File(buildDirA, "build.gradle")
    val buildFileB = new File(buildDirB, "build.gradle")
    val buildFileC = new File(buildDirC, "build.gradle")
    val buildFileD = new File(buildDirD, "build.gradle")

    writeBuildScript(
      buildFileA,
      s"""
         |plugins {
         |  id 'bloop'
         |}
         |
         |apply plugin: 'scala'
         |apply plugin: 'bloop'
         |
         |repositories {
         |  mavenCentral()
         |}
         |
         |dependencies {
         |  compile 'org.scala-lang:scala-library:2.12.6'
         |}
      """.stripMargin
    )

    writeBuildScript(
      buildFileB,
      s"""
         |plugins {
         |  id 'bloop'
         |}
         |
         |apply plugin: 'scala'
         |apply plugin: 'bloop'
         |
         |repositories {
         |  mavenCentral()
         |}
         |
         |dependencies {
         |  implementation 'org.typelevel:cats-core_2.12:1.2.0'
         |  compile project(':a')
         |  implementation(project(':c'))
         |}
      """.stripMargin
    )

    writeBuildScript(
      buildFileC,
      s"""
         |plugins {
         |  id 'bloop'
         |}
         |
         |apply plugin: 'scala'
         |apply plugin: 'bloop'
         |
         |repositories {
         |  mavenCentral()
         |}
         |
         |dependencies {
         |  compile 'org.scala-lang:scala-library:2.12.6'
         |}
      """.stripMargin
    )

    writeBuildScript(
      buildFileD,
      s"""
         |plugins {
         |  id 'bloop'
         |}
         |
         |apply plugin: 'scala'
         |apply plugin: 'bloop'
         |
         |repositories {
         |  mavenCentral()
         |}
         |
         |dependencies {
         |  compile project(':b')
         |}
      """.stripMargin
    )

    writeBuildScript(
      buildSettings,
      """
        |rootProject.name = 'scala-multi-projects'
        |include 'a'
        |include 'b'
        |include 'c'
        |include 'd'
      """.stripMargin
    )

    createHelloWorldScalaSource(buildDirA, "package x { trait A }")
    createHelloWorldScalaSource(buildDirB, "package y { trait B extends x.A { println(new z.C) } }")
    createHelloWorldScalaSource(buildDirC, "package z { class C }")
    createHelloWorldScalaSource(
      buildDirD,
      "package zz { class D extends x.A { println(new z.C) } }")

    GradleRunner
      .create()
      .withGradleVersion(gradleVersion)
      .withProjectDir(testProjectDir.getRoot)
      .withPluginClasspath(getClasspath.asJava)
      .withArguments("bloopInstall", "-Si")
      .build()

    val projectName = testProjectDir.getRoot.getName
    val bloopDir = new File(testProjectDir.getRoot, ".bloop")
    val bloopNone = new File(bloopDir, s"${projectName}.json")
    val bloopA = new File(bloopDir, "a.json")
    val bloopB = new File(bloopDir, "b.json")
    val bloopC = new File(bloopDir, "c.json")
    val bloopD = new File(bloopDir, "d.json")
    val bloopATest = new File(bloopDir, "a-test.json")
    val bloopBTest = new File(bloopDir, "b-test.json")
    val bloopCTest = new File(bloopDir, "c-test.json")
    val bloopDTest = new File(bloopDir, "d-test.json")

    assertFalse(bloopNone.exists())
    val configA = readValidBloopConfig(bloopA)
    val configB = readValidBloopConfig(bloopB)
    val configC = readValidBloopConfig(bloopC)
    val configD = readValidBloopConfig(bloopD)
    val configATest = readValidBloopConfig(bloopATest)
    val configBTest = readValidBloopConfig(bloopBTest)
    val configCTest = readValidBloopConfig(bloopCTest)
    val configDTest = readValidBloopConfig(bloopDTest)

    assertTrue(configA.project.`scala`.exists(_.version == "2.12.6"))
    assertTrue(configB.project.`scala`.exists(_.version == "2.12.6"))
    assertTrue(configC.project.`scala`.exists(_.version == "2.12.6"))
    assertTrue(configD.project.`scala`.exists(_.version == "2.12.6"))
    assertTrue(configA.project.dependencies.isEmpty)
    assertEquals(List("a", "c"), configB.project.dependencies.sorted)
    assertTrue(configC.project.dependencies.isEmpty)
    assertEquals(List("b"), configD.project.dependencies.sorted)
    assertEquals(List("a"), configATest.project.dependencies)
    assertEquals(List("a", "b", "c"), configBTest.project.dependencies.sorted)
    assertEquals(List("c"), configCTest.project.dependencies)
    assertEquals(List("b", "d"), configDTest.project.dependencies.sorted)

    def hasClasspathEntryName(config: Config.File, entryName: String): Boolean =
      config.project.classpath.exists(_.toString.contains(entryName))

    def assertSources(config: Config.File, entryName: String): Unit = {
      assertTrue(
        s"Resolution field for ${config.project.name} does not exist",
        config.project.resolution.isDefined)
      config.project.resolution.foreach { resolution =>
        val sources = resolution.modules.find(
          module =>
            module.name.contains(entryName) && module.artifacts.exists(
              _.classifier.contains("sources")))
        assertTrue(s"Sources for $entryName do not exist", sources.isDefined)
        assertTrue(
          s"There are more sources than one for $entryName:\n${sources.get.artifacts.mkString("\n")}",
          sources.exists(_.artifacts.size == 2))
      }
    }

    assertTrue(hasClasspathEntryName(configA, "scala-library"))
    assertSources(configA, "scala-library")
    assertTrue(hasClasspathEntryName(configB, "scala-library"))
    assertSources(configB, "scala-library")
    assertTrue(hasClasspathEntryName(configC, "scala-library"))
    assertSources(configC, "scala-library")
    assertTrue(hasClasspathEntryName(configATest, "scala-library"))
    assertSources(configATest, "scala-library")
    assertTrue(hasClasspathEntryName(configBTest, "scala-library"))
    assertSources(configBTest, "scala-library")
    assertTrue(hasClasspathEntryName(configCTest, "scala-library"))
    assertSources(configCTest, "scala-library")
    assertTrue(
      hasClasspathEntryName(configATest, "/a/build/classes".replace('/', File.separatorChar)))
    assertTrue(
      hasClasspathEntryName(configCTest, "/c/build/classes".replace('/', File.separatorChar)))
    assertTrue(hasClasspathEntryName(configB, "cats-core"))
    assertSources(configB, "cats-core")
    assertTrue(hasClasspathEntryName(configB, "/a/build/classes".replace('/', File.separatorChar)))
    assertTrue(hasClasspathEntryName(configB, "/c/build/classes".replace('/', File.separatorChar)))
    assertTrue(hasClasspathEntryName(configBTest, "cats-core"))
    assertSources(configBTest, "cats-core")
    assertTrue(
      hasClasspathEntryName(configBTest, "/a/build/classes".replace('/', File.separatorChar)))
    assertTrue(
      hasClasspathEntryName(configBTest, "/b/build/classes".replace('/', File.separatorChar)))
    assertTrue(
      hasClasspathEntryName(configBTest, "/c/build/classes".replace('/', File.separatorChar)))
    assertTrue(hasClasspathEntryName(configD, "/a/build/classes".replace('/', File.separatorChar)))
    assertTrue(hasClasspathEntryName(configD, "/b/build/classes".replace('/', File.separatorChar)))
    assertTrue(hasClasspathEntryName(configD, "/c/build/classes".replace('/', File.separatorChar)))
    assertTrue(
      hasClasspathEntryName(configDTest, "/a/build/classes".replace('/', File.separatorChar)))
    assertTrue(
      hasClasspathEntryName(configDTest, "/b/build/classes".replace('/', File.separatorChar)))
    assertTrue(
      hasClasspathEntryName(configDTest, "/c/build/classes".replace('/', File.separatorChar)))
    assertTrue(
      hasClasspathEntryName(configDTest, "/d/build/classes".replace('/', File.separatorChar)))

    assertTrue(compileBloopProject("b", bloopDir).status.isOk)
    assertTrue(compileBloopProject("d", bloopDir).status.isOk)
  }

  // problem here is that to specify the test sourceset of project b depends on the test sourceset of project a using
  // testCompile project(':a').sourceSets.test.output
  // means that it points directly at the source set directory instead of the project + sourceset
  @Test def worksWithSourceSetDependencies(): Unit = {
    val buildSettings = testProjectDir.newFile("settings.gradle")
    val buildDirA = testProjectDir.newFolder("a")
    testProjectDir.newFolder("a", "src", "main", "scala")
    testProjectDir.newFolder("a", "src", "test", "scala")
    val buildDirB = testProjectDir.newFolder("b")
    testProjectDir.newFolder("b", "src", "main", "scala")
    testProjectDir.newFolder("b", "src", "test", "scala")
    val buildFileA = new File(buildDirA, "build.gradle")
    val buildFileB = new File(buildDirB, "build.gradle")

    writeBuildScript(
      buildFileA,
      s"""
         |plugins {
         |  id 'bloop'
         |}
         |
         |apply plugin: 'scala'
         |apply plugin: 'bloop'
         |
         |repositories {
         |  mavenCentral()
         |}
         |
         |dependencies {
         |  compile 'org.scala-lang:scala-library:2.12.6'
         |}
      """.stripMargin
    )

    writeBuildScript(
      buildFileB,
      s"""
         |plugins {
         |  id 'bloop'
         |}
         |
         |apply plugin: 'scala'
         |apply plugin: 'bloop'
         |
         |repositories {
         |  mavenCentral()
         |}
         |
         |dependencies {
         |  compile 'org.scala-lang:scala-library:2.12.6'
         |  testImplementation project(':a').sourceSets.test.output
         |}
      """.stripMargin
    )

    writeBuildScript(
      buildSettings,
      """
        |rootProject.name = 'scala-multi-projects'
        |include 'a'
        |include 'b'
      """.stripMargin
    )

    createHelloWorldScalaTestSource(buildDirA, "package x { trait A }")
    createHelloWorldScalaTestSource(buildDirB, "package y { trait B extends x.A { } }")

    GradleRunner
      .create()
      .withGradleVersion(gradleVersion)
      .withProjectDir(testProjectDir.getRoot)
      .withPluginClasspath(getClasspath.asJava)
      .withArguments("bloopInstall", "-Si")
      .build()

    val projectName = testProjectDir.getRoot.getName
    val bloopDir = new File(testProjectDir.getRoot, ".bloop")
    val bloopNone = new File(bloopDir, s"${projectName}.json")
    val bloopA = new File(bloopDir, "a.json")
    val bloopB = new File(bloopDir, "b.json")
    val bloopATest = new File(bloopDir, "a-test.json")
    val bloopBTest = new File(bloopDir, "b-test.json")

    assertFalse(bloopNone.exists())
    val configA = readValidBloopConfig(bloopA)
    val configB = readValidBloopConfig(bloopB)
    val configATest = readValidBloopConfig(bloopATest)
    val configBTest = readValidBloopConfig(bloopBTest)

    assertTrue(configA.project.dependencies.isEmpty)
    assertTrue(configB.project.dependencies.isEmpty)
    assertEquals(List("a"), configATest.project.dependencies.sorted)
    assertEquals(List("a-test", "b"), configBTest.project.dependencies.sorted)

    def hasClasspathEntryName(config: Config.File, entryName: String): Boolean =
      config.project.classpath.exists(_.toString.contains(entryName))

    assertFalse(hasClasspathEntryName(configB, "/a/build/classes".replace('/', File.separatorChar)))
    assertFalse(
      hasClasspathEntryName(configB, "/a-test/build/classes".replace('/', File.separatorChar)))
    assertFalse(
      hasClasspathEntryName(configBTest, "/a/build/classes".replace('/', File.separatorChar)))
    assertTrue(
      hasClasspathEntryName(configBTest, "/b/build/classes".replace('/', File.separatorChar)))
    assertTrue(
      hasClasspathEntryName(configBTest, "/a-test/build/classes".replace('/', File.separatorChar)))

    assertTrue(compileBloopProject("b", bloopDir).status.isOk)
  }

  // problem here is that to specify the test sourceset of project b depends on the test sourceset of project a using
  // additional configuration + artifacts
  @Test def worksWithConfigurationDependencies(): Unit = {
    val buildSettings = testProjectDir.newFile("settings.gradle")
    val buildDirA = testProjectDir.newFolder("a")
    testProjectDir.newFolder("a", "src", "main", "scala")
    testProjectDir.newFolder("a", "src", "test", "scala")
    val buildDirB = testProjectDir.newFolder("b")
    testProjectDir.newFolder("b", "src", "main", "scala")
    testProjectDir.newFolder("b", "src", "test", "scala")
    val buildFileA = new File(buildDirA, "build.gradle")
    val buildFileB = new File(buildDirB, "build.gradle")

    writeBuildScript(
      buildFileA,
      s"""
         |plugins {
         |  id 'bloop'
         |}
         |
         |apply plugin: 'scala'
         |apply plugin: 'bloop'
         |
         |repositories {
         |  mavenCentral()
         |}
         |
         |configurations {
         |  testArtifacts.extendsFrom testRuntime
         |}
         |
         |task testJar(type: Jar) {
         |  classifier = 'tests'
         |  from sourceSets.test.output
         |}
         |
         |artifacts {
         |  testArtifacts testJar
         |}
         |
         |dependencies {
         |  compile 'org.scala-lang:scala-library:2.12.6'
         |}
      """.stripMargin
    )

    writeBuildScript(
      buildFileB,
      s"""
         |plugins {
         |  id 'bloop'
         |}
         |
         |apply plugin: 'scala'
         |apply plugin: 'bloop'
         |
         |repositories {
         |  mavenCentral()
         |}
         |
         |dependencies {
         |  compile 'org.scala-lang:scala-library:2.12.6'
         |  testImplementation project( path: ':a', configuration: 'testArtifacts')
         |}
      """.stripMargin
    )

    writeBuildScript(
      buildSettings,
      """
        |rootProject.name = 'scala-multi-projects'
        |include 'a'
        |include 'b'
      """.stripMargin
    )

    createHelloWorldScalaTestSource(buildDirA, "package x { trait A }")
    createHelloWorldScalaTestSource(buildDirB, "package y { trait B extends x.A { } }")

    GradleRunner
      .create()
      .withGradleVersion(gradleVersion)
      .withProjectDir(testProjectDir.getRoot)
      .withPluginClasspath(getClasspath.asJava)
      .withArguments("bloopInstall", "-Si")
      .build()

    val projectName = testProjectDir.getRoot.getName
    val bloopDir = new File(testProjectDir.getRoot, ".bloop")
    val bloopNone = new File(bloopDir, s"${projectName}.json")
    val bloopA = new File(bloopDir, "a.json")
    val bloopB = new File(bloopDir, "b.json")
    val bloopATest = new File(bloopDir, "a-test.json")
    val bloopBTest = new File(bloopDir, "b-test.json")

    assertFalse(bloopNone.exists())
    val configA = readValidBloopConfig(bloopA)
    val configB = readValidBloopConfig(bloopB)
    val configATest = readValidBloopConfig(bloopATest)
    val configBTest = readValidBloopConfig(bloopBTest)

    assertTrue(configA.project.dependencies.isEmpty)
    assertTrue(configB.project.dependencies.isEmpty)
    assertEquals(List("a"), configATest.project.dependencies.sorted)
    assertEquals(List("a-test", "b"), configBTest.project.dependencies.sorted)

    def hasClasspathEntryName(config: Config.File, entryName: String): Boolean =
      config.project.classpath.exists(_.toString.contains(entryName))
    assertFalse(hasClasspathEntryName(configB, "/a/build/classes".replace('/', File.separatorChar)))
    assertFalse(
      hasClasspathEntryName(configB, "/a-test/build/classes".replace('/', File.separatorChar)))
    assertTrue(
      hasClasspathEntryName(configBTest, "/a-test/build/classes".replace('/', File.separatorChar)))
    assertTrue(
      hasClasspathEntryName(configBTest, "/b/build/classes".replace('/', File.separatorChar)))

    assertTrue(compileBloopProject("b-test", bloopDir).status.isOk)
  }

  @Test def encodingOptionGeneratedCorrectly(): Unit = {
    val buildFile = testProjectDir.newFile("build.gradle")
    testProjectDir.newFolder("src", "main", "scala")
    writeBuildScript(
      buildFile,
      s"""
         |plugins {
         |  id 'bloop'
         |}
         |
         |apply plugin: 'scala'
         |apply plugin: 'bloop'
         |
         |repositories {
         |  mavenCentral()
         |}
         |
         |dependencies {
         |  compile group: 'org.scala-lang', name: 'scala-library', version: '2.12.6'
         |}
         |
         |tasks.withType(ScalaCompile) {
         |	scalaCompileOptions.additionalParameters = ["-deprecation", "-unchecked", "-encoding", "utf8"]
         |}
         |
      """.stripMargin
    )

    createHelloWorldScalaSource(testProjectDir.getRoot)

    GradleRunner
      .create()
      .withGradleVersion(gradleVersion)
      .withProjectDir(testProjectDir.getRoot)
      .withPluginClasspath(getClasspath.asJava)
      .withArguments("bloopInstall", "-Si")
      .build()

    val projectName = testProjectDir.getRoot.getName
    val bloopFile = new File(new File(testProjectDir.getRoot, ".bloop"), projectName + ".json")

    val resultConfig = readValidBloopConfig(bloopFile)

    assertEquals(
      List("-deprecation", "-encoding", "utf8", "-unchecked"),
      resultConfig.project.`scala`.get.options)
  }

  @Test def flagsWithArgsGeneratedCorrectly(): Unit = {
    val buildFile = testProjectDir.newFile("build.gradle")
    testProjectDir.newFolder("src", "main", "scala")
    writeBuildScript(
      buildFile,
      s"""
         |plugins {
         |  id 'bloop'
         |}
         |
         |apply plugin: 'scala'
         |apply plugin: 'bloop'
         |
         |repositories {
         |  mavenCentral()
         |}
         |
         |dependencies {
         |  compile group: 'org.scala-lang', name: 'scala-library', version: '2.12.6'
         |}
         |
         |tasks.withType(ScalaCompile) {
         |	scalaCompileOptions.additionalParameters = [
         |    "-deprecation",
         |    "-Yjar-compression-level", "0",
         |    "-Ybackend-parallelism", "8",
         |    "-unchecked",
         |    "-encoding", "utf8"]
         |}
         |
      """.stripMargin
    )

    createHelloWorldScalaSource(testProjectDir.getRoot)

    GradleRunner
      .create()
      .withGradleVersion(gradleVersion)
      .withProjectDir(testProjectDir.getRoot)
      .withPluginClasspath(getClasspath.asJava)
      .withArguments("bloopInstall", "-Si")
      .build()

    val projectName = testProjectDir.getRoot.getName
    val bloopFile = new File(new File(testProjectDir.getRoot, ".bloop"), projectName + ".json")

    val resultConfig = readValidBloopConfig(bloopFile)

    assertEquals(
      List(
        "-Ybackend-parallelism",
        "8",
        "-Yjar-compression-level",
        "0",
        "-deprecation",
        "-encoding",
        "utf8",
        "-unchecked"),
      resultConfig.project.`scala`.get.options
    )
  }

  @Test def doesNotCreateEmptyProjects(): Unit = {
    val buildSettings = testProjectDir.newFile("settings.gradle")
    val buildDirA = testProjectDir.newFolder("a")
    val buildDirB = testProjectDir.newFolder("b")
    val buildFileA = new File(buildDirA, "build.gradle")
    val buildFileB = new File(buildDirB, "build.gradle")

    writeBuildScript(
      buildFileA,
      """
        |plugins {
        |  id 'bloop'
        |}
        |
        |apply plugin: 'java'
        |apply plugin: 'bloop'
        |
        |sourceSets.main {
        |  resources.srcDirs = ["$projectDir/resources"]
        |}
        |sourceSets.test {
        |  resources.srcDirs = ["$projectDir/testresources"]
        |}
        |
      """.stripMargin
    )

    writeBuildScript(
      buildFileB,
      """
        |plugins {
        |  id 'bloop'
        |}
        |
        |apply plugin: 'java'
        |apply plugin: 'bloop'
        |
        |repositories {
        |  mavenCentral()
        |}
        |
        |dependencies {
        |  compile 'org.scala-lang:scala-library:2.12.6'
        |  compile project(':a')
        |}
        |
      """.stripMargin
    )

    writeBuildScript(
      buildSettings,
      """
        |rootProject.name = 'scala-multi-projects'
        |include 'a'
        |include 'b'
      """.stripMargin
    )

    GradleRunner
      .create()
      .withGradleVersion(gradleVersion)
      .withProjectDir(testProjectDir.getRoot)
      .withPluginClasspath(getClasspath.asJava)
      .withArguments("bloopInstall", "-Si")
      .build()

    val projectName = testProjectDir.getRoot.getName
    val bloopDir = new File(testProjectDir.getRoot, ".bloop")
    val bloopNone = new File(bloopDir, s"${projectName}.json")
    val bloopA = new File(bloopDir, "a.json")
    val bloopB = new File(bloopDir, "b.json")
    val bloopATest = new File(bloopDir, "a-test.json")
    val bloopBTest = new File(bloopDir, "b-test.json")

    // projects shouldn't be created because they have no dependencies and all source/resources directories are empty
    assertFalse(bloopNone.exists())
    assertTrue(bloopA.exists())
    assertFalse(bloopATest.exists())
    assertTrue(bloopB.exists())
    assertFalse(bloopBTest.exists())
  }

  @Test def generateConfigFileForNonJavaNonScalaProjects(): Unit = {
    val buildFile = testProjectDir.newFile("build.gradle")
    writeBuildScript(
      buildFile,
      s"""
         |plugins {
         |  id 'bloop'
         |}
         |
         |apply plugin: 'bloop'
         |
         |repositories {
         |  mavenCentral()
         |}
         |
      """.stripMargin
    )

    createHelloWorldJavaSource()

    val result = GradleRunner
      .create()
      .withGradleVersion(gradleVersion)
      .withProjectDir(testProjectDir.getRoot)
      .withPluginClasspath(getClasspath.asJava)
      .withArguments("bloopInstall", "-Si")
      .build()

    assertTrue(
      result.getOutput.contains("Ignoring 'bloopInstall' on non-Scala and non-Java project"))
    val projectName = testProjectDir.getRoot.getName
    val bloopFile = new File(new File(testProjectDir.getRoot, ".bloop"), projectName + ".json")
    assertTrue(!bloopFile.exists())
  }

  @Test def generateConfigFileForNonJavaNonScalaProjectDependencies(): Unit = {
    val buildSettings = testProjectDir.newFile("settings.gradle")
    val buildDirA = testProjectDir.newFolder("a")
    val buildDirB = testProjectDir.newFolder("b")
    testProjectDir.newFolder("b", "src", "test", "scala")
    val buildFileA = new File(buildDirA, "build.gradle")
    val buildFileB = new File(buildDirB, "build.gradle")

    writeBuildScript(
      buildFileA,
      s"""
         |plugins {
         |  id 'bloop'
         |}
         |
         |apply plugin: 'bloop'
         |
         |repositories {
         |  mavenCentral()
         |}
         |configurations {
         |  foo
         |}
      """.stripMargin
    )

    writeBuildScript(
      buildFileB,
      s"""
         |plugins {
         |  id 'bloop'
         |}
         |
         |apply plugin: 'scala'
         |apply plugin: 'bloop'
         |
         |repositories {
         |  mavenCentral()
         |}
         |
         |dependencies {
         |  compile 'org.typelevel:cats-core_2.12:1.2.0'
         |  compile(project(path: ':a',  configuration: 'foo'))
         |}
      """.stripMargin
    )

    writeBuildScript(
      buildSettings,
      """
        |rootProject.name = 'scala-multi-projects-nonjava-dep'
        |include ':a'
        |include ':b'
      """.stripMargin
    )

    createHelloWorldScalaSource(buildDirB, "package y { trait B }")

    GradleRunner
      .create()
      .withGradleVersion(gradleVersion)
      .withProjectDir(testProjectDir.getRoot)
      .withPluginClasspath(getClasspath.asJava)
      .withArguments("bloopInstall", "-Si")
      .build()

    val projectName = testProjectDir.getRoot.getName
    val bloopDir = new File(testProjectDir.getRoot, ".bloop")
    val bloopNone = new File(bloopDir, s"$projectName.json")
    val bloopA = new File(bloopDir, "a.json")
    val bloopB = new File(bloopDir, "b.json")
    val bloopATest = new File(bloopDir, "a-test.json")
    val bloopBTest = new File(bloopDir, "b-test.json")

    assertFalse(bloopNone.exists())
    assertFalse(bloopA.exists())
    assertFalse(bloopATest.exists())
    val configB = readValidBloopConfig(bloopB)
    val configBTest = readValidBloopConfig(bloopBTest)
    assertTrue(configB.project.`scala`.exists(_.version == "2.12.6"))
    assertEquals(Nil, configB.project.dependencies)
    assertEquals(List("b"), configBTest.project.dependencies)

    def hasClasspathEntryName(config: Config.File, entryName: String): Boolean =
      config.project.classpath.exists(_.toString.contains(entryName))

    assertTrue(hasClasspathEntryName(configB, "scala-library"))
    assertTrue(hasClasspathEntryName(configBTest, "scala-library"))
    assertTrue(hasClasspathEntryName(configB, "cats-core"))
    assertTrue(hasClasspathEntryName(configBTest, "cats-core"))

    assertTrue(compileBloopProject("b", bloopDir).status.isOk)
  }

  @Test def generateConfigFileForJavaOnlyProjects(): Unit = {
    val buildFile = testProjectDir.newFile("build.gradle")
    writeBuildScript(
      buildFile,
      s"""
         |plugins {
         |  id 'bloop'
         |}
         |apply plugin: 'application'
         |apply plugin: 'java'
         |apply plugin: 'bloop'
         |
         |mainClassName = 'org.main.name'
         |
      """.stripMargin
    )

    createHelloWorldJavaSource()
    createHelloWorldJavaTestSource()

    GradleRunner
      .create()
      .withGradleVersion(gradleVersion)
      .withProjectDir(testProjectDir.getRoot)
      .withPluginClasspath(getClasspath.asJava)
      .withArguments("bloopInstall", "-Si")
      .build()

    val projectName = testProjectDir.getRoot.getName
    val bloopDir = new File(testProjectDir.getRoot, ".bloop")
    val projectFile = new File(bloopDir, s"${projectName}.json")
    val projectTestFile = new File(bloopDir, s"${projectName}-test.json")
    val projectConfig = readValidBloopConfig(projectFile)
    assertFalse(projectConfig.project.`scala`.isDefined)
    assertTrue(projectConfig.project.dependencies.isEmpty)
    assertTrue(projectConfig.project.classpath.isEmpty)

    val projectTestConfig = readValidBloopConfig(projectTestFile)
    assertFalse(projectConfig.project.`scala`.isDefined)
    assertTrue(projectTestConfig.project.dependencies == List(projectName))
    assertTrue(compileBloopProject(s"${projectName}-test", bloopDir).status.isOk)
  }

  @Test def maintainsClassPathOrder(): Unit = {
    val buildSettings = testProjectDir.newFile("settings.gradle")
    val buildDirA = testProjectDir.newFolder("a")
    val buildDirB = testProjectDir.newFolder("b")
    val buildDirC = testProjectDir.newFolder("c")
    val buildDirD = testProjectDir.newFolder("d")
    val buildFileA = new File(buildDirA, "build.gradle")
    val buildFileB = new File(buildDirB, "build.gradle")
    val buildFileC = new File(buildDirC, "build.gradle")
    val buildFileD = new File(buildDirD, "build.gradle")

    writeBuildScript(
      buildFileA,
      """
        |plugins {
        |  id 'bloop'
        |}
        |
        |apply plugin: 'java'
        |apply plugin: 'bloop'
      """.stripMargin
    )

    writeBuildScript(
      buildFileB,
      """
        |plugins {
        |  id 'bloop'
        |}
        |
        |apply plugin: 'java'
        |apply plugin: 'bloop'
      """.stripMargin
    )

    writeBuildScript(
      buildFileC,
      """
        |plugins {
        |  id 'bloop'
        |}
        |
        |apply plugin: 'java'
        |apply plugin: 'bloop'
        |
        |repositories {
        |  mavenCentral()
        |}
        |
        |dependencies {
        |  compile 'org.scala-lang:scala-library:2.12.6'
        |}
      """.stripMargin
    )

    writeBuildScript(
      buildFileD,
      """
        |plugins {
        |  id 'bloop'
        |}
        |
        |apply plugin: 'java'
        |apply plugin: 'bloop'
        |
        |repositories {
        |  mavenCentral()
        |}
        |
        |dependencies {
        |  compile project(':c')
        |  compile 'org.typelevel:cats-core_2.12:1.2.0'
        |  compile project(':a')
        |  compile project(':b')
        |}
      """.stripMargin
    )

    writeBuildScript(
      buildSettings,
      """
        |rootProject.name = 'scala-multi-projects'
        |include 'a'
        |include 'b'
        |include 'c'
        |include 'd'
      """.stripMargin
    )

    GradleRunner
      .create()
      .withGradleVersion(gradleVersion)
      .withProjectDir(testProjectDir.getRoot)
      .withPluginClasspath(getClasspath.asJava)
      .withArguments("bloopInstall", "-Si")
      .build()

    val bloopDir = new File(testProjectDir.getRoot, ".bloop")
    val bloopD = new File(bloopDir, "d.json")

    val configD = readValidBloopConfig(bloopD)
    assertEquals(List("c", "a", "b"), configD.project.dependencies)

    def idxOfClasspathEntryName(config: Config.File, entryName: String): Int =
      config.project.classpath.takeWhile(!_.toString.contains(entryName)).size

    val idxA = idxOfClasspathEntryName(configD, "/a/build/classes".replace('/', File.separatorChar))
    val idxB = idxOfClasspathEntryName(configD, "/b/build/classes".replace('/', File.separatorChar))
    val idxC = idxOfClasspathEntryName(configD, "/c/build/classes".replace('/', File.separatorChar))

    assertTrue(idxC < idxA)
    assertTrue(idxA < idxB)
    assertTrue(idxB < configD.project.classpath.size)
  }

  @Test def compilerPluginsGeneratedCorrectly(): Unit = {
    val buildFile = testProjectDir.newFile("build.gradle")
    testProjectDir.newFolder("src", "main", "scala")
    writeBuildScript(
      buildFile,
      """
        |plugins {
        |  id 'bloop'
        |}
        |
        |apply plugin: 'scala'
        |apply plugin: 'bloop'
        |
        |repositories {
        |  mavenCentral()
        |}
        |
        |configurations {
        |    scalaCompilerPlugin
        |}
        |
        |dependencies {
        |  compile group: 'org.scala-lang', name: 'scala-library', version: '2.12.6'
        |  scalaCompilerPlugin "org.scalameta:semanticdb-scalac_2.12.6:4.1.4"
        |}
        |
        |tasks.withType(ScalaCompile) {
        |	 scalaCompileOptions.additionalParameters = [
        |      "-Xplugin:" + configurations.scalaCompilerPlugin.asPath,
        |      "-Yrangepos",
        |      "-P:semanticdb:sourceroot:${rootProject.projectDir}"
        |    ]
        |}
        |
        """.stripMargin
    )

    createHelloWorldScalaSource(testProjectDir.getRoot)

    GradleRunner
      .create()
      .withGradleVersion(gradleVersion)
      .withProjectDir(testProjectDir.getRoot)
      .withPluginClasspath(getClasspath.asJava)
      .withArguments("bloopInstall", "-Si")
      .build()

    val projectName = testProjectDir.getRoot.getName
    val bloopFile = new File(new File(testProjectDir.getRoot, ".bloop"), projectName + ".json")

    val resultConfig = readValidBloopConfig(bloopFile)

    assertTrue(resultConfig.project.resolution.nonEmpty)
    assertTrue(
      resultConfig.project.resolution.get.modules.exists(p => p.name == "semanticdb-scalac_2.12.6"))

    assertTrue(
      resultConfig.project.`scala`.get.options
        .contains(s"-P:semanticdb:sourceroot:${testProjectDir.getRoot}"))
    assertTrue(resultConfig.project.`scala`.get.options.exists(p => p.startsWith("-Xplugin:")))
  }

  def loadBloopState(configDir: File): State = {
    val logger = BloopLogger.default(configDir.toString)
    assert(Files.exists(configDir.toPath), "Does not exist: " + configDir)
    val configDirectory = AbsolutePath(configDir)
    val loadedProjects = BuildLoader.loadSynchronously(configDirectory, logger)
    val build = Build(configDirectory, loadedProjects)
    State.forTests(build, TestUtil.getCompilerCache(logger), logger)
  }

  def compileBloopProject(projectName: String, bloopDir: File, verbose: Boolean = false): State = {
    val state0 = loadBloopState(bloopDir)
    val state = if (verbose) state0.copy(logger = state0.logger.asVerbose) else state0
    val action = Run(Commands.Compile(List(projectName)))
    TestUtil.blockingExecute(action, state0)
  }

  private def worksWithGivenScalaVersion(version: String): Unit = {
    val buildFile = testProjectDir.newFile("build.gradle")
    testProjectDir.newFolder("src", "main", "scala")
    testProjectDir.newFolder("src", "test", "scala")

    writeBuildScript(
      buildFile,
      s"""
         |plugins {
         |  id 'bloop'
         |}
         |
         |apply plugin: 'scala'
         |apply plugin: 'bloop'
         |
         |repositories {
         |  mavenCentral()
         |}
         |
         |dependencies {
         |  compile group: 'org.scala-lang', name: 'scala-library', version: "$version"
         |}
      """.stripMargin
    )

    createHelloWorldScalaSource(testProjectDir.getRoot)

    GradleRunner
      .create()
      .withGradleVersion(gradleVersion)
      .withProjectDir(testProjectDir.getRoot)
      .withPluginClasspath(getClasspath.asJava)
      .withArguments("bloopInstall", "-Si")
      .build()

    val projectName = testProjectDir.getRoot.getName
    val bloopDir = new File(testProjectDir.getRoot, ".bloop")
    val projectFile = new File(bloopDir, s"${projectName}.json")
    val projectTestFile = new File(bloopDir, s"${projectName}-test.json")
    val configFile = readValidBloopConfig(projectFile)
    val configTestFile = readValidBloopConfig(projectTestFile)

    assertTrue(configFile.project.`scala`.isDefined)
    assertEquals(version, configFile.project.`scala`.get.version)
    assertTrue(configFile.project.classpath.nonEmpty)
    assertTrue(configFile.project.dependencies.isEmpty)

    assertTrue(configTestFile.project.dependencies == List(projectName))
    assertTrue(compileBloopProject(s"${projectName}-test", bloopDir).status.isOk)
  }

  private def createHelloWorldJavaSource(): Unit = {
    val srcDir = testProjectDir.newFolder("src", "main", "java")
    val srcFile = new File(srcDir, "Hello.java")
    val src =
      """
        |public class Hello {
        |    public static void main(String[] args) {
        |        System.out.println("Hello World");
        |    }
        |}
      """.stripMargin
    Files.write(srcFile.toPath, src.getBytes(StandardCharsets.UTF_8))
    ()
  }

  private def createHelloWorldJavaTestSource(): Unit = {
    val srcDir = testProjectDir.newFolder("src", "test", "java")
    val srcFile = new File(srcDir, "HelloTest.java")
    val src =
      """
        |public class HelloTest {
        |    public static void main(String[] args) {
        |        System.out.println("Hello World test");
        |    }
        |}
      """.stripMargin
    Files.write(srcFile.toPath, src.getBytes(StandardCharsets.UTF_8))
    ()
  }

  private def createHelloWorldScalaTestSource(projectDir: File, source: String = ""): Unit = {
    val contents = if (source.isEmpty) HelloWorldSource else source
    val srcDir = projectDir.toPath.resolve("src").resolve("test").resolve("scala")
    Files.createDirectories(srcDir)
    val srcFile = srcDir.resolve("Source1.scala")
    Files.write(srcFile, contents.getBytes(StandardCharsets.UTF_8))
    ()
  }

  private final val HelloWorldSource: String = {
    """
      |object Hello {
      |  def main(args: Array[String]): Unit = {
      |    println("Hello")
      |  }
      |}
    """.stripMargin
  }

  private def createHelloWorldScalaSource(projectDir: File, source: String = ""): Unit = {
    val contents = if (source.isEmpty) HelloWorldSource else source
    val srcDir = projectDir.toPath.resolve("src").resolve("main").resolve("scala")
    Files.createDirectories(srcDir)
    val srcFile = srcDir.resolve("Source1.scala")
    Files.write(srcFile, contents.getBytes(StandardCharsets.UTF_8))
    ()
  }

  private def readValidBloopConfig(file: File): Config.File = {
    assertTrue("The bloop project file should exist", file.exists())
    parse(new String(Files.readAllBytes(file.toPath), StandardCharsets.UTF_8)) match {
      case Left(failure) =>
        throw new AssertionError(s"Failed to parse ${file.getAbsolutePath}: $failure")
      case Right(json) =>
        json.as[Config.File] match {
          case Left(failure) =>
            throw new AssertionError(s"Failed to decode ${file.getAbsolutePath}: $failure")
          case Right(result) =>
            result
        }
    }
  }

  private def getClasspath: List[File] = {
    classOf[BloopPlugin].getClassLoader
      .asInstanceOf[URLClassLoader]
      .getURLs
      .toList
      .map(url => new File(url.getFile))
  }

  private def writeBuildScript(buildFile: File, contents: String): Unit = {
    Files.write(buildFile.toPath, contents.getBytes(StandardCharsets.UTF_8))
    ()
  }
}
