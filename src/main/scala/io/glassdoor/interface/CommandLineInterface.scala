package io.glassdoor.interface

import java.io.{PrintWriter, Writer}
import java.util.concurrent.TimeUnit
import javax.smartcardio.TerminalFactory

import akka.actor.{ActorRef, Cancellable, Props}
import io.glassdoor.application.{Command, CommandInterpreter, Context, Log}
import io.glassdoor.bus.{EventBus, Message, MessageEvent}
import io.glassdoor.controller.ControllerConstant
import io.glassdoor.plugin.PluginInstance
import io.glassdoor.plugin.manager.PluginManagerConstant.PluginErrorCode
import io.glassdoor.plugin.resource.ResourceManagerConstant
import io.glassdoor.plugin.resource.ResourceManagerConstant.ResourceErrorCode
import io.glassdoor.resource.Resource
import jline.{Terminal, UnixTerminal}
import jline.console.ConsoleReader
import jline.console.completer.StringsCompleter
import org.fusesource.jansi.AnsiConsole
import org.fusesource.jansi.Ansi
import org.fusesource.jansi.Ansi.Color

import scala.collection.mutable.ArrayBuffer
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.Duration

/**
  * Created by Florian Schrofner on 3/15/16.
  */
class CommandLineInterface extends UserInterface {

  //mConsoleOutput should be used for simple outputs, that will not change
  //while mConsole should be used for lines that will be updated
  var mConsole:Option[ConsoleReader] = None
  var mConsoleOutput:Option[Writer] = None

  var mCompleter:Option[StringsCompleter] = None
  var mCommandLineReader:Option[ActorRef] = None
  var mPluginsShowingProgress:Array[PluginProgress] = Array[PluginProgress]()

  //TODO: there can be mutliple animations going on!
  var mAnimationTask:Option[Cancellable] = None

  val newLine = sys.props("line.separator")


  override def receive: PartialFunction[Any, Unit] = {
    //let the default user interface trait handle all messages,
    //except commandline specific ones
    super.receive orElse {
      case CommandLineMessage(action, data) =>
        action match {
          case CommandLineInterfaceConstant.Action.HandleLine =>
            if(data.isDefined){
              val line = data.get.asInstanceOf[String]
              handleLine(line)
            }
        }
    }
  }

  override def initialise(context: Context): Unit = {
    Log.debug("initialising interface..")

    val console = new ConsoleReader()

    AnsiConsole.systemInstall()

    console.getTerminal.init()
    //console.clearScreen() //TODO: uncomment
    console.setPrompt(">")
    mConsoleOutput = Some(console.getOutput)

    mConsole = Some(console)

    startReadingFromCommandline()

//    setupAutoComplete()
  }

  def startReadingFromCommandline():Unit = {
    //run reader as own thread in order to prevent blocking of interface changes
    val commandLineReader = context.system.actorOf(Props(new CommandLineReader(self)))
    mCommandLineReader = Some(commandLineReader)

    commandLineReader ! CommandLineMessage(CommandLineReaderConstant.Action.init, mConsole)
  }

  def handleLine(line:String):Unit = {
    Log.debug("handle line called!")
    Log.debug("line: " + line)
    //TODO: handle "exit"!
    val input = CommandInterpreter.interpret(line)

    if(input.isDefined){
      //don't show next command prompt, while there is still a task executing
      mConsole.get.resetPromptLine("","",0)

      //TODO: use list of system commands instead
      //TODO: this should be moved out of the interface and be interpreted somewhere else
      if(input.get.name == "install") {
        Log.debug("install called!")
        EventBus.publish(MessageEvent(ControllerConstant.Channel, Message(ControllerConstant.Action.InstallResource, Some(input.get.parameters))))
      } else if(input.get.name == "remove"){
        Log.debug("remove called!")
        EventBus.publish(MessageEvent(ControllerConstant.Channel, Message(ControllerConstant.Action.RemoveResource, Some(input.get.parameters))))
      } else if(input.get.name == "update"){
        EventBus.publish(MessageEvent(ControllerConstant.Channel, Message(ControllerConstant.Action.UpdateAvailableResources,None)))
      } else if(input.get.name == "help"){
        if(input.get.parameters.length == 1){
          EventBus.publish(MessageEvent(ControllerConstant.Channel, Message(ControllerConstant.Action.ShowPluginHelp, Some(input.get.parameters(0)))))
        } else {
          EventBus.publish(MessageEvent(ControllerConstant.Channel, Message(ControllerConstant.Action.ShowPluginHelp, Some(""))))
        }
      } else if(input.get.name == "list"){
        EventBus.publish(MessageEvent(ControllerConstant.Channel, Message(ControllerConstant.Action.ShowPluginList, None)))
      } else if(input.get.name == "exit"){
        Log.debug("exit called!")
        if(mConsole.isDefined){
          mConsole.get.shutdown()
          mConsole = None
        }
        terminate()
      } else {
        EventBus.publish(MessageEvent(ControllerConstant.Channel, Message(ControllerConstant.Action.ApplyPlugin, input)))
      }
    }
  }

//  def setupAutoComplete():Unit = {
//    //TODO: handover all possible commands (system commands + plugins + aliases)
//    val completer = new StringsCompleter()
//    mCompleter = Some(completer)
//  }

  override def print(message: String): Unit = {
    Log.debug("commandline interface received print")
    if(mConsoleOutput.isDefined && mConsole.isDefined){
      Log.debug("console defined, printing..")
      Log.debug("message: " + message)
      val prompt = mConsole.get.getPrompt
      mConsoleOutput.get.append(message + newLine).flush()
      mConsole.get.setPrompt(prompt)
      //TODO: make sure that prompt is not overwritten here!
    } else {
      Log.debug("error: mConsole not defined")
    }
  }

  override def showPluginList(plugins: Array[PluginInstance]): Unit = {
    if(mConsoleOutput.isDefined){
      val console = mConsoleOutput.get
      for(plugin:PluginInstance <- plugins){
        console.append(plugin.kind + ":" + plugin.name + newLine)
      }
      console.flush()
    }
  }

  override def showProgress(taskInstance: PluginInstance, progress: Float): Unit = ???

  override def showEndlessProgress(taskInstance: PluginInstance): Unit = {
    Log.debug("commandline interface: showing endless progress")
    //TODO: check if endless progress is already shown for that plugin
    mPluginsShowingProgress = mPluginsShowingProgress :+ PluginProgress(taskInstance, 0, true)

    if(mConsole.isDefined){
      Log.debug("console defined")
      //restart progress updates
      stopProgressUpdates()
      startProgressUpdates()
    } else {
      Log.debug("error: console not defined!")
    }
  }

  def startProgressUpdates(): Unit ={
    val handle = context.system.scheduler.schedule(Duration.Zero, Duration.create(1, TimeUnit.SECONDS))(updateProgresses())
    mAnimationTask = Some(handle)
  }

  def stopProgressUpdates(): Unit ={
    if(mAnimationTask.isDefined){
      mAnimationTask.get.cancel()
      mAnimationTask = None
    }
  }

  def updateProgresses():Unit = {
    val stringBuilder = new StringBuilder
    for(pluginProgress <- mPluginsShowingProgress){
      if(pluginProgress.endlessProgress){
        val result = updateEndlessProgress(pluginProgress.pluginInstance, pluginProgress.progress)
        pluginProgress.progress = result.progressValue
        stringBuilder.append(result.progressString)
      } else {
        //TODO
      }
    }

    if(mConsole.isDefined){
      val console = mConsole.get
      //TODO: do some fancy ansi stuff here (or maybe above), to update multiple lines?
      console.resetPromptLine("",stringBuilder.toString(),-1)
    }
  }

  def updateEndlessProgress(taskInstance: PluginInstance, counter:Int):UpdateProgressResult = {
    if(mConsole.isDefined){
      val console = mConsole.get
      val infoString = "[" + taskInstance.uniqueId + "] " + taskInstance.name + ":"

      val stringBuilder = new StringBuilder()
      stringBuilder.append(CommandLineInterfaceConstant.Progress.StartString)

      for(i <- 1 to CommandLineInterfaceConstant.Progress.ProgressbarLength){
        if((i > counter && i <= counter + CommandLineInterfaceConstant.Progress.EndlessProgressLength)
          || (i < (counter + CommandLineInterfaceConstant.Progress.EndlessProgressLength) - CommandLineInterfaceConstant.Progress.ProgressbarLength)){
          stringBuilder.append(CommandLineInterfaceConstant.Progress.ProgressbarFilledString)
        } else {
          stringBuilder.append(CommandLineInterfaceConstant.Progress.ProgressbarEmptyString)
        }
      }

      stringBuilder.append(CommandLineInterfaceConstant.Progress.EndString)

      val terminalWidth = console.getTerminal.getWidth
      val spacing = terminalWidth - infoString.length - stringBuilder.length

      for(i <- 1 to spacing){
        stringBuilder.insert(0, " ")
      }

      //TODO: need to do something here
      //TODO: multithreading will not work here
      //console.resetPromptLine("",infoString + stringBuilder.toString(),-1)

      val resultString = infoString + stringBuilder.toString()
      var resultInt = counter + 1

      if(resultInt >= CommandLineInterfaceConstant.Progress.ProgressbarLength){
        resultInt = 0
      }

      UpdateProgressResult(resultString, resultInt)
    } else {
      UpdateProgressResult("", 0)
    }
  }

  override def taskCompleted(taskInstance: PluginInstance): Unit = {
    Log.debug("interface received task completed")

    if(mConsole.isDefined && mConsoleOutput.isDefined){
      stopAnimation(taskInstance)

      //show completed task
      val infoString = "[" + taskInstance.uniqueId + "] " + taskInstance.name + ":"

      val stringBuilder = new StringBuilder()
      stringBuilder.append(CommandLineInterfaceConstant.Progress.StartString)

      for(i <- 1 to CommandLineInterfaceConstant.Progress.ProgressbarLength){
        stringBuilder.append(CommandLineInterfaceConstant.Progress.ProgressbarFilledString)
      }

      stringBuilder.append(CommandLineInterfaceConstant.Progress.EndString)

      val console = mConsole.get

      val terminalWidth = console.getTerminal.getWidth
      val spacing = terminalWidth - infoString.length - stringBuilder.length

      for(i <- 1 to spacing){
        stringBuilder.insert(0, " ")
      }

      console.resetPromptLine("", "",-1)
      print(infoString + stringBuilder.toString())

      if(mPluginsShowingProgress.size > 0){
        startProgressUpdates()
      }
      //console.resetPromptLine("", infoString + stringBuilder.toString(),-1)
    }
  }


  override def resourceCompleted(resource: Option[Resource], code: Int): Unit = {
    code match {
      case ResourceManagerConstant.ResourceSuccessCode.ResourceSuccessfullyInstalled =>
        if(resource.isDefined){
          Console.println("successfully installed resource: " + resource.get.name + "[" + resource.get.kind + "]")
        }
      case ResourceManagerConstant.ResourceSuccessCode.ResourceSuccessfullyRemoved =>
        if(resource.isDefined){
          Console.println("successfully removed resource: " + resource.get.name + "[" + resource.get.kind + "]")
        }
    }

    waitForInput()
  }

  def stopAnimation(taskInstance: PluginInstance):Unit = {
    mPluginsShowingProgress = mPluginsShowingProgress.filterNot(_.pluginInstance.uniqueId == taskInstance.uniqueId)
    stopProgressUpdates()

    //TODO: clear prompt
  }

  override def taskFailed(taskInstance: Option[PluginInstance], error: Int, data:Option[Any]): Unit = {
    if(taskInstance.isDefined){
      stopAnimation(taskInstance.get)
    }

    if(mConsoleOutput.isDefined){
      error match {
        case PluginErrorCode.DependenciesNotSatisfied =>
          if(data.isDefined){
            print("error: dependency not satisfied: " + data.get.asInstanceOf[String])
          }
        case PluginErrorCode.DependenciesInChange =>
          if(data.isDefined){
            print("error: dependency in change: " + data.get.asInstanceOf[String])
          }
        case PluginErrorCode.PluginNotFound =>
          print("error: plugin not found!")
      }
    }

    if(mPluginsShowingProgress.size > 0){
      startProgressUpdates()
    }
    //waitForInput()
  }

  override def resourceFailed(resource: Option[Resource], error: Int, data: Option[Any]): Unit = {
    if(mConsoleOutput.isDefined){
      error match {
        case ResourceErrorCode.ResourceAlreadyInstalled =>
          if(resource.isDefined){
            print("error: resource already installed: " + resource.get.name + "[" + resource.get.kind + "]")
          }
        case ResourceErrorCode.ResourceNotFound =>
          print("error: resource not found!")
      }
    }

    //TODO: probably send waitForInput from resource manager
    waitForInput()
  }


  override def waitForInput(): Unit = {
    Log.debug("wait for input called!")

    //cancel animations that might be still going on
    stopProgressUpdates()
    mPluginsShowingProgress = Array()

    if(mCommandLineReader.isDefined){
      Log.debug("command line reader defined")
      val commandLineReader = mCommandLineReader.get
      commandLineReader ! CommandLineMessage(CommandLineReaderConstant.Action.read, None)
    } else {
      Log.debug("command line reader not defined")
    }
  }

}

case class CommandLineMessage(action: String, data:Option[Any])
case class PluginProgress(pluginInstance: PluginInstance, var progress:Int, endlessProgress:Boolean)
case class UpdateProgressResult(progressString:String, progressValue:Int)

object CommandLineInterfaceConstant {
  object Action {
    val HandleLine = "handleLine"
  }
  object Progress{
    val StartString = "["
    val EndString = "]"
    val ProgressbarLength = 25
    val ProgressbarEmptyString = "-"
    val ProgressbarFilledString = "#"
    val EndlessProgressLength = 20
  }
}
