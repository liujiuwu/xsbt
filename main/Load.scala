/* sbt -- Simple Build Tool
 * Copyright 2011 Mark Harrah
 */
package sbt

	import java.io.File
	import java.net.{URI,URL}
	import compiler.{Eval,EvalImports}
	import xsbt.api.{Discovered,Discovery}
	import xsbti.compile.CompileOrder
	import classpath.ClasspathUtilities
	import scala.annotation.tailrec
	import collection.mutable
	import Compiler.{Compilers,Inputs}
	import inc.{FileValueCache, Locate}
	import Project.{inScope,makeSettings}
	import Def.{parseResult, ScopedKey, ScopeLocal, Setting}
	import Keys.{appConfiguration, baseDirectory, configuration, fullResolvers, fullClasspath, pluginData, streams, thisProject, thisProjectRef, update}
	import Keys.{isDummy, loadedBuild, resolvedScoped, taskDefinitionKey}
	import tools.nsc.reporters.ConsoleReporter
	import Build.{analyzed, data}
	import Scope.{GlobalScope, ThisScope}
	import Types.const
	import BuildPaths._
	import BuildStreams._
	import Locate.DefinesClass

object Load
{
	// note that there is State passed in but not pulled out
	def defaultLoad(state: State, baseDirectory: File, log: Logger, isPlugin: Boolean = false, topLevelExtras: List[URI] = Nil): (() => Eval, BuildStructure) =
	{
		val globalBase = getGlobalBase(state)
		val base = baseDirectory.getCanonicalFile
		val definesClass = FileValueCache(Locate.definesClass _)
		val rawConfig = defaultPreGlobal(state, base, definesClass.get, globalBase, log)
		val config0 = defaultWithGlobal(state, base, rawConfig, globalBase, log)
		val config = if(isPlugin) enableSbtPlugin(config0) else config0.copy(extraBuilds = topLevelExtras)
		val result = apply(base, state, config)
		definesClass.clear()
		result
	}
	def defaultPreGlobal(state: State, baseDirectory: File, definesClass: DefinesClass, globalBase: File, log: Logger): LoadBuildConfiguration =
	{
		val provider = state.configuration.provider
		val scalaProvider = provider.scalaProvider
		val stagingDirectory = getStagingDirectory(state, globalBase).getCanonicalFile
		val loader = getClass.getClassLoader
		val classpath = Attributed.blankSeq(provider.mainClasspath ++ scalaProvider.jars)
		val compilers = Compiler.compilers(ClasspathOptions.boot)(state.configuration, log)
		val evalPluginDef = EvaluateTask.evalPluginDef(log) _
		val delegates = defaultDelegates
		val pluginMgmt = PluginManagement(loader)
		val inject = InjectSettings(injectGlobal(state), Nil, const(Nil))
		new LoadBuildConfiguration(stagingDirectory, classpath, loader, compilers, evalPluginDef, definesClass, delegates,
			EvaluateTask.injectStreams, pluginMgmt, inject, None, Nil, log)
	}
	def injectGlobal(state: State): Seq[Setting[_]] =
		(appConfiguration in GlobalScope :== state.configuration) +:
		EvaluateTask.injectSettings
	def defaultWithGlobal(state: State, base: File, rawConfig: LoadBuildConfiguration, globalBase: File, log: Logger): LoadBuildConfiguration =
	{
		val globalPluginsDir = getGlobalPluginsDirectory(state, globalBase)
		val withGlobal = loadGlobal(state, base, globalPluginsDir, rawConfig)
		val globalSettings = configurationSources(getGlobalSettingsDirectory(state, globalBase))
		loadGlobalSettings(base, globalBase, globalSettings, withGlobal)
	}

	def loadGlobalSettings(base: File, globalBase: File, files: Seq[File], config: LoadBuildConfiguration): LoadBuildConfiguration =
	{
		val compiled: ClassLoader => Seq[Setting[_]]  =
			if(files.isEmpty || base == globalBase) const(Nil) else buildGlobalSettings(globalBase, files, config)
		config.copy(injectSettings = config.injectSettings.copy(projectLoaded = compiled))
	}
	def buildGlobalSettings(base: File, files: Seq[File], config: LoadBuildConfiguration): ClassLoader => Seq[Setting[_]] =
	{	
		val eval = mkEval(data(config.globalPluginClasspath), base, defaultEvalOptions)
		val imports = baseImports ++ importAllRoot(config.globalPluginNames)
		EvaluateConfigurations(eval, files, imports)
	}
	def loadGlobal(state: State, base: File, global: File, config: LoadBuildConfiguration): LoadBuildConfiguration =
		if(base != global && global.exists)
			config.copy(globalPlugin = Some(GlobalPlugin.load(global, state, config)))
		else
			config
	def defaultDelegates: LoadedBuild => Scope => Seq[Scope] = (lb: LoadedBuild) => {
		val rootProject = getRootProject(lb.units)
		def resolveRef(project: Reference): ResolvedReference = Scope.resolveReference(lb.root, rootProject, project)
		Scope.delegates(
			lb.allProjectRefs,
			(_: ResolvedProject).configurations.map(c => ConfigKey(c.name)),
			resolveRef,
			rootProject,
			project => projectInherit(lb, project),
			(project, config) => configInherit(lb, project, config, rootProject),
			task => task.extend,
			(project, extra) => Nil
		)
	}
	def configInherit(lb: LoadedBuild, ref: ResolvedReference, config: ConfigKey, rootProject: URI => String): Seq[ConfigKey] =
		ref match
		{
			case pr: ProjectRef => configInheritRef(lb, pr, config)
			case BuildRef(uri) => configInheritRef(lb, ProjectRef(uri, rootProject(uri)), config)
		}
	def configInheritRef(lb: LoadedBuild, ref: ProjectRef, config: ConfigKey): Seq[ConfigKey] =
		configurationOpt(lb.units, ref.build, ref.project, config).toList.flatMap(_.extendsConfigs).map(c => ConfigKey(c.name))

	def projectInherit(lb: LoadedBuild, ref: ProjectRef): Seq[ProjectRef] =
		getProject(lb.units, ref.build, ref.project).delegates

		// build, load, and evaluate all units.
		//  1) Compile all plugin definitions
		//  2) Evaluate plugin definitions to obtain and compile plugins and get the resulting classpath for the build definition
		//  3) Instantiate Plugins on that classpath
		//  4) Compile all build definitions using plugin classpath
		//  5) Load build definitions.
		//  6) Load all configurations using build definitions and plugins (their classpaths and loaded instances).
		//  7) Combine settings from projects, plugins, and configurations
		//  8) Evaluate settings
	def apply(rootBase: File, s: State, config: LoadBuildConfiguration): (() => Eval, BuildStructure) =
	{
		// load, which includes some resolution, but can't fill in project IDs yet, so follow with full resolution
		val loaded = resolveProjects(load(rootBase, s, config))
		val projects = loaded.units
		lazy val rootEval = lazyEval(loaded.units(loaded.root).unit)
		val settings = finalTransforms(buildConfigurations(loaded, getRootProject(projects), rootEval, config.injectSettings))
		val delegates = config.delegates(loaded)
		val data = makeSettings(settings, delegates, config.scopeLocal)( Project.showLoadingKey( loaded ) )
		val index = structureIndex(data, settings, loaded.extra(data))
		val streams = mkStreams(projects, loaded.root, data)
		(rootEval, new BuildStructure(projects, loaded.root, settings, data, index, streams, delegates, config.scopeLocal))
	}

	// map dependencies on the special tasks:
	// 1. the scope of 'streams' is the same as the defining key and has the task axis set to the defining key
	// 2. the defining key is stored on constructed tasks
	// 3. resolvedScoped is replaced with the defining key as a value
	// 4. parseResult is replaced with a task that provides the result of parsing for the defined InputTask
	// Note: this must be idempotent.
	def finalTransforms(ss: Seq[Setting[_]]): Seq[Setting[_]] =
	{
		def mapSpecial(to: ScopedKey[_]) = new (ScopedKey ~> ScopedKey){ def apply[T](key: ScopedKey[T]) =
			if(key.key == streams.key)
				ScopedKey(Scope.fillTaskAxis(Scope.replaceThis(to.scope)(key.scope), to.key), key.key)
			else key
		}
		def setDefining[T] = (key: ScopedKey[T], value: T) => value match {
			case tk: Task[t] => setDefinitionKey(tk, key).asInstanceOf[T]
			case ik: InputTask[t] => ik.mapTask( tk => setDefinitionKey(tk, key) ).asInstanceOf[T]
			case _ => value
		}
		def setResolved(defining: ScopedKey[_]) = new (ScopedKey ~> Option) { def apply[T](key: ScopedKey[T]): Option[T] =
			key.key match
			{
				case resolvedScoped.key => Some(defining.asInstanceOf[T])
				case parseResult.key =>
						import std.TaskExtra._
					val getResult = InputTask.inputMap map { m => m get defining getOrElse error("No parsed value for " + Def.displayFull(defining) + "\n" + m) }
					Some(getResult.asInstanceOf[T])
				case _ => None
			}
		}
		ss.map(s => s mapConstant setResolved(s.key) mapReferenced mapSpecial(s.key) mapInit setDefining )
	}
	def setDefinitionKey[T](tk: Task[T], key: ScopedKey[_]): Task[T] =
		if(isDummy(tk)) tk else Task(tk.info.set(Keys.taskDefinitionKey, key), tk.work)

	def structureIndex(data: Settings[Scope], settings: Seq[Setting[_]], extra: KeyIndex => BuildUtil[_]): StructureIndex =
	{
		val keys = Index.allKeys(settings)
		val attributeKeys = Index.attributeKeys(data) ++ keys.map(_.key)
		val scopedKeys = keys ++ data.allKeys( (s,k) => ScopedKey(s,k))
		val keyIndex = KeyIndex(scopedKeys)
		val aggIndex = KeyIndex.aggregate(scopedKeys, extra(keyIndex))
		new StructureIndex(Index.stringToKeyMap(attributeKeys), Index.taskToKeyMap(data), Index.triggers(data), keyIndex, aggIndex)
	}

		// Reevaluates settings after modifying them.  Does not recompile or reload any build components.
	def reapply(newSettings: Seq[Setting[_]], structure: BuildStructure)(implicit display: Show[ScopedKey[_]]): BuildStructure =
	{
		val transformed = finalTransforms(newSettings)
		val newData = makeSettings(transformed, structure.delegates, structure.scopeLocal)
		val newIndex = structureIndex(newData, transformed, index => buildUtil(structure.root, structure.units, index, newData))
		val newStreams = mkStreams(structure.units, structure.root, newData)
		new BuildStructure(units = structure.units, root = structure.root, settings = transformed, data = newData, index = newIndex, streams = newStreams, delegates = structure.delegates, scopeLocal = structure.scopeLocal)
	}

	def isProjectThis(s: Setting[_]) = s.key.scope.project match { case This | Select(ThisProject) => true; case _ => false }
	def buildConfigurations(loaded: LoadedBuild, rootProject: URI => String, rootEval: () => Eval, injectSettings: InjectSettings): Seq[Setting[_]] =
	{
		((loadedBuild in GlobalScope :== loaded) +:
		transformProjectOnly(loaded.root, rootProject, injectSettings.global)) ++ 
		inScope(GlobalScope)( pluginGlobalSettings(loaded) ) ++
		loaded.units.toSeq.flatMap { case (uri, build) =>
			val eval = if(uri == loaded.root) rootEval else lazyEval(build.unit)
			val plugins = build.unit.plugins.plugins
			val (pluginSettings, pluginProjectSettings, pluginBuildSettings) = extractSettings(plugins)
			val (pluginThisProject, pluginNotThis) = pluginSettings partition isProjectThis
			val projectSettings = build.defined flatMap { case (id, project) =>
				val srcs = configurationSources(project.base)
				val ref = ProjectRef(uri, id)
				val defineConfig = for(c <- project.configurations) yield ( (configuration in (ref, ConfigKey(c.name))) :== c)
				val loader = build.unit.definitions.loader
				val settings =
					(thisProject :== project) +:
					(thisProjectRef :== ref) +:
					(defineConfig ++ project.settings ++ injectSettings.projectLoaded(loader) ++ pluginThisProject ++
						pluginProjectSettings ++ configurations(srcs, eval, build.imports)(loader) ++ injectSettings.project)
				 
				// map This to thisScope, Select(p) to mapRef(uri, rootProject, p)
				transformSettings(projectScope(ref), uri, rootProject, settings)
			}
			val buildScope = Scope(Select(BuildRef(uri)), Global, Global, Global)
			val buildBase = baseDirectory :== build.localBase
			val buildSettings = transformSettings(buildScope, uri, rootProject, pluginNotThis ++ pluginBuildSettings ++ (buildBase +: build.buildSettings))
			buildSettings ++ projectSettings
		}
	}
	def pluginGlobalSettings(loaded: LoadedBuild): Seq[Setting[_]] =
		loaded.units.toSeq flatMap { case (_, build) =>
			build.unit.plugins.plugins flatMap { _.globalSettings }
		}
	def extractSettings(plugins: Seq[Plugin]): (Seq[Setting[_]], Seq[Setting[_]], Seq[Setting[_]]) =
		(plugins.flatMap(_.settings), plugins.flatMap(_.projectSettings), plugins.flatMap(_.buildSettings))
	def transformProjectOnly(uri: URI, rootProject: URI => String, settings: Seq[Setting[_]]): Seq[Setting[_]] =
		Project.transform(Scope.resolveProject(uri, rootProject), settings)
	def transformSettings(thisScope: Scope, uri: URI, rootProject: URI => String, settings: Seq[Setting[_]]): Seq[Setting[_]] =
		Project.transform(Scope.resolveScope(thisScope, uri, rootProject), settings)
	def projectScope(project: Reference): Scope  =  Scope(Select(project), Global, Global, Global)
	
	def lazyEval(unit: BuildUnit): () => Eval =
	{
		lazy val eval = mkEval(unit)
		() => eval
	}
	def mkEval(unit: BuildUnit): Eval = mkEval(unit.definitions, unit.plugins, Nil)
	def mkEval(defs: LoadedDefinitions, plugs: LoadedPlugins, options: Seq[String]): Eval =
		mkEval(defs.target ++ plugs.classpath, defs.base, options)
	def mkEval(classpath: Seq[File], base: File, options: Seq[String]): Eval =
		new Eval(options, classpath, s => new ConsoleReporter(s), Some(evalOutputDirectory(base)))

	def configurations(srcs: Seq[File], eval: () => Eval, imports: Seq[String]): ClassLoader => Seq[Setting[_]] =
		if(srcs.isEmpty) const(Nil) else EvaluateConfigurations(eval(), srcs, imports)

	def load(file: File, s: State, config: LoadBuildConfiguration): PartBuild =
		load(file, builtinLoader(s, config.copy(pluginManagement = config.pluginManagement.shift, extraBuilds = Nil)), config.extraBuilds.toList )
	def builtinLoader(s: State, config: LoadBuildConfiguration): BuildLoader =
	{
		val fail = (uri: URI) => error("Invalid build URI (no handler available): " + uri)
		val resolver = (info: BuildLoader.ResolveInfo) => RetrieveUnit(info)
		val build = (info: BuildLoader.BuildInfo) => Some(() => loadUnit(info.uri, info.base, info.state, info.config))
		val components = BuildLoader.components(resolver, build, full = BuildLoader.componentLoader)
		BuildLoader(components, fail, s, config)
	}
	def load(file: File, loaders: BuildLoader, extra: List[URI]): PartBuild = loadURI(IO.directoryURI(file), loaders, extra)
	def loadURI(uri: URI, loaders: BuildLoader, extra: List[URI]): PartBuild =
	{
		IO.assertAbsolute(uri)
		val (referenced, map, newLoaders) = loadAll(uri :: extra, Map.empty, loaders, Map.empty)
		checkAll(referenced, map)
		val build = new PartBuild(uri, map)
		newLoaders transformAll build
	}
	def addOverrides(unit: BuildUnit, loaders: BuildLoader): BuildLoader =
		loaders updatePluginManagement PluginManagement.extractOverrides(unit.plugins.fullClasspath)

	def addResolvers(unit: BuildUnit, isRoot: Boolean, loaders: BuildLoader): BuildLoader =
		unit.definitions.builds.flatMap(_.buildLoaders) match
		{
			case Nil => loaders
			case x :: xs =>
				import Alternatives._
				val resolver = (x /: xs){ _ | _ }
				if(isRoot) loaders.setRoot(resolver) else loaders.addNonRoot(unit.uri, resolver)
		}

	def loaded(unit: BuildUnit): (PartBuildUnit, List[ProjectReference]) =
	{
		val defined = projects(unit)
		if(defined.isEmpty) error("No projects defined in build unit " + unit)

		// since base directories are resolved at this point (after 'projects'),
		//   we can compare Files instead of converting to URIs
		def isRoot(p: Project) = p.base == unit.localBase

		val externals = referenced(defined).toList
		val projectsInRoot = defined.filter(isRoot).map(_.id)
		val rootProjects = if(projectsInRoot.isEmpty) defined.head.id :: Nil else projectsInRoot
		(new PartBuildUnit(unit, defined.map(d => (d.id, d)).toMap, rootProjects, buildSettings(unit)), externals)
	}
	def buildSettings(unit: BuildUnit): Seq[Setting[_]] =
	{
		val buildScope = GlobalScope.copy(project = Select(BuildRef(unit.uri)))
		val resolve = Scope.resolveBuildScope(buildScope, unit.uri)
		Project.transform(resolve, unit.definitions.builds.flatMap(_.settings))
	}

	@tailrec def loadAll(bases: List[URI], references: Map[URI, List[ProjectReference]], loaders: BuildLoader, builds: Map[URI, PartBuildUnit]): (Map[URI, List[ProjectReference]], Map[URI, PartBuildUnit], BuildLoader) =
		bases match
		{
			case b :: bs =>
				if(builds contains b)
					loadAll(bs, references, loaders, builds)
				else
				{
					val (loadedBuild, refs) = loaded(loaders(b))
					checkBuildBase(loadedBuild.unit.localBase)
					val newLoader = addOverrides(loadedBuild.unit, addResolvers(loadedBuild.unit, builds.isEmpty, loaders))
					// it is important to keep the load order stable, so we sort the remaining URIs
					val remainingBases = (refs.flatMap(Reference.uri) reverse_::: bs).sorted
					loadAll(remainingBases, references.updated(b, refs), newLoader, builds.updated(b, loadedBuild))
				}
			case Nil => (references, builds, loaders)
		}
	def checkProjectBase(buildBase: File, projectBase: File)
	{
		checkDirectory(projectBase)
		assert(buildBase == projectBase || IO.relativize(buildBase, projectBase).isDefined, "Directory " + projectBase + " is not contained in build root " + buildBase)
	}
	def checkBuildBase(base: File) = checkDirectory(base)
	def checkDirectory(base: File)
	{
		assert(base.isAbsolute, "Not absolute: " + base)
		if(base.isFile)
			error("Not a directory: " + base)
		else if(!base.exists)
			IO createDirectory base
	}
	def resolveAll(builds: Map[URI, PartBuildUnit]): Map[URI, LoadedBuildUnit] =
	{
		val rootProject = getRootProject(builds)
		builds map { case (uri,unit) =>
			(uri, unit.resolveRefs( ref => Scope.resolveProjectRef(uri, rootProject, ref) ))
		} toMap;
	}
	def checkAll(referenced: Map[URI, List[ProjectReference]], builds: Map[URI, PartBuildUnit])
	{
		val rootProject = getRootProject(builds)
		for( (uri, refs) <- referenced; ref <- refs)
		{
			val ProjectRef(refURI, refID) = Scope.resolveProjectRef(uri, rootProject, ref)
			val loadedUnit = builds(refURI)
			if(! (loadedUnit.defined contains refID) )
				error("No project '" + refID + "' in '" + refURI + "'")
		}
	}

	def resolveBase(against: File): Project => Project =
	{
		def resolve(f: File) =
		{
			val fResolved = new File(IO.directoryURI(IO.resolve(against, f)))
			checkProjectBase(against, fResolved)
			fResolved
		}
		p => p.copy(base = resolve(p.base))
	}
	def resolveProjects(loaded: PartBuild): LoadedBuild =
	{
		val rootProject = getRootProject(loaded.units)
		new LoadedBuild(loaded.root, loaded.units map { case (uri, unit) =>
			IO.assertAbsolute(uri)
			(uri, resolveProjects(uri, unit, rootProject))
		})
	}
	def resolveProjects(uri: URI, unit: PartBuildUnit, rootProject: URI => String): LoadedBuildUnit =
	{
		IO.assertAbsolute(uri)
		val resolve = (_: Project).resolve(ref => Scope.resolveProjectRef(uri, rootProject, ref))
		new LoadedBuildUnit(unit.unit, unit.defined mapValues resolve toMap, unit.rootProjects, unit.buildSettings)
	}
	def projects(unit: BuildUnit): Seq[Project] =
	{
		// we don't have the complete build graph loaded, so we don't have the rootProject function yet.
		//  Therefore, we use resolveProjectBuild instead of resolveProjectRef.  After all builds are loaded, we can fully resolve ProjectReferences.
		val resolveBuild = (_: Project).resolveBuild(ref => Scope.resolveProjectBuild(unit.uri, ref))
		val resolve = resolveBuild compose resolveBase(unit.localBase)
		unit.definitions.builds.flatMap(_.projectDefinitions(unit.localBase) map resolve)
	}
	def getRootProject(map: Map[URI, BuildUnitBase]): URI => String =
		uri => getBuild(map, uri).rootProjects.headOption getOrElse emptyBuild(uri)
	def getConfiguration(map: Map[URI, LoadedBuildUnit], uri: URI, id: String, conf: ConfigKey): Configuration =
		configurationOpt(map, uri, id, conf) getOrElse noConfiguration(uri, id, conf.name)
	def configurationOpt(map: Map[URI, LoadedBuildUnit], uri: URI, id: String, conf: ConfigKey): Option[Configuration] =
		getProject(map, uri, id).configurations.find(_.name == conf.name)

	def getProject(map: Map[URI, LoadedBuildUnit], uri: URI, id: String): ResolvedProject =
		getBuild(map, uri).defined.getOrElse(id, noProject(uri, id))
	def getBuild[T](map: Map[URI, T], uri: URI): T =
		map.getOrElse(uri, noBuild(uri))

	def emptyBuild(uri: URI) = error("No root project defined for build unit '" + uri + "'")
	def noBuild(uri: URI) = error("Build unit '" + uri + "' not defined.")
	def noProject(uri: URI, id: String) = error("No project '" + id + "' defined in '" + uri + "'.")
	def noConfiguration(uri: URI, id: String, conf: String) = error("No configuration '" + conf + "' defined in project '" + id + "' in '" + uri +"'")

	def loadUnit(uri: URI, localBase: File, s: State, config: LoadBuildConfiguration): BuildUnit =
	{
		val normBase = localBase.getCanonicalFile
		val defDir = selectProjectDir(normBase, config.log)
		val pluginDir = pluginDirectory(defDir)
		val oldStyleExists = pluginDir.isDirectory
		val newStyleExists = configurationSources(defDir).nonEmpty || projectStandard(defDir).exists
		val (plugs, defs) =
			if(newStyleExists || !oldStyleExists)
			{
				if(oldStyleExists)
					config.log.warn("Detected both new and deprecated style of plugin configuration.\n  Ignoring deprecated project/plugins/ directory (" + pluginDir + ").")
				loadUnitNew(defDir, s, config)
			}
			else
				loadUnitOld(defDir, pluginDir, s, config)

		new BuildUnit(uri, normBase, defs, plugs)
	}
	def loadUnitNew(defDir: File, s: State, config: LoadBuildConfiguration): (LoadedPlugins, LoadedDefinitions) =
	{
		val plugs = plugins(defDir, s, config)
		val defNames = analyzed(plugs.fullClasspath) flatMap findDefinitions
		val defs = if(defNames.isEmpty) Build.default :: Nil else loadDefinitions(plugs.loader, defNames)
		val loadedDefs = new LoadedDefinitions(defDir, Nil, plugs.loader, defs, defNames)
		(plugs, loadedDefs)
	}
	def loadUnitOld(defDir: File, pluginDir: File, s: State, config: LoadBuildConfiguration): (LoadedPlugins, LoadedDefinitions) =
	{
		config.log.warn("Using project/plugins/ (" + pluginDir + ") for plugin configuration is deprecated.\n" + 
			"Put .sbt plugin definitions directly in project/,\n  .scala plugin definitions in project/project/,\n  and remove the project/plugins/ directory.")
		val plugs = plugins(pluginDir, s, config)
		val defs = definitionSources(defDir)
		val target = buildOutputDirectory(defDir, config.compilers.scalac.scalaInstance)
		IO.createDirectory(target)
		val loadedDefs =
			if(defs.isEmpty)
				new LoadedDefinitions(defDir, target :: Nil, plugs.loader, Build.default :: Nil, Nil)
			else
				definitions(defDir, target, defs, plugs, config.definesClass, config.compilers, config.log)
		(plugs, loadedDefs)
	}

	def globalPluginClasspath(globalPlugin: Option[GlobalPlugin]): Seq[Attributed[File]] =
		globalPlugin match
		{
			case Some(cp) => cp.data.fullClasspath
			case None => Nil
		}
	val autoPluginSettings: Seq[Setting[_]] = inScope(GlobalScope in LocalRootProject)(Seq(
		Keys.sbtPlugin :== true,
		pluginData <<= (fullClasspath in Configurations.Runtime, update, fullResolvers) map ( (cp, rep, rs) => PluginData(cp, Some(rs), Some(rep)) ),
		Keys.onLoadMessage <<= Keys.baseDirectory("Loading project definition from " + _)
	))
	def enableSbtPlugin(config: LoadBuildConfiguration): LoadBuildConfiguration =
		config.copy(injectSettings = config.injectSettings.copy(
			global = autoPluginSettings ++ config.injectSettings.global,
			project = config.pluginManagement.inject ++ config.injectSettings.project
		))
	def activateGlobalPlugin(config: LoadBuildConfiguration): LoadBuildConfiguration =
		config.globalPlugin match
		{
			case Some(gp) => config.copy(injectSettings = config.injectSettings.copy(project = gp.inject))
			case None => config
		}
	def plugins(dir: File, s: State, config: LoadBuildConfiguration): LoadedPlugins =
		if(hasDefinition(dir))
			buildPlugins(dir, s, enableSbtPlugin(activateGlobalPlugin(config)))
		else
			noPlugins(dir, config)

	def hasDefinition(dir: File) =
	{
		import Path._
		!(dir * -GlobFilter(DefaultTargetName)).get.isEmpty
	}
	def noPlugins(dir: File, config: LoadBuildConfiguration): LoadedPlugins =
		loadPluginDefinition(dir, config, PluginData(config.globalPluginClasspath, None, None))
	def buildPlugins(dir: File, s: State, config: LoadBuildConfiguration): LoadedPlugins =
		loadPluginDefinition(dir, config, buildPluginDefinition(dir, s, config))

	def loadPluginDefinition(dir: File, config: LoadBuildConfiguration, pluginData: PluginData): LoadedPlugins =
	{
		val (definitionClasspath, pluginLoader) = pluginDefinitionLoader(config, pluginData.classpath)
		loadPlugins(dir, pluginData.copy(classpath = definitionClasspath), pluginLoader)
	}
	def pluginDefinitionLoader(config: LoadBuildConfiguration, pluginClasspath: Seq[Attributed[File]]): (Seq[Attributed[File]], ClassLoader) =
	{
		val definitionClasspath = if(pluginClasspath.isEmpty) config.classpath else (pluginClasspath ++ config.classpath).distinct
		val pm = config.pluginManagement
		def addToLoader() = pm.loader add Path.toURLs(data(pluginClasspath))
		val pluginLoader = if(pluginClasspath.isEmpty) pm.initialLoader else { addToLoader(); pm.loader }
		(definitionClasspath, pluginLoader)
	}
	def buildPluginDefinition(dir: File, s: State, config: LoadBuildConfiguration): PluginData =
	{
		val (eval,pluginDef) = apply(dir, s, config)
		val pluginState = Project.setProject(Load.initialSession(pluginDef, eval), pluginDef, s)
		config.evalPluginDef(pluginDef, pluginState)
	}

	def definitions(base: File, targetBase: File, srcs: Seq[File], plugins: LoadedPlugins, definesClass: DefinesClass, compilers: Compilers, log: Logger): LoadedDefinitions =
	{
		val (inputs, defAnalysis) = build(plugins.fullClasspath, srcs, targetBase, compilers, definesClass, log)
		val target = inputs.config.classesDirectory
		val definitionLoader = ClasspathUtilities.toLoader(target :: Nil, plugins.loader)
		val defNames = findDefinitions(defAnalysis)
		val defs = if(defNames.isEmpty) Build.default :: Nil else loadDefinitions(definitionLoader, defNames)
		new LoadedDefinitions(base, target :: Nil, definitionLoader, defs, defNames)
	}

	def loadDefinitions(loader: ClassLoader, defs: Seq[String]): Seq[Build] =
		defs map { definition => loadDefinition(loader, definition) }
	def loadDefinition(loader: ClassLoader, definition: String): Build =
		ModuleUtilities.getObject(definition, loader).asInstanceOf[Build]

	def build(classpath: Seq[Attributed[File]], sources: Seq[File], target: File, compilers: Compilers, definesClass: DefinesClass, log: Logger): (Inputs, inc.Analysis) =
	{
		// TODO: make used of classpath metadata for recompilation
		val inputs = Compiler.inputs(data(classpath), sources, target, Nil, Nil, definesClass, Compiler.DefaultMaxErrors, CompileOrder.Mixed)(compilers, log)
		val analysis = Compiler(inputs, log)
		(inputs, analysis)
	}

	def loadPlugins(dir: File, data: PluginData, loader: ClassLoader): LoadedPlugins =
	{
		val (pluginNames, plugins) = if(data.classpath.isEmpty) (Nil, Nil) else {
			val names = getPluginNames(data.classpath, loader)
			val loaded =
				try loadPlugins(loader, names)
				catch { case e: LinkageError => incompatiblePlugins(data, e) }
			(names, loaded)
		}
		new LoadedPlugins(dir, data, loader, plugins, pluginNames)
	}
	private[this] def incompatiblePlugins(data: PluginData, t: LinkageError): Nothing =
	{
		val evicted = data.report.toList.flatMap(_.configurations.flatMap(_.evicted))
		val evictedModules = evicted map { id => (id.organization, id.name) } distinct ;
		val evictedStrings = evictedModules map { case (o,n) => o + ":" + n }
		val msgBase = "Binary incompatibility in plugins detected."
		val msgExtra = if(evictedStrings.isEmpty) "" else "\nNote that conflicts were resolved for some dependencies:\n\t" + evictedStrings.mkString("\n\t")
		throw new IncompatiblePluginsException(msgBase + msgExtra, t)
	}
	def getPluginNames(classpath: Seq[Attributed[File]], loader: ClassLoader): Seq[String] =
		 ( binaryPlugins(Build.data(classpath), loader) ++ (analyzed(classpath) flatMap findPlugins) ).distinct

	def binaryPlugins(classpath: Seq[File], loader: ClassLoader): Seq[String] =
	{
		import collection.JavaConversions._
		loader.getResources("sbt/sbt.plugins").toSeq.filter(onClasspath(classpath)) flatMap { u =>
			IO.readLinesURL(u).map( _.trim).filter(!_.isEmpty)
		}
	}
	def onClasspath(classpath: Seq[File])(url: URL): Boolean =
		IO.urlAsFile(url) exists (classpath.contains _)

	def loadPlugins(loader: ClassLoader, pluginNames: Seq[String]): Seq[Plugin] =
		pluginNames.map(pluginName => loadPlugin(pluginName, loader))

	def loadPlugin(pluginName: String, loader: ClassLoader): Plugin =
		ModuleUtilities.getObject(pluginName, loader).asInstanceOf[Plugin]

	def findPlugins(analysis: inc.Analysis): Seq[String]  =  discover(analysis, "sbt.Plugin")
	def findDefinitions(analysis: inc.Analysis): Seq[String]  =  discover(analysis, "sbt.Build")
	def discover(analysis: inc.Analysis, subclasses: String*): Seq[String] =
	{
		val subclassSet = subclasses.toSet
		val ds = Discovery(subclassSet, Set.empty)(Tests.allDefs(analysis))
		ds.flatMap {
			case (definition, Discovered(subs,_,_,true)) =>
				if((subs & subclassSet).isEmpty) Nil else definition.name :: Nil
			case _ => Nil
		}
	}

	def initialSession(structure: BuildStructure, rootEval: () => Eval, s: State): SessionSettings = {
		val session = s get Keys.sessionSettings
		val currentProject = session map (_.currentProject) getOrElse Map.empty
		val currentBuild = session map (_.currentBuild) filter (uri => structure.units.keys exists (uri ==)) getOrElse structure.root
		new SessionSettings(currentBuild, projectMap(structure, currentProject), structure.settings, Map.empty, Nil, rootEval)
	}

	def initialSession(structure: BuildStructure, rootEval: () => Eval): SessionSettings =
		new SessionSettings(structure.root, projectMap(structure, Map.empty), structure.settings, Map.empty, Nil, rootEval)
		
	def projectMap(structure: BuildStructure, current: Map[URI, String]): Map[URI, String] =
	{
		val units = structure.units
		val getRoot = getRootProject(units)
		def project(uri: URI) = {
			current get uri filter {
				p => structure allProjects uri map (_.id) contains p
			} getOrElse getRoot(uri)
		}
		units.keys.map(uri => (uri, project(uri))).toMap
	}

	def defaultEvalOptions: Seq[String] = Nil

	@deprecated("Use BuildUtil.baseImports", "0.13.0")
	def baseImports = BuildUtil.baseImports
	@deprecated("Use BuildUtil.checkCycles", "0.13.0")
	def checkCycles(units: Map[URI, LoadedBuildUnit]): Unit = BuildUtil.checkCycles(units)
	@deprecated("Use BuildUtil.importAll", "0.13.0")
	def importAll(values: Seq[String]): Seq[String] = BuildUtil.importAll(values)
	@deprecated("Use BuildUtil.importAllRoot", "0.13.0")
	def importAllRoot(values: Seq[String]): Seq[String] = BuildUtil.importAllRoot(values)
	@deprecated("Use BuildUtil.rootedNames", "0.13.0")
	def rootedName(s: String): String = BuildUtil.rootedName(s)
	@deprecated("Use BuildUtil.getImports", "0.13.0")
	def getImports(unit: BuildUnit): Seq[String] = BuildUtil.getImports(unit)

	def referenced[PR <: ProjectReference](definitions: Seq[ProjectDefinition[PR]]): Seq[PR] = definitions flatMap { _.referenced }
	
	@deprecated("LoadedBuildUnit is now top-level", "0.13.0")
	type LoadedBuildUnit = sbt.LoadedBuildUnit

	@deprecated("BuildStructure is now top-level", "0.13.0")
	type BuildStructure = sbt.BuildStructure

	@deprecated("StructureIndex is now top-level", "0.13.0")
	type StructureIndex = sbt.StructureIndex

	@deprecated("LoadBuildConfiguration is now top-level", "0.13.0")
	type LoadBuildConfiguration = sbt.LoadBuildConfiguration
	@deprecated("LoadBuildConfiguration is now top-level", "0.13.0")	
	val LoadBuildConfiguration = sbt.LoadBuildConfiguration

	final class EvaluatedConfigurations(val eval: Eval, val settings: Seq[Setting[_]])
	final case class InjectSettings(global: Seq[Setting[_]], project: Seq[Setting[_]], projectLoaded: ClassLoader => Seq[Setting[_]])

	@deprecated("LoadedDefinitions is now top-level", "0.13.0")
	type LoadedDefinitions = sbt.LoadedDefinitions
	@deprecated("LoadedPlugins is now top-level", "0.13.0")
	type LoadedPlugins = sbt.LoadedPlugins
	@deprecated("BuildUnit is now top-level", "0.13.0")
	type BuildUnit = sbt.BuildUnit
	@deprecated("LoadedBuild is now top-level", "0.13.0")
	type LoadedBuild = sbt.LoadedBuild
	@deprecated("PartBuild is now top-level", "0.13.0")
	type PartBuild = sbt.PartBuild
	@deprecated("BuildUnitBase is now top-level", "0.13.0")
	type BuildUnitBase = sbt.BuildUnitBase
	@deprecated("PartBuildUnit is now top-level", "0.13.0")
	type PartBuildUnit = sbt.PartBuildUnit
	@deprecated("Use BuildUtil.apply", "0.13.0")
	def buildUtil(root: URI, units: Map[URI, LoadedBuildUnit], keyIndex: KeyIndex, data: Settings[Scope]): BuildUtil[ResolvedProject] = BuildUtil(root, units, keyIndex, data)
}

final case class LoadBuildConfiguration(stagingDirectory: File, classpath: Seq[Attributed[File]], loader: ClassLoader,
	compilers: Compilers, evalPluginDef: (BuildStructure, State) => PluginData, definesClass: DefinesClass,
	delegates: LoadedBuild => Scope => Seq[Scope], scopeLocal: ScopeLocal,
	pluginManagement: PluginManagement, injectSettings: Load.InjectSettings, globalPlugin: Option[GlobalPlugin], extraBuilds: Seq[URI],
	log: Logger)
{
	lazy val (globalPluginClasspath, globalPluginLoader) = Load.pluginDefinitionLoader(this, Load.globalPluginClasspath(globalPlugin))
	lazy val globalPluginNames = if(globalPluginClasspath.isEmpty) Nil else Load.getPluginNames(globalPluginClasspath, globalPluginLoader)
}

final class IncompatiblePluginsException(msg: String, cause: Throwable) extends Exception(msg, cause)