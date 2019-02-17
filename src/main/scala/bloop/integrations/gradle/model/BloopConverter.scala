package bloop.integrations.gradle.model

import java.io.File
import java.nio.file.Path

import bloop.config.Config
import bloop.config.Config.{JvmConfig, Platform}
import bloop.integrations.gradle.BloopParameters
import bloop.integrations.gradle.model.BloopConverter.SourceSetDep
import bloop.integrations.gradle.syntax._
import org.gradle.api.{GradleException, Project}
import org.gradle.api.artifacts._
import org.gradle.api.internal.tasks.compile.{DefaultJavaCompileSpec, JavaCompilerArgumentsBuilder}
import org.gradle.api.plugins.{ApplicationPluginConvention, JavaPluginConvention}
import org.gradle.api.tasks.SourceSet
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.api.tasks.scala.{ScalaCompile, ScalaCompileOptions}
import org.gradle.plugins.ide.internal.tooling.java.DefaultInstalledJdk

import scala.collection.JavaConverters._
import scala.util.{Failure, Success, Try}

/**
 * Define the conversion from Gradle's project model to Bloop's project model.
 * @param parameters Parameters provided by Gradle's user configuration
 */
final class BloopConverter(parameters: BloopParameters) {

  /**
   * Converts a project's given source set to a Bloop project
   *
   * Bloop analysis output will be targetDir/project-name/project[-sourceSet].bin
   *
   * Output classes are generated to projectDir/build/classes/scala/sourceSetName to
   * be compatible with Gradle.
   *
   * NOTE: Java classes will be also put into the above defined directory, not as with Gradle
   *
   * @param strictProjectDependencies Additional dependencies that cannot be inferred from Gradle's object model
   * @param project The Gradle project model
   * @param sourceSet The source set to convert
   * @param targetDir Target directory for bloop files
   * @return Bloop configuration
   */
  def toBloopConfig(
      strictProjectDependencies: List[SourceSetDep],
      project: Project,
      sourceSet: SourceSet,
      targetDir: File
  ): Try[Config.File] = {
    val compileClassPathConfiguration = project.getConfiguration(sourceSet.getCompileClasspathConfigurationName)

    // get runtime classpath.  Config name has changed over Gradle versions so check both
    val runtimeClassPathConfiguration = {
      val runtimeConfig = project.getConfiguration(sourceSet.getRuntimeConfigurationName)
      val runtimeClassPathConfig = project.getConfiguration(sourceSet.getRuntimeConfigurationName + "Classpath")
      if (runtimeClassPathConfig != null)
        runtimeClassPathConfig
      else
        runtimeConfig
    }

    // retrieve direct project dependencies.
    // Bloop doesn't require transitive project dependencies
    val compileProjectDependencies = getProjectDependencies(compileClassPathConfiguration)
    val runtimeProjectDependencies = getProjectDependencies(runtimeClassPathConfiguration)

    // Strict project dependencies should have more priority than regular project dependencies
    val allDependencies = (strictProjectDependencies.map(_.bloopModuleName) ++
      compileProjectDependencies ++ runtimeProjectDependencies).distinct

    // retrieve project dependencies recursively to include transitive project dependencies
    // Bloop requires this for the classpath
    val allCompileProjectDependencies = getProjectDependenciesRecursively(compileClassPathConfiguration)
    val allRuntimeProjectDependencies = getProjectDependenciesRecursively(runtimeClassPathConfiguration)

    // retrieve all artifacts
    val compileArtifacts: List[ResolvedArtifact] =
      compileClassPathConfiguration.getResolvedConfiguration.getResolvedArtifacts.asScala.toList
    val runtimeArtifacts: List[ResolvedArtifact] =
      runtimeClassPathConfiguration.getResolvedConfiguration.getResolvedArtifacts.asScala.toList

    // convert artifacts to class dirs for projects and file paths for non-projects
    val compileClasspathItems = compileArtifacts.map(resolvedArtifact => convertToPath(allCompileProjectDependencies, resolvedArtifact))
    val runtimeClasspathItems = runtimeArtifacts.map(resolvedArtifact => convertToPath(allRuntimeProjectDependencies, resolvedArtifact))

    // get non-project artifacts for resolution
    val compileNonProjectDependencies: List[ResolvedArtifact] = compileArtifacts
      .filter(resolvedArtifact => !isProjectDependency(allCompileProjectDependencies, resolvedArtifact))
    val runtimeNonProjectDependencies: List[ResolvedArtifact] = runtimeArtifacts
      .filter(resolvedArtifact => !isProjectDependency(allRuntimeProjectDependencies, resolvedArtifact))
    val nonProjectDependencies = (compileNonProjectDependencies ++ runtimeNonProjectDependencies).distinct

    val resources = getResources(sourceSet)
    val sources = getSources(sourceSet).filterNot(resources.contains)

    val isTestSourceSet = sourceSet.getName == SourceSet.TEST_SOURCE_SET_NAME

    // Gradle always creates a main and test source set regardless of whether they are needed.
    // ignore test sourceset if there are no sources or resources
    if (isTestSourceSet &&
      !sources.exists(_.toFile.exists()) &&
      !resources.exists(_.toFile.exists())) {
      Failure(new GradleException("Test project has no source so ignore it"))
    } else {
      /* The classes directory is independent from Gradle's because Gradle has a different classes
       * directory for Scala and Java projects, whereas Bloop doesn't (it inherited this design from
       * sbt). Therefore, to avoid any compilation/test/run issue between Gradle and Bloop, we just
       * use our own classes 'bloop' directory in the build directory. */
      val classesDir = getClassesDir(project, sourceSet)

      // tag runtime items to the end of the classpath until Bloop has separate compile and runtime paths
      val classpath: List[Path] =
        (strictProjectDependencies.map(_.classesDir) ++ compileClasspathItems ++ runtimeClasspathItems).distinct

      for {
        scalaConfig <- getScalaConfig(project, sourceSet, compileArtifacts)
        resolution = Config.Resolution(nonProjectDependencies.map(artifactToConfigModule))
        bloopProject = Config.Project(
          name = getProjectName(project, sourceSet),
          directory = project.getProjectDir.toPath,
          sources = sources,
          dependencies = allDependencies,
          classpath = classpath,
          out = project.getBuildDir.toPath,
          classesDir = classesDir,
          resources = if (resources.isEmpty) None else Some(resources),
          `scala` = scalaConfig,
          java = getJavaConfig(project, sourceSet),
          sbt = None,
          test = getTestConfig(sourceSet),
          platform = getPlatform(project, isTestSourceSet),
          resolution = Some(resolution)
        )
      } yield Config.File(Config.File.LatestVersion, bloopProject)
    }
  }

  def getPlatform(project: Project, isTestSourceSet: Boolean): Option[Platform] = {
    val currentJDK = DefaultInstalledJdk.current()
    val jdkPath = Option(currentJDK).map(_.getJavaHome.toPath)
    project.getConvention.findPlugin(classOf[ApplicationPluginConvention]) match {
      case appPluginConvention: ApplicationPluginConvention if !isTestSourceSet =>
        val mainClass = Option(appPluginConvention.getMainClassName)
        val options = appPluginConvention.getApplicationDefaultJvmArgs.asScala.toList
        Some(Platform.Jvm(JvmConfig(jdkPath, options), mainClass))
      case _ =>
        Some(Platform.Jvm(JvmConfig(jdkPath, Nil), None))
    }
  }

  def getTestConfig(sourceSet: SourceSet): Option[Config.Test] = {
    if (sourceSet.getName == SourceSet.TEST_SOURCE_SET_NAME) {
      // TODO: make this configurable?
      Some(Config.Test.defaultConfiguration)
    } else {
      None
    }
  }

  def getProjectName(project: Project, sourceSet: SourceSet): String = {
    if (sourceSet.getName == SourceSet.MAIN_SOURCE_SET_NAME) {
      project.getName
    } else {
      s"${project.getName}-${sourceSet.getName}"
    }
  }

  // Gradle has removed the getConfiguration method and replaced it with getTargetConfiguration around version 4.0
  private def getTargetConfiguration(projectDependency: ProjectDependency): String = {
    try {
      val getTargetConfiguration = classOf[ProjectDependency].getMethod("getTargetConfiguration")
      val targetConfigName = getTargetConfiguration.invoke(projectDependency)
      if (targetConfigName == null)
        Dependency.DEFAULT_CONFIGURATION
      else
        targetConfigName.asInstanceOf[String]
    } catch {
      case _ : NoSuchMethodException => projectDependency.getConfiguration;
    }
  }

  private def getProjectDependencies(configuration: Configuration): List[String] = {
    // We cannot turn this into a set directly because we need the topological order for correctness
    configuration.getAllDependencies.asScala.collect {
      case projectDependency: ProjectDependency if projectDependency.getDependencyProject.getConvention.findPlugin(classOf[JavaPluginConvention]) != null =>
        dependencyToProjectName(projectDependency)
    }.toList
  }

  private def getProjectDependenciesRecursively(configuration: Configuration): List[ProjectDependency] = {
    // We cannot turn this into a set directly because we need the topological order for correctness
      configuration.getAllDependencies.asScala.collect {
        case projectDependency: ProjectDependency if projectDependency.getDependencyProject.getConvention.findPlugin(classOf[JavaPluginConvention]) != null =>
          val depProject = projectDependency.getDependencyProject
          val depConfigName = getTargetConfiguration(projectDependency)
          val depConfig = depProject.getConfigurations.getByName(depConfigName)
          projectDependency +: getProjectDependenciesRecursively(depConfig)
      }.toList.flatten.distinct
  }

  def getClassesDir(project: Project, sourceSet: SourceSet): Path =
    (project.getBuildDir / "classes" / "bloop" / sourceSet.getName).toPath

  private def getSources(sourceSet: SourceSet): List[Path] =
    sourceSet.getAllSource.getSrcDirs.asScala.map(_.toPath).toList

  private def getResources(sourceSet: SourceSet): List[Path] =
    sourceSet.getResources.getSrcDirs.asScala.map(_.toPath).toList

  private def dependencyToProjectName(projectDependency: ProjectDependency): String = {
    val depProject = projectDependency.getDependencyProject
    getProjectName(depProject, depProject.getSourceSet(SourceSet.MAIN_SOURCE_SET_NAME))
  }

  private def dependencyToClassPath(projectDependency: ProjectDependency): Path = {
    val depProject = projectDependency.getDependencyProject
    getClassesDir(depProject, depProject.getSourceSet(SourceSet.MAIN_SOURCE_SET_NAME))
  }

  private def convertToPath(
      projectDependencies: List[ProjectDependency],
      resolvedArtifact: ResolvedArtifact): Path = {
    projectDependencies.find(dep => isProjectDependency(dep, resolvedArtifact))
      .map(dependencyToClassPath)
      .getOrElse(resolvedArtifact.getFile.toPath)
  }

  private def isProjectDependency(
      dependency: ProjectDependency,
      resolvedArtifact: ResolvedArtifact): Boolean = {
    dependency.getGroup == resolvedArtifact.getModuleVersion.getId.getGroup &&
    dependency.getName == resolvedArtifact.getModuleVersion.getId.getName &&
    dependency.getVersion == resolvedArtifact.getModuleVersion.getId.getVersion
  }

  private def isProjectDependency(
      projectDependencies: List[ProjectDependency],
      resolvedArtifact: ResolvedArtifact
  ): Boolean = {
    projectDependencies.exists(dep => isProjectDependency(dep, resolvedArtifact))
  }

  private def artifactToConfigModule(artifact: ResolvedArtifact): Config.Module = {
    Config.Module(
      organization = artifact.getModuleVersion.getId.getGroup,
      name = artifact.getName,
      version = artifact.getModuleVersion.getId.getVersion,
      configurations = None,
      List(
        Config.Artifact(
          name = artifact.getModuleVersion.getId.getName,
          classifier = Option(artifact.getClassifier),
          checksum = None,
          path = artifact.getFile.toPath
        )
      )
    )
  }

  private def getScalaConfig(
      project: Project,
      sourceSet: SourceSet,
      artifacts: List[ResolvedArtifact]
  ): Try[Option[Config.Scala]] = {
    def isJavaOnly: Boolean = {
      val allSourceFiles = sourceSet.getAllSource.getFiles.asScala.toList
      !allSourceFiles.filter(f => f.exists && f.isFile).exists(_.getName.endsWith(".scala"))
    }

    // Finding the compiler group and version from the standard Scala library added as dependency
    artifacts.find(_.getName == parameters.stdLibName) match {
      case Some(stdLibArtifact) =>
        val scalaVersion = stdLibArtifact.getModuleVersion.getId.getVersion
        val scalaOrg = stdLibArtifact.getModuleVersion.getId.getGroup
        val scalaCompileTaskName = sourceSet.getCompileTaskName("scala")
        val scalaCompileTask = project.getTask[ScalaCompile](scalaCompileTaskName)

        if (scalaCompileTask != null) {
          val scalaJars = scalaCompileTask.getScalaClasspath.asScala.map(_.toPath).toList
          val opts = scalaCompileTask.getScalaCompileOptions
          val options = optionList(opts)
          val compilerName = parameters.compilerName

          // Use the compile setup and analysis out defaults, Gradle doesn't expose its customization
          Success(
            Some(
              Config.Scala(scalaOrg, compilerName, scalaVersion, options, scalaJars, None, None)
            )
          )
        } else {
          if (isJavaOnly) Success(None)
          else {
            // This is a heavy error on Gradle's side, but we will only report it in Scala projects
            Failure(
              new GradleException(s"$scalaCompileTaskName task is missing from ${project.getName}")
            )
          }
        }

      case None if isJavaOnly => Success(None)
      case None =>
        val target = s"project ${project.getName}/${sourceSet.getName}"
        val artifactNames =
          if (artifacts.isEmpty) ""
          else s" Found artifacts:\n${artifacts.map(_.getFile.toString).mkString("\n")}"
        Failure(
          new GradleException(
            s"Expected Scala standard library in classpath of $target that defines Scala sources.$artifactNames"
          )
        )
    }
  }

  private def getJavaConfig(project: Project, sourceSet: SourceSet): Option[Config.Java] = {
    val javaCompileTaskName = sourceSet.getCompileTaskName("java")
    val javaCompileTask = project.getTask[JavaCompile](javaCompileTaskName)
    val opts = javaCompileTask.getOptions

    val specs = new DefaultJavaCompileSpec()
    specs.setCompileOptions(opts)

    val builder = new JavaCompilerArgumentsBuilder(specs)
      .includeMainOptions(true)
      .includeClasspath(false)
      .includeSourceFiles(false)
      .includeLauncherOptions(false)

    var args = builder.build().asScala.toList.filter(_.nonEmpty)

    if (!args.contains("-source")) {
      if (specs.getSourceCompatibility != null) {
        args = "-source" :: specs.getSourceCompatibility :: args
      } else {
        Option(DefaultInstalledJdk.current())
          .foreach(jvm => args = "-source" :: jvm.getJavaVersion.toString :: args)
      }
    }

    if (!args.contains("-target")) {
      if (specs.getTargetCompatibility != null) {
        args = "-target" :: specs.getTargetCompatibility :: args
      } else {
        Option(DefaultInstalledJdk.current())
          .foreach(jvm => args = "-target" :: jvm.getJavaVersion.toString :: args)
      }
    }

    // Always return a java configuration (this cannot hurt us)
    Some(Config.Java(args))
  }

  private def ifEnabled[T](option: Boolean)(value: T): Option[T] =
    if (option) Some(value) else None

  private def optionList(options: ScalaCompileOptions): List[String] = {
    // based on ZincScalaCompilerArgumentsGenerator
    val baseOptions: Set[String] = Seq(
      ifEnabled(options.isDeprecation)("-deprecation"),
      ifEnabled(options.isUnchecked)("-unchecked"),
      ifEnabled(options.isOptimize)("-optimize"),
      ifEnabled(options.getDebugLevel == "verbose")("-verbose"),
      ifEnabled(options.getDebugLevel == "debug")("-Ydebug"),
      Option(options.getEncoding).map(encoding => s"-encoding $encoding"),
      Option(options.getDebugLevel).map(level => s"-g:$level")
    ).flatten.toSet

    val loggingPhases: Set[String] =
      Option(options.getLoggingPhases)
        .map(_.asScala.toSet)
        .getOrElse(Set.empty)
        .map(phase => s"-Ylog:$phase")

    val additionalOptions: Set[String] = {
      val opts = options.getAdditionalParameters
      if (opts == null) Set.empty
      else fuseOptionsWithArguments(opts.asScala.toList).toSet
    }

    // Sort compiler flags to get a deterministic order when extracting the project
    splitFlags(baseOptions.union(loggingPhases).union(additionalOptions).toList.sorted)
  }

  private final val argumentSpaceSeparator = '\u0000'
  private final val argumentSpace = argumentSpaceSeparator.toString
  private def fuseOptionsWithArguments(scalacOptions: List[String]): List[String] = {
    scalacOptions match {
      case scalacOption :: rest =>
        val (args, remaining) = nextArgsAndRemaining(rest)
        val fused = (scalacOption :: args).mkString(argumentSpace)
        fused :: fuseOptionsWithArguments(remaining)
      case Nil => Nil
    }
  }

  private def nextArgsAndRemaining(scalacOptions: List[String]): (List[String], List[String]) = {
    scalacOptions match {
      case arg :: rest if !arg.startsWith("-") =>
        val (args, flags) = nextArgsAndRemaining(rest)
        (arg :: args, flags)
      // If next option starts with '-', then no scalac option is left to process
      case _ => (Nil, scalacOptions)
    }
  }

  private def splitFlags(values: List[String]): List[String] = {
    values.flatMap(value => value.split(argumentSpaceSeparator))
  }
}

object BloopConverter {
  case class SourceSetDep(bloopModuleName: String, classesDir: Path)
}
