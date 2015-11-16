/* Copyright 2015 Felix Becker

   Licensed under the Apache License, Version 2.0 (the "License");
   you may not use this file except in compliance with the License.
   You may obtain a copy of the License at

   http://www.apache.org/licenses/LICENSE-2.0

   Unless required by applicable law or agreed to in writing, software
   distributed under the License is distributed on an "AS IS" BASIS,
   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
   See the License for the specific language governing permissions and
   limitations under the License. */

package name.felixbecker.jarlotte

import java.io.{File, FileWriter}
import java.nio.file.Paths
import java.util.Properties

import net.lingala.zip4j.core.ZipFile
import net.lingala.zip4j.model.ZipParameters
import org.apache.maven.plugin.MojoFailureException
import org.eclipse.aether.artifact.DefaultArtifact
import org.eclipse.aether.collection.CollectRequest
import org.eclipse.aether.graph.Dependency
import org.eclipse.aether.resolution.{ArtifactRequest, ArtifactResult, DependencyRequest}
import org.eclipse.aether.util.artifact.JavaScopes
import org.eclipse.aether.util.filter.DependencyFilterUtils

class JarPackagingMojoExecutor(mojo: JarPackagingMojo) {

  def execute(): Unit = {

    val targetDir = Paths.get(mojo.getProject.getBuild.getDirectory).toFile
    val projectFinalName: String = mojo.getProject.getBuild.getFinalName
    val buildDir = Paths.get(mojo.getProject.getBuild.getDirectory, projectFinalName).toFile
    val jarlotteZipFileName = s"$projectFinalName-jarlotte.jar"

    if(!buildDir.exists()){
      throw new MojoFailureException(s"Couldn't find target directory $targetDir. Please make sure that the maven war plugin ran in this phase before (order in xml matters, see effective pom in doubt)!")
    }

    /* Step 1: Add WAR-Content to jar */
    val zipFile = new ZipFile(Paths.get(targetDir.getAbsolutePath, jarlotteZipFileName).toFile)
    val zipParameters = new ZipParameters
    zipFile.addFolder(buildDir, zipParameters)

    /* Step 2: Add Jarlotte stuff */

    val ownVersion = mojo.getPluginDescriptor.getVersion
    val loaderJarResolutionResult = resolveArtifact(s"name.felixbecker:jarlotte-loader:$ownVersion")
    mojo.getLog.info(s"Loader jar resolution ${loaderJarResolutionResult.getArtifact.getFile}")


    val loaderZipFileExtractionDir = new File(mojo.getProject.getBuild.getDirectory, "jarlotte-loader-extracted")
    val loaderZipFile = new ZipFile(loaderJarResolutionResult.getArtifact.getFile)
    mojo.getLog.info(s"Extracting ${loaderZipFile.getFile} to $loaderZipFileExtractionDir")
    loaderZipFile.extractAll(loaderZipFileExtractionDir.getAbsolutePath)

    zipFile.addFolder(Paths.get(loaderZipFileExtractionDir.getAbsolutePath, "name").toFile, zipParameters)
    val metaInfZipParameters = new ZipParameters
    metaInfZipParameters.setRootFolderInZip("META-INF")

    val manifestFile = Paths.get(loaderZipFileExtractionDir.getAbsolutePath, "MANIFEST.MF").toFile



    /* add property file for custom attributes */
    val jarlotteProperties = new Properties()
    val jarlottePropertiesFile = Paths.get(loaderZipFileExtractionDir.getAbsolutePath, "jarlotte.properties").toFile
    jarlotteProperties.put("Webapp-Dir-Name", projectFinalName)
    jarlotteProperties.put("Initializer-Class", mojo.getInitializerClass)
    jarlotteProperties.store(new FileWriter(jarlottePropertiesFile), "Generated by the Jarlotte Mojo")

    zipFile.addFile(manifestFile, metaInfZipParameters)
    zipFile.addFile(jarlottePropertiesFile, metaInfZipParameters)


    mojo.getLog.info(s"target directory is ${mojo.getProject.getBuild.getDirectory} - $projectFinalName")

    /* Add Initializer Classes to the jarlotte-lib folder */

    val i = mojo.getInitializerArtifact
    val initializerArtifacts = resolveArtifacts(s"${i.getGroupId}:${i.getArtifactId}:${i.getVersion}")
    val jarlotteLibZipParameters = new ZipParameters
    jarlotteLibZipParameters.setRootFolderInZip("jarlotte-lib")

    initializerArtifacts.foreach { af =>
      val file = af.getArtifact.getFile
      zipFile.addFile(file, jarlotteLibZipParameters)
      mojo.getLog.info(s"Adding $file to $jarlotteZipFileName")
    }

    /* attach jarlotte artifact */
    mojo.getProjectHelper.attachArtifact(mojo.getProject, "jar", "jarlotte", zipFile.getFile)
  }

  def resolveArtifact(artifactCoords: String): ArtifactResult = {

    val artifact = new DefaultArtifact(artifactCoords)
    val artifactRequest = new ArtifactRequest()
    artifactRequest.setArtifact(artifact)
    artifactRequest.setRepositories(mojo.getProjectRepos)

    mojo.getRepoSystem.resolveArtifact(mojo.getRepoSession, artifactRequest)

  }

  def resolveArtifacts(artifactCoords: String): List[ArtifactResult] = {

    import scala.collection.JavaConversions._

    val artifact = new DefaultArtifact(artifactCoords)

    val classpathFlter = DependencyFilterUtils.classpathFilter(JavaScopes.COMPILE) // ??? from example TODO - maybe thats the reason why i don't find my initializer

    val collectRequest = new CollectRequest
    collectRequest.setRoot(new Dependency(artifact, JavaScopes.COMPILE))
    collectRequest.setRepositories(mojo.getProjectRepos)

    val dependencyRequest = new DependencyRequest(collectRequest, classpathFlter)
    val artifactResults = mojo.getRepoSystem.resolveDependencies(mojo.getRepoSession, dependencyRequest).getArtifactResults

    /*
    artifactResults.foreach { artifactResult =>
      mojo.getLog.info("Initializer dependencies are: " + artifactResult.getArtifact + " resolved to " + artifactResult.getArtifact.getFile)
    }

    mojo.getProject.getArtifacts.foreach { projectArtifact =>
      println(s"Jar has to contain artifact ${projectArtifact.getFile}")
    }

    mojo.getProject.getResources.foreach { resource =>
      println(s"Jar has to contain resource ${resource}")
    }
    */

    artifactResults.toList
  }
}
