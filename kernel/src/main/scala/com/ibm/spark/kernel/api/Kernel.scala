/*
 * Copyright 2015 IBM Corp.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.ibm.spark.kernel.api

import java.io.{OutputStream, InputStream, PrintStream}
import java.util.concurrent.ConcurrentHashMap

import com.ibm.spark.annotations.Experimental
import com.ibm.spark.boot.layer.InterpreterManager
import com.ibm.spark.comm.CommManager
import com.ibm.spark.global
import com.ibm.spark.interpreter.Results.Result
import com.ibm.spark.interpreter._
import com.ibm.spark.kernel.protocol.v5
import com.ibm.spark.kernel.protocol.v5.{KMBuilder, KernelMessage}
import com.ibm.spark.kernel.protocol.v5.kernel.ActorLoader
import com.ibm.spark.kernel.protocol.v5.magic.MagicParser
import com.ibm.spark.kernel.protocol.v5.stream.{KernelOutputStream, KernelInputStream}
import com.ibm.spark.magic.{MagicLoader, MagicExecutor}
import com.ibm.spark.utils.{KeyValuePairUtils, LogLike}
import com.typesafe.config.Config
import org.apache.spark.api.java.JavaSparkContext
import org.apache.spark.sql.SQLContext
import org.apache.spark.{SparkContext, SparkConf}
import scala.util.{Try, DynamicVariable}

import scala.reflect.runtime.universe._

import scala.language.dynamics
import com.ibm.spark.global.ExecuteRequestState

/**
 * Represents the main kernel API to be used for interaction.
 *
 * @param config The configuration used when starting the kernel
 * @param interpreterManager The interpreter manager to expose in this instance
 * @param comm The Comm manager to expose in this instance
 * @param actorLoader The actor loader to use for message relaying
 */
@Experimental
class Kernel (
  private val config: Config,
  private val actorLoader: ActorLoader,
  val interpreterManager: InterpreterManager,
  val comm: CommManager,
  val magicLoader: MagicLoader
) extends KernelLike with LogLike {
  /**
   * Represents the current input stream used by the kernel for the specific
   * thread.
   */
  private val currentInputStream =
    new DynamicVariable[InputStream](null)
  private val currentInputKernelMessage =
    new DynamicVariable[KernelMessage](null)

  /**
   * Represents the current output stream used by the kernel for the specific
   * thread.
   */
  private val currentOutputStream =
    new DynamicVariable[PrintStream](null)
  private val currentOutputKernelMessage =
    new DynamicVariable[KernelMessage](null)

  /**
   * Represents the current error stream used by the kernel for the specific
   * thread.
   */
  private val currentErrorStream =
    new DynamicVariable[PrintStream](null)
  private val currentErrorKernelMessage =
    new DynamicVariable[KernelMessage](null)

  private var _sparkContext:SparkContext = null;
  private var _sparkConf:SparkConf = null;
  private var _javaSparkContext:JavaSparkContext = null;
  private var _sqlContext:SQLContext = null;

  /**
   * Represents magics available through the kernel.
   */
  val magics = new MagicExecutor(magicLoader)

  /**
   * Represents magic parsing functionality.
   */
  val magicParser = new MagicParser(magicLoader)

  /**
   * Represents the data that can be shared using the kernel as the middleman.
   *
   * @note Using Java structure to enable other languages to have easy access!
   */
  val data: java.util.Map[String, Any] = new ConcurrentHashMap[String, Any]()


  interpreterManager.initializeInterpreters(this)

  val interpreter = interpreterManager.defaultInterpreter().get

  /**
   * Handles the output of interpreting code.
   * @param output the output of the interpreter
   * @return (success, message) or (failure, message)
   */
  private def handleInterpreterOutput(
    output: (Result, Either[ExecuteOutput, ExecuteFailure])
  ): (Boolean, String) = {
    val (success, result) = output
    success match {
      case Results.Success =>
        (true, result.left.getOrElse("").asInstanceOf[String])
      case Results.Error =>
        (false, result.right.getOrElse("").toString)
      case Results.Aborted =>
        (false, "Aborted!")
      case Results.Incomplete =>
        // If we get an incomplete it's most likely a syntax error, so
        // let the user know.
        (false, "Syntax Error!")
    }
  }

  /**
   * Executes a block of code represented as a string and returns the result.
   *
   * @param code The code as an option to execute
   * @return A tuple containing the result (true/false) and the output as a
   *         string
   */
  def eval(code: Option[String]): (Boolean, String) = {
    code.map(c => {
      magicParser.parse(c) match {
        case Left(parsedCode) =>
          val output = interpreter.interpret(parsedCode)
          handleInterpreterOutput(output)
        case Right(errMsg) =>
          (false, errMsg)
      }
    }).getOrElse((false, "Error!"))
  }

  /**
   * Constructs a new instance of the stream methods using the latest
   * kernel message instance.
   *
   * @return The collection of stream methods
   */
  override def stream: StreamMethods = stream()

  /**
   * Constructs a new instance of the stream methods using the specified
   * kernel message instance.
   *
   * @param parentMessage The message to serve as the parent of outgoing
   *                      messages sent as a result of using streaming methods
   *
   * @return The collection of streaming methods
   */
  private[spark] def stream(
    parentMessage: v5.KernelMessage = lastKernelMessage()
  ): StreamMethods = {
    new StreamMethods(actorLoader, parentMessage)
  }

  /**
   * Constructs a new instance of the factory methods using the latest
   * kernel message instance.
   *
   * @return The collection of factory methods
   */
  override def factory: FactoryMethods = factory()

  /**
   * Constructs a new instance of the factory methods using the specified
   * kernel message and kernel message builder.
   *
   * @param parentMessage The message to serve as the parent of outgoing
   *                      messages sent as a result of using an object created
   *                      by the factory methods
   * @param kmBuilder The builder to be used by objects created by factory
   *                  methods
   *
   * @return The collection of factory methods
   */
  private[spark] def factory(
    parentMessage: v5.KernelMessage = lastKernelMessage(),
    kmBuilder: v5.KMBuilder = v5.KMBuilder()
  ): FactoryMethods = {
    new FactoryMethods(config, actorLoader, parentMessage, kmBuilder)
  }

  /**
   * Returns a print stream to be used for communication back to clients
   * via standard out.
   *
   * @return The print stream instance or an error if the stream info is
   *         not found
   */
  override def out: PrintStream = {
    val kernelMessage = lastKernelMessage()

    constructStream(currentOutputStream, currentOutputKernelMessage, kernelMessage, { kernelMessage =>
      val outputStream = this.factory(parentMessage = kernelMessage)
        .newKernelOutputStream("stdout")

      new PrintStream(outputStream)
    })
  }

  /**
   * Returns a print stream to be used for communication back to clients
   * via standard error.
   *
   * @return The print stream instance or an error if the stream info is
   *         not found
   */
  override def err: PrintStream = {
    val kernelMessage = lastKernelMessage()

    constructStream(currentErrorStream, currentErrorKernelMessage, kernelMessage, { kernelMessage =>
      val outputStream = this.factory(parentMessage = kernelMessage)
        .newKernelOutputStream("stderr")

      new PrintStream(outputStream)
    })
  }

  /**
   * Returns an input stream to be used to receive information from the client.
   *
   * @return The input stream instance or an error if the stream info is
   *         not found
   */
  override def in: InputStream = {
    val kernelMessage = lastKernelMessage()

    constructStream(currentInputStream, currentInputKernelMessage, kernelMessage, { kernelMessage =>
      this.factory(parentMessage = kernelMessage).newKernelInputStream()
    })
  }

  /**
   * Constructs or uses an existing stream.
   *
   * @param dynamicStream The DynamicVariable containing the stream to modify
   *                      or use
   * @param dynamicKernelMessage The DynamicVariable containing the KernelMessage to
   *                          check against the new KernelMessage
   * @param newKernelMessage The potentially-new KernelMessage
   * @param streamConstructionFunc The function used to create a new stream
   * @param typeTag The type information associated with the stream
   * @tparam T The stream type
   * @return The new stream or existing stream
   */
  private def constructStream[T](
    dynamicStream: DynamicVariable[T],
    dynamicKernelMessage: DynamicVariable[KernelMessage],
    newKernelMessage: KernelMessage,
    streamConstructionFunc: (KernelMessage) => T
  )(implicit typeTag: TypeTag[T]) = {
    // Update the stream being used only if the information has changed
    // or if the stream has not been initialized
    if (updateKernelMessage(dynamicKernelMessage, newKernelMessage) ||
      dynamicStream.value == null)
    {
      logger.trace("Creating new kernel " + typeTag.tpe.toString + "!")
      dynamicStream.value = streamConstructionFunc(newKernelMessage)
    }

    dynamicStream.value
  }

  /**
   * Updates the last stream info returning the status of whether or not the
   * new stream info was different than the last stream info.
   *
   * @param dynamicKernelMessage The dynamic variable containing the current
   *                          stream info
   * @param kernelMessage The new stream info
   * @return True if the new stream info is different from the last (therefore
   *         replaced), otherwise false
   */
  private def updateKernelMessage(
    dynamicKernelMessage: DynamicVariable[KernelMessage],
    kernelMessage: KernelMessage
  ): Boolean =
    if (kernelMessage != null && !kernelMessage.equals(dynamicKernelMessage.value)) {
      dynamicKernelMessage.value = kernelMessage
      true
    } else {
      false
    }

  /**
   * Retrieves the last kernel message received by the kernel.
   *
   * @throws IllegalArgumentException If no kernel message has been received
   *
   * @return The kernel message instance
   */
  private def lastKernelMessage() = {
    val someKernelMessage = ExecuteRequestState.lastKernelMessage
    require(someKernelMessage.nonEmpty, "No kernel message received!")
    someKernelMessage.get
  }

  override def createSparkContext(conf: SparkConf): SparkContext = {
    _sparkConf = createSparkConf(conf)
    _sparkContext = initializeSparkContext(sparkConf);
    _javaSparkContext = new JavaSparkContext(_sparkContext)
    _sqlContext = new SQLContext(_sparkContext)

    updateInterpreterWithSparkContext(sparkContext)

    magicLoader.dependencyMap =
      magicLoader.dependencyMap.setSparkContext(_sparkContext)

    _sparkContext
  }

  override def createSparkContext(
    master: String, appName: String
  ): SparkContext = {
    createSparkContext(new SparkConf().setMaster(master).setAppName(appName))
  }

  // TODO: Think of a better way to test without exposing this
  protected[kernel] def createSparkConf(conf: SparkConf) = {

    logger.info("Setting deployMode to client")
    conf.set("spark.submit.deployMode", "client")

    KeyValuePairUtils.stringToKeyValuePairSeq(
      config.getString("spark_configuration")
    ).foreach { keyValuePair =>
      logger.info(s"Setting ${keyValuePair.key} to ${keyValuePair.value}")
      Try(conf.set(keyValuePair.key, keyValuePair.value))
    }

    // TODO: Move SparkIMain to private and insert in a different way
    logger.warn("Locked to Scala interpreter with SparkIMain until decoupled!")

    // TODO: Construct class server outside of SparkIMain
    logger.warn("Unable to control initialization of REPL class server!")
    logger.info("REPL Class Server Uri: " + interpreter.classServerURI)
    conf.set("spark.repl.class.uri", interpreter.classServerURI)

    conf
  }

  // TODO: Think of a better way to test without exposing this
  protected[kernel] def initializeSparkContext(sparkConf: SparkConf): SparkContext = {

    logger.debug("Constructing new Spark Context")
    // TODO: Inject stream redirect headers in Spark dynamically
    var sparkContext: SparkContext = null
    val outStream = new KernelOutputStream(
      actorLoader, KMBuilder(), global.ScheduledTaskManager.instance,
      sendEmptyOutput = config.getBoolean("send_empty_output")
    )

    // Update global stream state and use it to set the Console local variables
    // for threads in the Spark threadpool
    global.StreamState.setStreams(System.in, outStream, outStream)
    global.StreamState.withStreams {
      sparkContext = new SparkContext(sparkConf)
    }

    sparkContext
  }

  // TODO: Think of a better way to test without exposing this
  protected[kernel] def updateInterpreterWithSparkContext(
    sparkContext: SparkContext
  ) = {

    interpreter.bindSparkContext(sparkContext)
    /*
    interpreter.doQuietly {
      logger.debug("Binding context into interpreter")
      interpreter.bind(
        "sc", "org.apache.spark.SparkContext",
        sparkContext, List( """@transient"""))

      // NOTE: This is needed because interpreter blows up after adding
      //       dependencies to SparkContext and Interpreter before the
      //       cluster has been used... not exactly sure why this is the case
      // TODO: Investigate why the cluster has to be initialized in the kernel
      //       to avoid the kernel's interpreter blowing up (must be done
      //       inside the interpreter)
      logger.debug("Initializing Spark cluster in interpreter")

      interpreter.doQuietly {
        interpreter.interpret("""
                                | val $toBeNulled = {
                                | var $toBeNulled = sc.emptyRDD.collect()
                                | $toBeNulled = null
                                |  }
                                |
                                |""".stripMargin)
      }
    }
    */

    // Add ourselves as a dependency
    // TODO: Provide ability to point to library as commandline argument
    // TODO: Provide better method to determine if can add ourselves
    // TODO: Avoid duplicating request for master twice (initializeSparkContext
    //       also does this)
    val master = sparkContext.getConf.get("spark.master")

    // If in local mode, do not need to add our jars as dependencies
    if (!master.toLowerCase.startsWith("local")) {
      @inline def getJarPathFor(klass: Class[_]): String =
        klass.getProtectionDomain.getCodeSource.getLocation.getPath

      val interpreterC = interpreterManager.interpreters.values.map(_.getClass)

      // TODO: Provide less hard-coded solution in case additional dependencies
      //       are added or classes are refactored to different projects
      val classDep = Seq(

        // Macro project
        classOf[com.ibm.spark.annotations.Experimental],

        // Protocol project
        classOf[com.ibm.spark.kernel.protocol.v5.KernelMessage],

        // Communication project
        classOf[com.ibm.spark.communication.SocketManager],

        // Kernel-api project
        classOf[com.ibm.spark.kernel.api.KernelLike],

        // Scala-interpreter project
        //classOf[com.ibm.spark.kernel.interpreter.scala.ScalaInterpreter],

        // PySpark-interpreter project
        //classOf[com.ibm.spark.kernel.interpreter.pyspark.PySparkInterpreter],

        // SparkR-interpreter project
        //classOf[com.ibm.spark.kernel.interpreter.sparkr.SparkRInterpreter],

        // Kernel project
        classOf[com.ibm.spark.boot.KernelBootstrap]
      )

      val jarPaths = (interpreterC ++ classDep).map(getJarPathFor)

      logger.info("Adding kernel jars to cluster:\n- " +
        jarPaths.mkString("\n- "))
      jarPaths.foreach(sparkContext.addJar)
    } else {
      logger.info("Running in local mode! Not adding self as dependency!")
    }
  }

  override def interpreter(name: String): Option[Interpreter] = {
    interpreterManager.interpreters.get(name)
  }

  override def sparkContext: SparkContext = _sparkContext
  override def sparkConf: SparkConf = _sparkConf
  override def javaSparkContext: JavaSparkContext = _javaSparkContext
  override def sqlContext: SQLContext = _sqlContext
}
