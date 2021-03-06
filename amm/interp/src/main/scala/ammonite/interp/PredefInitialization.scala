package ammonite.interp


import ammonite.ops.Path
import ammonite.runtime.{APIHolder, SpecialClassLoader, Storage}
import ammonite.util.ScriptOutput.Metadata
import ammonite.util.{Imports, Name, PredefInfo, Res}
import ammonite.util.Util.CodeSource

/**
  * The logic around executing an [[Interpreter]]'s predef during
  * initialization
  */
object PredefInitialization {
  def apply(bridges: Seq[(String, String, AnyRef)],
            interpApi: InterpAPI,
            evalClassloader: SpecialClassLoader,
            storage: Storage,
            customPredefs: Seq[PredefInfo],
            processModule: (String, CodeSource, Boolean) => Res[Metadata],
            addImports: Imports => Unit,
            watch: Path => Unit): Res[_] = {

    for ((name, shortName, bridge) <- bridges ){
      APIHolder.initBridge(evalClassloader, name, bridge)
    }

    // import ammonite.repl.ReplBridge.{value => repl}
    // import ammonite.runtime.InterpBridge.{value => interp}
    val bridgePredefs =
      for ((name, shortName, bridge) <- bridges)
      yield PredefInfo(
        Name(s"${shortName}Bridge"),
        s"import $name.{value => $shortName}",
        true,
        None
      )

    val predefs = {
      bridgePredefs ++ customPredefs ++
      (storage.loadSharedPredef ++ storage.loadPredef).map{
        case (code, path) =>
          PredefInfo(Name(path.last.stripSuffix(".sc")), code, false, Some(path))
      }
    }

    predefs.filter(_.code.nonEmpty)


    Res.fold((), predefs){(_, predefInfo) =>
      if (predefInfo.code.isEmpty) Res.Success(())
      else {
        predefInfo.path.foreach(watch)
        processModule(
          predefInfo.code,
          CodeSource(
            predefInfo.name,
            Seq(),
            Seq(Name("ammonite"), Name("predef")),
            predefInfo.path
          ),
          predefInfo.hardcoded
        ) match{
          case Res.Skip => Res.Success(())
          case Res.Success(processed) =>
            addImports(processed.blockInfo.last.hookInfo.imports)
            addImports(processed.blockInfo.last.finalImports)
            Res.Success(())

          case x => x.map(_ => ())
        }

      }
    }
  }
}
