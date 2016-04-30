package io.glassdoor.plugin.plugins.preprocessor.smali

import java.io.File

import io.glassdoor.application.{ContextConstant, Constant, Context}
import io.glassdoor.plugin.Plugin
import org.jf.baksmali.{baksmaliOptions, baksmali}
import org.jf.dexlib2.{DexFileFactory, Opcodes}
import org.jf.dexlib2.iface.{ClassDef, DexFile}

import scala.collection.immutable.HashMap

/**
  * Created by Florian Schrofner on 3/16/16.
  */
class SmaliDisassembler extends Plugin{
  var mResult:Option[Map[String,String]] = None

  override def apply(data: Map[String,String], parameters: Array[String]): Unit = {
    //baksmali.disassembleDexFile(context.intermediateAssembly(Constant.INTERMEDIATE_ASSEMBLY_DEX))
    //val folder = new File(context.intermediateAssembly(Constant.INTERMEDIATE_ASSEMBLY_DEX))

    val workingDir = data.get(ContextConstant.FullKey.CONFIG_WORKING_DIRECTORY)

    if(workingDir.isDefined){
      val outputDirectory = workingDir.get + "/" + ContextConstant.Key.SMALI

      val options = new baksmaliOptions
      options.jobs = 1
      options.outputDirectory = outputDirectory

      val destination = data.get(ContextConstant.FullKey.CONFIG_WORKING_DIRECTORY)

      //TODO: use destination

      val dexFilePath = data.get(ContextConstant.FullKey.INTERMEDIATE_ASSEMBLY_DEX)

      if(dexFilePath.isDefined){
        val dexFileFile = new File(dexFilePath.get + "/classes.dex")
        val dexFile = DexFileFactory.loadDexFile(dexFileFile, options.dexEntry, options.apiLevel, options.experimental);

        try {
          baksmali.disassembleDexFile(dexFile, options)
          println("disassembling dex to: " + outputDirectory)
          val result = HashMap[String,String](ContextConstant.FullKey.INTERMEDIATE_ASSEMBLY_SMALI -> outputDirectory)
          mResult = Some(result)
        } catch {
          case e:IllegalArgumentException =>
            mResult = None
        }
      } else {
        println("dex not defined!")
      }

      ready
    }


  }

  override def result:Option[Map[String,String]] = {
    mResult
  }

  override def help(parameters: Array[String]): Unit = ???
}