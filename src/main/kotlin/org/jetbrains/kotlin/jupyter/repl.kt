package org.jetbrains.kotlin.jupyter

import jupyter.kotlin.*
import jupyter.kotlin.KotlinContext
import kotlinx.coroutines.runBlocking
import org.jetbrains.kotlin.config.KotlinCompilerVersion
import org.jetbrains.kotlin.jupyter.repl.completion.CompletionResult
import org.jetbrains.kotlin.jupyter.repl.completion.KotlinCompleter
import org.jetbrains.kotlin.jupyter.repl.completion.ListErrorsResult
import org.jetbrains.kotlin.jupyter.repl.completion.SourceCodeImpl
import org.jetbrains.kotlin.jupyter.repl.reflect.ContextUpdater
import org.jetbrains.kotlin.jupyter.repl.spark.ClassWriter
import org.jetbrains.kotlin.scripting.ide_services.compiler.KJvmReplCompilerWithIdeServices
import org.jetbrains.kotlin.scripting.resolve.skipExtensionsResolutionForImplicitsExceptInnermost
import java.io.File
import java.net.URLClassLoader
import java.util.*
import kotlin.script.dependencies.ScriptContents
import kotlin.script.experimental.api.*
import kotlin.script.experimental.host.withDefaultsFrom
import kotlin.script.experimental.jvm.*
import kotlin.script.experimental.jvm.util.isError
import kotlin.script.experimental.jvm.util.isIncomplete
import kotlin.script.experimental.jvm.util.toSourceCodePosition

data class EvalResult(val resultValue: Any?)

data class CheckResult(val isComplete: Boolean = true)

open class ReplException(message: String, cause: Throwable? = null) : Exception(message, cause)

class ReplEvalRuntimeException(message: String, cause: Throwable? = null) : ReplException(message, cause)

class ReplCompilerException(errorResult: ResultWithDiagnostics.Failure? = null, message: String? = null)
    : ReplException(message ?: errorResult?.getErrors() ?: "") {

    val firstDiagnostics = errorResult?.reports?.firstOrNull {
        it.severity == ScriptDiagnostic.Severity.ERROR || it.severity == ScriptDiagnostic.Severity.FATAL
    }

    constructor(message: String): this(null, message)
}

enum class ExecutedCodeLogging {
    Off,
    All,
    Generated
}

interface ReplOptions {
    var trackClasspath: Boolean

    var executedCodeLogging: ExecutedCodeLogging

    var writeCompiledClasses: Boolean

    var outputConfig: OutputConfig
}

typealias TypeName = String
typealias Code = String

interface ReplForJupyter {
    fun eval(code: Code, displayHandler: ((Any) -> Unit)? = null, jupyterId: Int = -1): EvalResult

    fun checkComplete(code: Code): CheckResult

    suspend fun complete(code: String, cursor: Int, callback: (CompletionResult) -> Unit)

    suspend fun listErrors(code: String, callback: (ListErrorsResult) -> Unit)

    val currentClasspath: Collection<String>

    val resolverConfig: ResolverConfig?

    var outputConfig: OutputConfig
}

class ReplForJupyterImpl(private val scriptClasspath: List<File> = emptyList(),
                         override val resolverConfig: ResolverConfig? = null, vararg scriptReceivers: Any) : ReplForJupyter, ReplOptions, KotlinKernelHost {

    private var outputConfigImpl = OutputConfig()

    override var outputConfig
        get() = outputConfigImpl
        set(value) {
            // reuse output config instance, because it is already passed to CapturingOutputStream and stream parameters should be updated immediately
            outputConfigImpl.update(value)
        }

    override var trackClasspath: Boolean = false

    override var executedCodeLogging: ExecutedCodeLogging = ExecutedCodeLogging.Off

    private var classWriter: ClassWriter? = null

    override var writeCompiledClasses: Boolean
        get() = classWriter != null
        set(value) {
            classWriter = if (!value) null
            else {
                val cw = ClassWriter()
                System.setProperty("spark.repl.class.outputDir", cw.outputDir.toString())
                cw
            }
        }

    private val resolver = JupyterScriptDependenciesResolver(resolverConfig)

    private val typeRenderers = mutableMapOf<String, String>()

    private val initCellCodes = mutableListOf<String>()

    private fun renderResult(value: Any?, resultField: Pair<String, KotlinType>?): Any? {
        if (value == null || resultField == null) return null
        val code = typeRenderers[value.javaClass.canonicalName]?.replace("\$it", resultField.first)
                ?: return value
        val result = doEval(code)
        return renderResult(result.value, result.resultField)
    }

    data class PreprocessingResult(val code: Code, val initCodes: List<Code>, val initCellCodes: List<Code>, val typeRenderers: List<TypeHandler>)

    fun preprocessCode(code: String): PreprocessingResult {

        val processedMagics = magics.processMagics(code)

        val initCodes = mutableListOf<Code>()
        val initCellCodes = mutableListOf<Code>()
        val typeRenderers = mutableListOf<TypeHandler>()
        val typeConverters = mutableListOf<TypeHandler>()
        val annotations = mutableListOf<TypeHandler>()

        processedMagics.libraries.forEach { libraryDefinition ->
            val builder = StringBuilder()
            libraryDefinition.repositories.forEach { builder.appendLine("@file:Repository(\"$it\")") }
            libraryDefinition.dependencies.forEach { builder.appendLine("@file:DependsOn(\"$it\")") }
            libraryDefinition.imports.forEach { builder.appendLine("import $it") }
            if (builder.isNotBlank())
                initCodes.add(builder.toString())
            typeRenderers.addAll(libraryDefinition.renderers)
            typeConverters.addAll(libraryDefinition.converters)
            annotations.addAll(libraryDefinition.annotations)
            libraryDefinition.init.forEach {

                // Library init code may contain other magics, so we process them recursively
                val preprocessed = preprocessCode(it)
                initCodes.addAll(preprocessed.initCodes)
                typeRenderers.addAll(preprocessed.typeRenderers)
                initCellCodes.addAll(preprocessed.initCellCodes)
                if (preprocessed.code.isNotBlank())
                    initCodes.add(preprocessed.code)
            }
        }

        val declarations = (typeConverters.map { typeProvidersProcessor.register(it) } + annotations.map { annotationsProcessor.register(it) })
                .joinToString("\n")
        if (declarations.isNotBlank()) {
            initCodes.add(declarations)
        }

        return PreprocessingResult(processedMagics.code, initCodes, initCellCodes, typeRenderers)
    }

    private val ctx = KotlinContext()

    private val receivers: List<Any> = scriptReceivers.asList()

    private val magics = MagicsProcessor(this, LibrariesProcessor(resolverConfig?.libraries))

    private fun configureMavenDepsOnAnnotations(context: ScriptConfigurationRefinementContext): ResultWithDiagnostics<ScriptCompilationConfiguration> {
        val annotations = context.collectedData?.get(ScriptCollectedData.foundAnnotations)?.takeIf { it.isNotEmpty() }
                ?: return context.compilationConfiguration.asSuccess()
        val scriptContents = object : ScriptContents {
            override val annotations: Iterable<Annotation> = annotations
            override val file: File? = null
            override val text: CharSequence? = null
        }
        return try {
            resolver.resolveFromAnnotations(scriptContents)
                    .onSuccess { classpath ->
                        context.compilationConfiguration
                                .let { if (classpath.isEmpty()) it else it.withUpdatedClasspath(classpath) }
                                .asSuccess()
                    }
        } catch (e: Throwable) {
            ResultWithDiagnostics.Failure(e.asDiagnostics(path = context.script.locationId))
        }
    }

    private val compilerConfiguration by lazy {
        ScriptCompilationConfiguration {
            hostConfiguration.update { it.withDefaultsFrom(defaultJvmScriptingHostConfiguration) }
            baseClass.put(KotlinType(ScriptTemplateWithDisplayHelpers::class))
            fileExtension.put("jupyter.kts")

            val classImports = listOf(
                    DependsOn::class,
                    Repository::class,
                    ScriptTemplateWithDisplayHelpers::class,
            ).map { it.java.name }
            defaultImports(classImports + defaultGlobalImports)

            jvm {
                updateClasspath(scriptClasspath)
            }
            refineConfiguration {
                onAnnotations(DependsOn::class, Repository::class, handler = { configureMavenDepsOnAnnotations(it) })
            }

            val receiversTypes = receivers.map { KotlinType(it.javaClass.canonicalName) }
            implicitReceivers(receiversTypes)
            skipExtensionsResolutionForImplicitsExceptInnermost(receiversTypes)

            compilerOptions(
                    "-jvm-target", runtimeProperties.jvmTargetForSnippets,
                    "-no-stdlib"
            )
        }
    }

    private val ScriptCompilationConfiguration.classpath
        get() = this[ScriptCompilationConfiguration.dependencies]
                ?.filterIsInstance<JvmDependency>()
                ?.flatMap { it.classpath }
                .orEmpty()

    override val currentClasspath = mutableSetOf<String>().also { filePaths ->
        filePaths.addAll(compilerConfiguration.classpath.map { it.canonicalPath })
    }

    private class FilteringClassLoader(parent: ClassLoader, val includeFilter: (String) -> Boolean) : ClassLoader(parent) {
        override fun loadClass(name: String?, resolve: Boolean): Class<*> {
            val c = if (name != null && includeFilter(name))
                parent.loadClass(name)
            else parent.parent.loadClass(name)
            if (resolve)
                resolveClass(c)
            return c
        }
    }

    private val evaluatorConfiguration = ScriptEvaluationConfiguration {
        implicitReceivers.invoke(v = receivers)
        jvm {
            val filteringClassLoader = FilteringClassLoader(ClassLoader.getSystemClassLoader()) {
                it.startsWith("jupyter.kotlin.") || it.startsWith("kotlin.") || (it.startsWith("org.jetbrains.kotlin.") && !it.startsWith("org.jetbrains.kotlin.jupyter."))
            }
            val scriptClassloader = URLClassLoader(scriptClasspath.map { it.toURI().toURL() }.toTypedArray(), filteringClassLoader)
            baseClassLoader(scriptClassloader)
        }
        constructorArgs()
    }

    private var executionCounter = 0

    private val compiler: KJvmReplCompilerWithIdeServices by lazy {
        KJvmReplCompilerWithIdeServices()
    }

    private val evaluator: BasicJvmReplEvaluator by lazy {
        BasicJvmReplEvaluator()
    }

    private val completer = KotlinCompleter()

    private val contextUpdater = ContextUpdater(ctx, evaluator)

    private val typeProvidersProcessor: TypeProvidersProcessor = TypeProvidersProcessorImpl(contextUpdater)

    private val annotationsProcessor: AnnotationsProcessor = AnnotationsProcessorImpl(contextUpdater)

    private var currentDisplayHandler: ((Any) -> Unit)? = null

    private val scheduledExecutions = LinkedList<Code>()

    override fun checkComplete(code: String): CheckResult {
        val id = executionCounter++
        val codeLine = SourceCodeImpl(id, code)
        val result = runBlocking { compiler.analyze(codeLine, 0.toSourceCodePosition(codeLine), compilerConfiguration) }
        return when {
            result.isIncomplete() -> CheckResult(false)
            result.isError() -> throw ReplException(result.getErrors())
            else -> CheckResult(true)
        }
    }

    private fun executeInitCellCode() = initCellCodes.forEach(::evalNoReturn)

    private fun executeInitCode(p: PreprocessingResult) = p.initCodes.forEach(::evalNoReturn)

    private fun executeScheduledCode() {
        while (scheduledExecutions.isNotEmpty()) {
            val code = scheduledExecutions.pop()
            if (executedCodeLogging == ExecutedCodeLogging.Generated)
                println(code)
            evalNoReturn(code)
        }
    }

    private fun processVariablesConversion() {
        var iteration = 0
        do {
            if (iteration++ > 10) {
                log.error("Execution loop in type providers processing")
                break
            }
            val codes = typeProvidersProcessor.process()
            codes.forEach {
                if (executedCodeLogging == ExecutedCodeLogging.Generated)
                    println(it)
                evalNoReturn(it)
            }
        } while (codes.isNotEmpty())
    }

    private fun processAnnotations(replLine: Any?) {
        if (replLine == null) return
        log.catchAll {
            annotationsProcessor.process(replLine)
        }?.forEach {
            if (executedCodeLogging == ExecutedCodeLogging.Generated)
                println(it)
            evalNoReturn(it)
        }
    }

    private fun registerNewLibraries(p: PreprocessingResult) {
        p.initCellCodes.filter { !initCellCodes.contains(it) }.let(initCellCodes::addAll)
        typeRenderers.putAll(p.typeRenderers.map { it.className to it.code })
    }

    private fun lastReplLine() = evaluator.lastEvaluatedSnippet?.get()?.result?.scriptInstance

    override fun eval(code: String, displayHandler: ((Any) -> Unit)?, jupyterId: Int): EvalResult {
        synchronized(this) {
            try {

                currentDisplayHandler = displayHandler

                executeInitCellCode()

                val preprocessed = preprocessCode(code)

                executeInitCode(preprocessed)

                var result: Any? = null
                var resultField: Pair<String, KotlinType>? = null
                var replLine: Any? = null

                if (preprocessed.code.isNotBlank()) {
                    doEval(preprocessed.code).let {
                        result = it.value
                        resultField = it.resultField
                    }
                    replLine = lastReplLine()
                }

                log.catchAll {
                    processAnnotations(replLine)
                }

                log.catchAll {
                    executeScheduledCode()
                }

                log.catchAll {
                    registerNewLibraries(preprocessed)
                }

                log.catchAll {
                    processVariablesConversion()
                }

                log.catchAll {
                    executeScheduledCode()
                }

                log.catchAll {
                    updateOutputList(jupyterId, result)
                }

                log.catchAll {
                    updateClasspath()
                }

                result = renderResult(result, resultField)

                return EvalResult(result)

            } finally {
                currentDisplayHandler = null
                scheduledExecutions.clear()
            }
        }
    }

    private fun updateOutputList(jupyterId: Int, result: Any?) {
        if (jupyterId >= 0) {
            while (ReplOutputs.count() <= jupyterId) ReplOutputs.add(null)
            ReplOutputs[jupyterId] = result
        }
    }

    private fun updateClasspath() {
        val resolvedClasspath = resolver.popAddedClasspath().map { it.canonicalPath }
        if (resolvedClasspath.isNotEmpty()) {

            val newClasspath = resolvedClasspath.filter { !currentClasspath.contains(it) }
            val oldClasspath = resolvedClasspath.filter { currentClasspath.contains(it) }
            currentClasspath.addAll(newClasspath)
            if (trackClasspath) {
                val sb = StringBuilder()
                if (newClasspath.count() > 0) {
                    sb.appendLine("${newClasspath.count()} new paths were added to classpath:")
                    newClasspath.sortedBy { it }.forEach { sb.appendLine(it) }
                }
                if (oldClasspath.count() > 0) {
                    sb.appendLine("${oldClasspath.count()} resolved paths were already in classpath:")
                    oldClasspath.sortedBy { it }.forEach { sb.appendLine(it) }
                }
                sb.appendLine("Current classpath size: ${currentClasspath.count()}")
                println(sb.toString())
            }
        }
    }

    private val completionQueue = LockQueue<CompletionResult, CompletionArgs>()
    override suspend fun complete(code: String, cursor: Int, callback: (CompletionResult) -> Unit) = doWithLock(CompletionArgs(code, cursor, callback), completionQueue, CompletionResult.Empty(code, cursor)) {
        //val preprocessed = preprocessCode(code)
        completer.complete(compiler, compilerConfiguration, code, executionCounter++, cursor)
    }

    private val listErrorsQueue = LockQueue<ListErrorsResult, ListErrorsArgs>()
    override suspend fun listErrors(code: String, callback: (ListErrorsResult) -> Unit) = doWithLock(ListErrorsArgs(code, callback), listErrorsQueue, ListErrorsResult(code)) {
        //val preprocessed = preprocessCode(code)
        val codeLine = SourceCodeImpl(executionCounter++, code)
        val errorsList = runBlocking { compiler.analyze(codeLine, 0.toSourceCodePosition(codeLine), compilerConfiguration) }
        ListErrorsResult(code, errorsList.valueOrThrow()[ReplAnalyzerResult.analysisDiagnostics]!!)
    }

    private fun <T, Args: LockQueueArgs<T>> doWithLock(args: Args, queue: LockQueue<T, Args>, default: T, action: (Args) -> T) {
        queue.add(args)

        val result = synchronized(this) {
            val lastArgs = queue.get()
            if (lastArgs != args)
                default
            else
                action(args)
        }
        args.callback(result)
    }

    private fun evalNoReturn(code: String) {
        doEval(code)
        processAnnotations(lastReplLine())
    }

    private data class InternalEvalResult(val value: Any?, val resultField: Pair<String, KotlinType>?)

    private interface LockQueueArgs <T> {
        val callback: (T) -> Unit
    }

    private data class CompletionArgs(val code: String, val cursor: Int, override val callback: (CompletionResult) -> Unit) : LockQueueArgs<CompletionResult>
    private data class ListErrorsArgs(val code: String, override val callback: (ListErrorsResult) -> Unit) : LockQueueArgs<ListErrorsResult>

    private class LockQueue<T, Args: LockQueueArgs<T>> {
        private var args: Args? = null

        fun add(args: Args) {
            synchronized(this) {
                this.args = args
            }
        }

        fun get(): Args {
            return args!!
        }
    }

    private fun doEval(code: String): InternalEvalResult {
        if (executedCodeLogging == ExecutedCodeLogging.All)
            println(code)
        val id = executionCounter++
        val codeLine = SourceCodeImpl(id, code)
        when (val compileResultWithDiagnostics = runBlocking { compiler.compile(codeLine, compilerConfiguration) }) {
            is ResultWithDiagnostics.Success -> {
                val compileResult = compileResultWithDiagnostics.value
                classWriter?.writeClasses(codeLine, compileResult.get())
                val repl = this
                val currentEvalConfig = ScriptEvaluationConfiguration(evaluatorConfiguration) {
                    constructorArgs.invoke(repl as KotlinKernelHost)
                }
                val resultWithDiagnostics = runBlocking { evaluator.eval(compileResult, currentEvalConfig) }
                contextUpdater.update()

                when(resultWithDiagnostics) {
                    is ResultWithDiagnostics.Success -> {
                        val pureResult = resultWithDiagnostics.value.get()
                        return when (val resultValue = pureResult.result) {
                            is ResultValue.Error -> throw ReplEvalRuntimeException(resultValue.error.message.orEmpty(), resultValue.error)
                            is ResultValue.Unit -> {
                                InternalEvalResult(Unit, null)
                            }
                            is ResultValue.Value -> {
                                InternalEvalResult(resultValue.value, pureResult.compiledSnippet.resultField)
                            }
                            is ResultValue.NotEvaluated -> {
                                throw ReplEvalRuntimeException(buildString {
                                    val cause = resultWithDiagnostics.reports.firstOrNull()?.exception
                                    val stackTrace = cause?.stackTrace.orEmpty()
                                    append("This snippet was not evaluated: ")
                                    appendLine(cause.toString())
                                    for (s in stackTrace)
                                        appendLine(s)
                                })
                            }
                            else -> throw IllegalStateException("Unknown eval result type $this")
                        }
                    }
                    is ResultWithDiagnostics.Failure -> {
                        throw ReplCompilerException(resultWithDiagnostics)
                    }
                    else -> throw IllegalStateException("Unknown result")
                }

            }
            is ResultWithDiagnostics.Failure -> throw ReplCompilerException(compileResultWithDiagnostics)
        }
    }

    init {
        log.info("Starting kotlin REPL engine. Compiler version: ${KotlinCompilerVersion.VERSION}")
        log.info("Classpath used in script: $scriptClasspath")
    }

    override fun display(value: Any) {
        currentDisplayHandler?.invoke(value)
    }

    override fun scheduleExecution(code: String) {
        scheduledExecutions.add(code)
    }
}

