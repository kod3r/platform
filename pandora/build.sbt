/*
 *  ____    ____    _____    ____    ___     ____ 
 * |  _ \  |  _ \  | ____|  / ___|  / _/    / ___|        Precog (R)
 * | |_) | | |_) | |  _|   | |     | |  /| | |  _         Advanced Analytics Engine for NoSQL Data
 * |  __/  |  _ <  | |___  | |___  |/ _| | | |_| |        Copyright (C) 2010 - 2013 SlamData, Inc.
 * |_|     |_| \_\ |_____|  \____|   /__/   \____|        All Rights Reserved.
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the 
 * GNU Affero General Public License as published by the Free Software Foundation, either version 
 * 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; 
 * without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See 
 * the GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License along with this 
 * program. If not, see <http://www.gnu.org/licenses/>.
 *
 */
import sbt._
import Keys._
import AssemblyKeys._
import java.io.File

name := "pandora"

version := "0.0.1-SNAPSHOT"

organization := "com.precog"

scalaVersion := "2.9.1"

scalacOptions ++= Seq("-deprecation", "-unchecked")

libraryDependencies ++= Seq(
  "org.sonatype.jline" % "jline" % "2.5",
  "org.specs2" %% "specs2" % "1.8-SNAPSHOT" % "test" changing(),
  "org.scala-tools.testing" %% "scalacheck" % "1.9")
  
outputStrategy := Some(StdoutOutput)

connectInput in run := true
  
fork in run := true

run <<= inputTask { argTask =>
  (javaOptions in run, fullClasspath in Compile, connectInput in run, outputStrategy, mainClass in run, argTask, extractData) map { (opts, cp, ci, os, mc, args, dataDir) =>
    val delim = java.io.File.pathSeparator
    val opts2 = opts ++
      Seq("-classpath", cp map { _.data } mkString delim) ++
      Seq(mc getOrElse "com.precog.pandora.Console") ++
      (if (args.isEmpty) Seq(dataDir) else args)
    Fork.java.fork(None, opts2, None, Map(), ci, os getOrElse StdoutOutput).exitValue()
    jline.Terminal.getTerminal.initializeTerminal()
  }
}

extractData <<= streams map { s =>
  val target = new File("/tmp/pandora/data/")
  if (!target.exists()) {
    s.log.info("Extracting LevelDB sample data...")
    IO.copyDirectory(new File("pandora/dist/data/"), target, true, false)
  }
  target.getCanonicalPath
}

test <<= (streams, fullClasspath in Test, outputStrategy in Test, extractData) map { (s, cp, os, dataDir) =>
  val delim = java.io.File.pathSeparator
  val cpStr = cp map { _.data } mkString delim
  s.log.debug("Running with classpath: " + cpStr)
  val opts2 =
    Seq("-classpath", cpStr) ++
    Seq("-Dprecog.storage.root=" + dataDir) ++
    Seq("specs2.run") ++
    Seq("com.precog.pandora.PlatformSpecs")
  val result = Fork.java.fork(None, opts2, None, Map(), false, os getOrElse StdoutOutput).exitValue()
  if (result != 0) error("Tests unsuccessful")    // currently has no effect (https://github.com/etorreborre/specs2/issues/55)
}

(console in Compile) <<= (streams, initialCommands in console, fullClasspath in Compile, scalaInstance) map { (s, init, cp, si) =>
  IO.withTemporaryFile("pandora", ".scala") { file =>
    IO.write(file, init)
    val delim = java.io.File.pathSeparator
    val scalaCp = (si.compilerJar +: si.extraJars) map { _.getCanonicalPath }
    val fullCp = (cp map { _.data }) ++ scalaCp
    val cpStr = fullCp mkString delim
    s.log.debug("Running with classpath: " + cpStr)
    val opts2 =
      Seq("-classpath", cpStr) ++
      Seq("-Dscala.usejavacp=true") ++
      Seq("scala.tools.nsc.MainGenericRunner") ++
      Seq("-Yrepl-sync", "-i", file.getCanonicalPath)
    Fork.java.fork(None, opts2, None, Map(), true, StdoutOutput).exitValue()
    jline.Terminal.getTerminal.initializeTerminal()
  }
} dependsOn extractData

initialCommands in console := """
  | import edu.uwm.cs.gll.LineStream
  | 
  | import com.precog._
  |
  | import daze._
  | import daze.util._
  | 
  | import pandora._
  | 
  | import quirrel._
  | import quirrel.emitter._
  | import quirrel.parser._
  | import quirrel.typer._
  | 
  | import yggdrasil.{SValue, YggConfig}
  | 
  | val platform = new Compiler with LineErrors with ProvenanceChecker with Emitter with Evaluator with DatasetConsumers with YggdrasilOperationsAPI with YggdrasilStorage with AkkaIngestServer {
  |   import akka.dispatch.Await
  |   import akka.util.Duration
  |
  |   import java.io.File
  |
  |   import scalaz.effect.IO
  |   
  |   import org.streum.configrity.Configuration
  |   import org.streum.configrity.io.BlockFormat
  | 
  |   val controlTimeout = Duration(120, "seconds")
  |
  |   lazy val yggConfig = loadConfig(Some("/tmp/pandora/data"))
  |
  |   private def loadConfig(dataDir: Option[String]): IO[YggConfig] = {
  |     val rawConfig = dataDir map {
  |       "precog.storage.root = " + _
  |     } getOrElse { "" }
  |
  |     IO { 
  |       new YggConfig {
  |         def config = Configuration.parse(rawConfig)  
  |       }
  |     }
  |   }
  |
  |   def eval(str: String): Set[SValue] = evalE(str) map { _._2 }
  | 
  |   def evalE(str: String) = {
  |     val Right(dag) = decorate(emit(compile(str)))
  |     consumeEval(dag)
  |   }
  | 
  |   def startup() {
  |     // start storage shard 
  |     Await.result(storage.start, controlTimeout)
  |   }
  |   
  |   def shutdown() {
  |     // stop storage shard
  |     Await.result(storage.stop, controlTimeout)
  |     
  |     actorSystem.shutdown()
  |   }
  | }""".stripMargin
  
logBuffered := false       // gives us incremental output from Specs2

mainClass := Some("com.precog.pandora.Console")

dist <<= (version, streams, baseDirectory, target in assembly, jarName in assembly) map { 
         (projectVersion: String, streams: TaskStreams, projectRoot: File, buildTarget: File, assemblyName: String) => {
  val log = streams.log
  val distStaticRoot = new File(projectRoot, "dist")
  val distName = "pandist-%s".format(projectVersion)
  val distTmp = new File(buildTarget, distName)
  val distTarball = new File(buildTarget, distName + ".tar.gz")
  val assemblyJar = new File(buildTarget, assemblyName)
  val distTmpLib = new File(distTmp, "lib/") 
  log.info("copy static dist contents")
  List("cp", "-r", distStaticRoot.toString, distTmp.toString) ! log
  log.info("copy assembly jar: %s".format(assemblyName)) 
  distTmpLib.mkdirs
  List("cp", assemblyJar.toString, distTmpLib.toString) ! log
  log.info("create tarball")
  List("tar", "-C", distTmp.getParent.toString, "-cvzf", distTarball.toString, distTmp.getName) ! log
}}

dist <<= dist.dependsOn(assembly)
