package io.glassdoor.application

import java.io.{InputStream, OutputStream, PrintWriter}

import akka.event.Logging.Debug

import scala.io.Source
import scala.sys.process._

/**
  * Created by Florian Schrofner on 1/22/17.
  */
class AdbCommand(val command : String, val outputCallback : String => Unit){
  private var adbProcess : Process = null

  def execute(): Unit = {
    val adbConnection = new ProcessIO(adbInput, adbOutput, errorOutput)
    val adbCommand = Seq("adb", "shell")
    var adbProcess = adbCommand.run(adbConnection)
  }

  private def adbOutput(in: InputStream) {
    //do something
    Log.debug("adb output ready")
    val outputSource = Source.fromInputStream(in)
    val outputString = new StringBuilder()

    for(line <- outputSource.getLines()){
      Log.debug("line: " + line)
      outputString.append(line)
    }

    outputSource.close()
    outputCallback(outputString.toString())
  }

  private def adbInput(out: OutputStream) {
    Log.debug("adb input ready, executing: " + command)
    val writer = new PrintWriter(out)
    writer.println("su")
    writer.println(command)
    writer.close()
  }

  private def errorOutput(err: InputStream) {
    //do something
    err.close()
  }
}
