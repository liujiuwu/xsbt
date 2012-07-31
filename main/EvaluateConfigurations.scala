/* sbt -- Simple Build Tool
 * Copyright 2011 Mark Harrah
 */
package sbt

	import java.io.File
	import java.net.URI
	import compiler.{Eval, EvalImports}
	import complete.DefaultParsers.validID
	import Def.{ScopedKey, Setting, SettingsDefinition}
	import Scope.GlobalScope
	import scala.annotation.tailrec

object EvaluateConfigurations
{
	def apply(eval: Eval, srcs: Seq[File], imports: Seq[String]): ClassLoader => Seq[Setting[_]] =
		flatten(srcs.sortBy(_.getName) map { src =>  evaluateConfiguration(eval, src, imports) })
	def evaluateConfiguration(eval: Eval, src: File, imports: Seq[String]): ClassLoader => Seq[Setting[_]] =
		evaluateConfiguration(eval, src.getPath, IO.readLines(src), imports, 0)
	def evaluateConfiguration(eval: Eval, name: String, lines: Seq[String], imports: Seq[String], offset: Int): ClassLoader => Seq[Setting[_]] =
	{
		val (importExpressions, settingExpressions) = splitExpressions(lines)
		val settings = addOffsetToRange(offset, settingExpressions) map { case (settingExpression,range) =>
			evaluateSetting(eval, name, (imports.map(s => (s, -1)) ++ addOffset(offset, importExpressions)), settingExpression, range)
		}
		flatten(settings)
	}
	def flatten(mksettings: Seq[ClassLoader => Seq[Setting[_]]]): ClassLoader => Seq[Setting[_]] =
		loader => mksettings.flatMap(_ apply loader)
	def addOffset(offset: Int, lines: Seq[(String,Int)]): Seq[(String,Int)] =
		lines.map { case (s, i) => (s, i + offset) }
	def addOffsetToRange(offset: Int, ranges: Seq[(String,LineRange)]): Seq[(String,LineRange)] =
		ranges.map { case (s, r) => (s, r shift offset) }

	val SettingsDefinitionName = classOf[SettingsDefinition].getName
	def evaluateSetting(eval: Eval, name: String, imports: Seq[(String,Int)], expression: String, range: LineRange): ClassLoader => Seq[Setting[_]] =
	{
		val result = try {
			eval.eval(expression, imports = new EvalImports(imports, name), srcName = name, tpeName = Some(SettingsDefinitionName), line = range.start)
		} catch {
			case e: sbt.compiler.EvalException => throw new MessageOnlyException(e.getMessage)
		}
		loader => {
			val pos = RangePosition(name, range shift 1)
			result.getValue(loader).asInstanceOf[SettingsDefinition].settings map (_ withPos pos)
		}
	}
	private[this] def isSpace = (c: Char) => Character isWhitespace c
	private[this] def fstS(f: String => Boolean): ((String,Int)) => Boolean = { case (s,i) => f(s) }
	private[this] def firstNonSpaceIs(lit: String) = (_: String).view.dropWhile(isSpace).startsWith(lit)
	private[this] def or[A](a: A => Boolean, b: A => Boolean): A => Boolean = in => a(in) || b(in)
	def splitExpressions(lines: Seq[String]): (Seq[(String,Int)], Seq[(String,LineRange)]) =
	{
		val blank = (_: String).forall(isSpace)
		val isImport = firstNonSpaceIs("import ")
		val comment = firstNonSpaceIs("//")
		val blankOrComment = or(blank, comment)
		val importOrBlank = fstS(or(blankOrComment, isImport))

		val (imports, settings) = lines.zipWithIndex span importOrBlank
		(imports filterNot fstS( blankOrComment ), groupedLines(settings, blank, blankOrComment))
	}
	def groupedLines(lines: Seq[(String,Int)], delimiter: String => Boolean, skipInitial: String => Boolean): Seq[(String,LineRange)] =
	{
		val fdelim = fstS(delimiter)
		@tailrec def group0(lines: Seq[(String,Int)], accum: Seq[(String,LineRange)]): Seq[(String,LineRange)] =
			if(lines.isEmpty) accum.reverse
			else
			{
				val start = lines dropWhile fstS( skipInitial )
				val (next, tail) = start.span { case (s,_) => !delimiter(s) }
				val grouped = if(next.isEmpty) accum else (next.map(_._1).mkString("\n"), LineRange(next.head._2, next.last._2 + 1)) +: accum
				group0(tail, grouped)
			}
		group0(lines, Nil)
	}
}
object Index
{
	def taskToKeyMap(data: Settings[Scope]): Map[Task[_], ScopedKey[Task[_]]] =
	{
		// AttributeEntry + the checked type test 'value: Task[_]' ensures that the cast is correct.
		//  (scalac couldn't determine that 'key' is of type AttributeKey[Task[_]] on its own and a type match still required the cast)
		val pairs = for( scope <- data.scopes; AttributeEntry(key, value: Task[_]) <- data.data(scope).entries ) yield
			(value, ScopedKey(scope, key.asInstanceOf[AttributeKey[Task[_]]])) // unclear why this cast is needed even with a type test in the above filter
		pairs.toMap[Task[_], ScopedKey[Task[_]]]
	}
	def allKeys(settings: Seq[Setting[_]]): Set[ScopedKey[_]] =
		settings.flatMap(s => if(s.key.key.isLocal) Nil else s.key +: s.dependencies).filter(!_.key.isLocal).toSet
	def attributeKeys(settings: Settings[Scope]): Set[AttributeKey[_]] =
		settings.data.values.flatMap(_.keys).toSet[AttributeKey[_]]
	def stringToKeyMap(settings: Set[AttributeKey[_]]): Map[String, AttributeKey[_]] =	
	{
		val multiMap = settings.groupBy(_.label)
		val duplicates = multiMap collect { case (k, xs) if xs.size > 1 => (k, xs.map(_.manifest)) } collect { case (k, xs) if xs.size > 1 => (k, xs) }
		if(duplicates.isEmpty)
			multiMap.collect { case (k, v) if validID(k) => (k, v.head) } toMap;
		else
			error(duplicates map { case (k, tps) => "'" + k + "' (" + tps.mkString(", ") + ")" } mkString("AttributeKey ID collisions detected for: ", ", ", ""))
	}
	private[this] type TriggerMap = collection.mutable.HashMap[Task[_], Seq[Task[_]]]
	def triggers(ss: Settings[Scope]): Triggers[Task] =
	{
		val runBefore = new TriggerMap
		val triggeredBy = new TriggerMap
		for( (_, amap) <- ss.data; AttributeEntry(_, value: Task[_]) <- amap.entries)
		{
			val as = value.info.attributes
			update(runBefore, value, as get Keys.runBefore)
			update(triggeredBy, value, as get Keys.triggeredBy)
		}
		val onComplete = Keys.onComplete in GlobalScope get ss getOrElse { () => () }
		new Triggers[Task](runBefore, triggeredBy, map => { onComplete(); map } )
	}
	private[this] def update(map: TriggerMap, base: Task[_], tasksOpt: Option[Seq[Task[_]]]): Unit =
		for( tasks <- tasksOpt; task <- tasks )
			map(task) = base +: map.getOrElse(task, Nil)
}