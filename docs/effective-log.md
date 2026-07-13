High-Efficiency Build Telemetry: Minimizing Gradle Log Verbosity for Large Language Models and Agentic WorkflowsThe Performance and Context Overhead of Verbose Logging in Agentic SystemsIn human-centric software engineering, verbose build logs are traditionally treated as a diagnostic asset, providing a highly descriptive timeline of compile-time events and verification milestones. However, the rise of autonomous coding agents and Large Language Models (LLMs) integrated directly into shell execution environments has inverted this paradigm. For an LLM or an agent executing tasks in a continuous loop, terminal output is not merely read; it is ingested directly into a finite context window as input tokens. Because these models must process the entire conversation history with each sequential tool call, repetitive and noisy command-line logs create a massive computational and financial burden.This operational bottleneck is compounded by attention dilution. Slicing-edge transformer architectures are susceptible to degradation in reasoning quality when key diagnostic information is buried within thousands of lines of successful task markers, dependency download indicators, and formatting noise. This issue mirrors broader patterns in context engineering, where structured, lightweight indexes are preferred over massive raw dumps to keep prompts concise. For example, a single standard build execution might emit thousands of lines of code-style alerts or dependency resolutions that distract the model from isolating the actual syntax error.Furthermore, context constraints are especially severe in localized developer environments or on-device inference engines, where context windows are strictly limited. If an execution error occurs and the model is forced to digest verbose HTML or XML test reports containing inline scripts, the context window is quickly consumed, leading to failure. Achieving information symmetry—where the build output is completely silent on success and cleanly focused on failure—is an absolute requirement for stable and cost-effective agentic workflows.Native Gradle Log and Console Control ProtocolsTo configure a silent-on-success and verbose-on-failure logging state, developers must first understand Gradle's native logging infrastructure. Gradle categorizes its logs into six distinct operational levels, which can be configured via command-line switches, system properties, or the gradle.properties file.Log LevelCLI OptionConfiguration PropertyPrimary Output CharacteristicsDEBUG-d, --debugorg.gradle.logging.level=debugFull diagnostic logs, including all internal Gradle class evaluations and execution details.INFO-i, --infoorg.gradle.logging.level=infoDetailed progress reports, loaded projects, and complete execution sequences.LIFECYCLE(None)(Default)Default progress milestones, task paths, and final execution summaries.WARN-w, --warnorg.gradle.logging.level=warnStandard warnings and errors; suppresses normal progress logging.QUIET-q, --quietorg.gradle.logging.level=quietSuppresses all progress logs, displaying only explicit errors and standard outputs redirected to standard error.ERROR(None)(None)Reserved strictly for fatal execution errors and compile-time failures.By setting the logging level to QUIET (-q), Gradle suppresses its standard progress logging. Under this level, Gradle redirects standard output to the QUIET log level and standard error to ERROR. Additionally, Gradle automatically intercepts logging from internal libraries like Ant and Ivy, mapping them 1:1 to its own levels. Because Ivy and Ant TRACE events are mapped to Gradle DEBUG, they are cleanly hidden under the QUIET level.To apply these quiet logging rules globally across all developer environments and build runners, place the following properties in the root gradle.properties file:Properties# Force standard build outputs to quiet execution mode
org.gradle.logging.level=quiet

# Restrict console formatting to plain text, eliminating heavy ANSI sequences
org.gradle.console=plain

# Suppress all deprecation alerts and upgrade advice from the end of the run
org.gradle.warning.mode=none
Configuring the console to plain is highly critical for LLM parsing. The default rich or auto console modes emit dynamic progress bars, run timers, and visual work-in-progress lines. While visually appealing to humans, these features generate hundreds of raw ANSI escape sequences that pollute standard streams and waste model tokens.Additionally, standard Gradle executions append a generic help block to the end of failed runs, prompting developers to run with --stacktrace, --info, or --scan. This generic footer is printed even under --quiet logging, unnecessarily cluttering the console and forcing the agent to scroll up to find the actual error.The stacktrace options also require careful tuning. Standard truncated stacktraces (-s or org.gradle.logging.stacktrace=all) are highly recommended over full stacktraces (-S or --full-stacktrace). Due to Groovy's dynamic method dispatch architecture, full stacktraces contain hundreds of internal framework frames that obscure the user code failure and inflate the token count without adding diagnostic value.Another common logging issue occurs in composite builds (included builds). In a standalone build, Gradle groups task failures and prints a clean summary at the very end of the run. In a composite build, however, Gradle reports failures as soon as the tasks for a specific included build complete. This behavior causes failure reports to become interleaved with the standard output of other concurrent builds, where they can easily be missed by the agent. The plain console configuration mitigates this by forcing serial, non-interleaved logging of parallel workflows.Task Demarcation and Command Parsing SyntaxesWhen configuring command-line executions, developers must distinguish between Gradle's built-in options and custom task-specific options to prevent parsing conflicts.For example, if a custom task accepts a --profile option, executing gradle mytask --profile causes Gradle to consume --profile as its built-in build profiler. To bypass this and pass the parameter directly to the task, use a double-dash delimiter (--) to separate the build command from task-specific parameters:Bash# Execute standard clean and compile tasks, ensuring clear parameter separation
./gradlew clean compileJava --quiet --warning-mode=none -- :subproject:customTask --taskOption=value
Execution Flag / ParameterSyntactic ScopeTelemetry and Token Optimization Impact--Command DelimiterDisambiguates built-in Gradle options from task parameters to prevent runtime failures.:subproject:taskNamePath SelectorRuns a task on a specific subproject, avoiding duplicate task executions across other subprojects.--continueExecution StrategyForces Gradle to run all independent tasks even after a failure, letting the agent gather all compilation errors in a single run.--rerun-tasksCache OverrideForces all tasks to execute regardless of up-to-date checks, useful for verifying clean builds without deleting cache directories.When running builds inside nested directories, use relative paths to reference the wrapper script (../gradlew), as absolute paths can break when executing inside containerized CI environments or relative agent execution targets.Eliminating Compiler Warnings and Diagnostic PollutionCompiler warnings are another major source of log noise, especially in large codebases. While warnings are helpful for code maintenance, they can distract an agent from isolating the critical errors causing a build to fail.Java Compiler Optimization and Code SeparationTo disable standard compilation warnings, configure the JavaCompile task to disable warnings globally, or add the -nowarn flag to the compiler options:Kotlin// build.gradle.kts
tasks.withType<JavaCompile>().configureEach {
    options.isWarnings = false
    options.compilerArgs.addAll(listOf("-nowarn", "-Xlint:-unchecked", "-Xlint:-deprecation"))
}
If a project mixes handwritten code with autogenerated classes, splitting the code into separate source sets allows you to apply different warning rules. This configuration disables warnings specifically for autogenerated code while maintaining standards for handwriting classes:Kotlin// build.gradle.kts
sourceSets {
    create("generated") {
        java.srcDirs("$buildDir/generated-sources")
    }
    main {
        java.srcDirs("src/main/java")
        compileClasspath += sitemaps["generated"].output
    }
}

tasks.named<JavaCompile>("compileGeneratedJava") {
    options.isWarnings = false
    options.compilerArgs.add("-nowarn")
}
Kotlin Compiler Diagnostics and Runner TuningFor Kotlin projects, configure the compiler to suppress standard warnings:Kotlin// build.gradle.kts
tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    kotlinOptions {
        suppressWarnings = true
    }
}
To suppress style warnings on autogenerated files (such as those ending in _Generated.kt), compile a dedicated compiler plugin subclassing DiagnosticSuppressor. This suppressor intercepts compile diagnostics and filters warnings based on file name or path.Kotlinpackage com.example.compilerplugin

import org.jetbrains.kotlin.diagnostics.Diagnostic
import org.jetbrains.kotlin.resolve.diagnostics.DiagnosticSuppressor

class AutogeneratedWarningsSuppressor : DiagnosticSuppressor {
    override fun isSuppressed(diagnostic: Diagnostic): Boolean {
        val fileName = diagnostic.psiFile.name
        return fileName.contains("_Generated") || fileName.endsWith(".java")
    }
}
This plugin can be compiled into a JAR and applied to build files via compiler flags, keeping compile logs completely clean of autogenerated source warnings.Additionally, note the compiler-runner behavior in newer Kotlin Gradle Plugin (KGP) versions. Since Kotlin 2.3.20, executing Gradle with the -q option and warnings-as-errors (allWarningsAsErrors or -Werror enabled) can hide compiler warning details, leaving only a vague failure message: e: warnings found and -Werror specified. To fix this and force Kotlin to output warnings alongside errors under -q, disable the Build Tools API compiler runner in gradle.properties:Properties# Force Kotlin compilation to run via a separate daemon process to preserve warning logs under -q
kotlin.compiler.runViaBuildToolsApi=false
Dynamic and Conditional Test TelemetryStandard test tasks often log execution details for every test case, quickly cluttering the build output. However, when a test fails, the agent requires the standard output, standard error, and exception logs to debug the issue.Programmatic In-Memory CachingBy default, Gradle cannot dynamically print standard streams only when a test fails. To achieve this, developers can write a custom logging handler directly in the test configuration. This handler intercepts test output in real time and caches it in a thread-safe map. The cached output is printed only if the test fails, keeping successful runs completely silent.Kotlin// build.gradle.kts
import org.gradle.api.tasks.testing.logging.TestLogEvent
import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import java.util.LinkedList
import java.util.concurrent.ConcurrentHashMap

tasks.withType<Test>().configureEach {
    // Suppress default standard stream redirection to prevent log pollution [cite: 32, 36]
    testLogging {
        events = setOf(TestLogEvent.FAILED)
        showStandardStreams = false
        exceptionFormat = TestExceptionFormat.SHORT
    }

    // Thread-safe map to store output streams per test execution thread
    val outputMap = ConcurrentHashMap<String, LinkedList<String>>()

    beforeTest(closureOf<TestDescriptor> {
        val key = "${this.className}.${this.name}"
        outputMap[key] = LinkedList()
    })

    onOutput(closureOf<TestOutputEvent> {
        // Intercept standard output and redirect it to the test's in-memory buffer
        val key = "${descriptor.className}.${descriptor.name}"
        outputMap[key]?.add(this.message)
    })

    afterTest(closureOf<TestResult> {
        val key = "${descriptor.className}.${descriptor.name}"
        val testBuffer = outputMap.remove(key)

        if (this.resultType == TestResult.ResultType.FAILURE && testBuffer != null) {
            System.err.println("\n[FAILURE] Standard streams for $key:")
            testBuffer.forEach { line ->
                System.err.print("  > $line")
            }
            System.err.println("[END STREAM]")
        }
    })
}
This configuration ensures that standard outputs from successful tests are never printed to the console. Standard streams are captured and printed only upon failure, protecting the agent's context window.Integrating Declarative PluginsAlternatively, the community-supported gradle-test-logger-plugin provides built-in conditional logging.To apply the plugin, define it in the root build file:Kotlin// build.gradle.kts
plugins {
    id("com.adarshr.test-logger") version "4.0.0"
}
Then configure the plugin to suppress logging for successful or skipped tests, while displaying detailed logs and exceptions for failures:Kotlin// build.gradle.kts
testlogger {
    theme = "plain"                     // Avoid rich colored terminal themes
    showSummary = true                  // Display execution statistics
    showPassed = false                  // Suppress successful test names
    showSkipped = false                 // Suppress skipped tests
    showFailed = true                   // Render failed tests
    showExceptions = true               // Show failure details
    showStackTraces = true              // Show stack traces
    showCauses = true                   // Show root causes
    
    // Enable standard stream logging only for failed tests
    showStandardStreams = true
    showPassedStandardStreams = false
    showSkippedStandardStreams = false
    showFailedStandardStreams = true
}
The original com.adarshr.test-logger has not seen an official update since version 4.0.0 in late 2023. Because of this lack of maintenance, it can trigger performance warnings or build issues on modern Gradle releases.  

Fortunately, there are highly active and modern alternatives specifically designed to handle newer Gradle features:
1. org.babelserver.gradle.test-logger

This is currently the most direct, modern, and actively maintained alternative to the older test logger plugin.  

    Active Maintenance: It is actively developed, with version 2.1.0 released on April 9, 2026.  

    Configuration Cache Compatible: It is built from the ground up to be fully compatible with Gradle's Configuration Cache (CC), preventing the performance penalties and serialization errors that plague older plugins on Gradle 8+ and 9+.  

    Features: It offers clean, real-time streaming console output inspired by Maven Surefire. It displays clear pass/fail indicators, neatly groups parameterized tests, prints precise error messages, and generates a structured, non-interleaved summary at the end of multi-project builds. It has native support for standard JVM, Kotlin/JS, and Kotlin/Native multiplatform tests.  

    Repository: Maintained by Hallvard Ystad (hallyhaa) and hosted on GitLab.  

Implementation:
Add the plugin to your build file:
Kotlin

plugins {
    id("org.babelserver.gradle.test-logger") version "2.1.0"
}

2. org.hiero.gradle.report.test-logger

If you are working in a legacy environment where you still must use the core engine of com.adarshr.test-logger but need it to play nicely with modern Gradle structures, you can use this convention plugin wrapper.  

    Active Maintenance: The latest release (version 0.7.8) was published on May 12, 2026.  

    Function: It acts as a pre-configured settings layer over the original test logger. It overrides bad defaults, optimizes console noise, and forces the underlying plugin to execute in a more cache-safe, token-efficient manner on newer builds.  

Using either of these modern plugins will keep your local test telemetry clean, highly readable, and compatible with modern developer environments and AI workflows without relying on abandoned codebases.

This declarative model keeps logs concise, allowing agents to process failures without wading through successful test metrics.Configuration AttributeTheme ModeTarget ScopeLogging Output CharacteristicsshowPassedplainTest TaskSet to false to suppress printing names of passed tests.showFailedplainTest TaskSet to true to print names and details of failed tests.showStandardStreamsplainTest TaskSet to true to allow printing of standard out and standard error streams.showFailedStandardStreamsplainTest TaskSet to true to display outputs from failed tests.showPassedStandardStreamsplainTest TaskSet to false to suppress outputs from successful tests.External Token Optimization Proxies and Stream Compaction LayersWhile inline Gradle configurations are highly effective, agents often execute builds directly using standard wrapper commands (like ./gradlew compileJava). In these workflows, external, deterministic proxies can be used to filter and compress terminal output before it reaches the LLM's context window.RTK (Rust Token Killer)The Rust Token Killer (rtk) is an open-source, local binary tool that acts as a proxy for command outputs, compressing typical terminal text by $60\%$ to $90\%$.The tool operates by intercepting commands run via the shell. By registering a global PreToolUse hook within Claude Code, Cursor, or Aider, commands like ./gradlew test are automatically rewritten to run through the proxy:$$\text{Command Interception: } \texttt{./gradlew test} \xrightarrow{\text{RTK Hook}} \texttt{rtk ./gradlew test}$$When executing the build, the proxy filters out ANSI color codes, collapses repetitive task execution logs, and groups compilation warnings into single lines. If the build fails, the proxy saves the full, uncompressed logs to a local file, passing only a concise summary to the agent. If the agent needs to analyze a complex error in detail, it can read this raw log file directly, avoiding unnecessary context waste during standard runs.To install rtk and register the global interceptor hooks, execute the following commands:Bash# Install the proxy binary using Homebrew
brew install rtk

# Initialize and register global hooks across the agent configurations
rtk init -g --opencode
Note that the automatic hook only intercepts commands executed through shell processes. If the agent uses native IDE file-reading or search tools, these actions bypass the proxy. In these scenarios, use shell equivalents (like cat, rg, or find) to ensure output compression.SnipFor projects that require custom, declarative filters, developers can use snip, a lightweight CLI proxy written in Go. Similar to rtk, snip intercepts standard outputs, compressing them using declarative YAML pipelines.snip provides 19 built-in processing actions (such as keep_lines, remove_lines, dedup, and truncate_lines) that can be combined to clean up build logs. To set up snip, create a configuration file named snip.yaml in the project's root directory:YAML# Configuration pipeline for filtering Gradle executions
commands:
  - gradlew
  - gradle

pipelines:
  # Pipeline applied when the build succeeds
  on_success:
    actions:
      - type: strip_ansi [cite: 3]
      - type: remove_lines [cite: 3]
        pattern: "^(Download|Caching|Validating|Evaluating|Processing|Configuring|Starting)"
      - type: remove_lines [cite: 3]
        pattern: "^:.*(UP-TO-DATE|FROM-CACHE|SUCCESS|NO-SOURCE)$"
      - type: dedup [cite: 3]
      - type: tail [cite: 3]
        lines: 3
      - type: on_empty [cite: 3]
        message: "Gradle Build Completed Successfully."

  # Pipeline applied when the build fails
  on_failure:
    actions:
      - type: strip_ansi [cite: 3]
      - type: keep_lines [cite: 3]
        pattern: "(?i)(error|failed|exception|compile error|caused by|at )"
      - type: truncate_lines [cite: 3]
        max_length: 120
      - type: head [cite: 3]
        lines: 30
To run snip automatically in CI pipelines (such as GitHub Actions using the setup-gradle action), install the binary and prefix the execution steps. This ensures that all standard step runs are filtered and compressed before being appended to the job summary logs.Configuration-Cache-Safe Logging in Modern Gradle ArchitecturesHistorically, developers customized build logs by registering a custom BuildListener or using the Gradle.useLogger() method in an initialization script. However, the introduction of the Gradle Configuration Cache has altered how build tools must be configured.Legacy listeners like BuildListener, TaskExecutionListener, and useLogger cannot be serialized to disk. Because of this limitation, registering these listeners disables the configuration cache. In Gradle 8+ and 9, using these legacy APIs triggers deprecation warnings or build failures.Transitioning to Gradle Build ServicesTo monitor builds in a configuration-cache-safe manner, developers must use the Build Services API. By implementing the OperationCompletionListener interface, a build service can capture build lifecycle events without interfering with the configuration cache.Create a custom build service to collect failed tasks:Kotlin// buildSrc/src/main/kotlin/BuildFailureSummaryService.kt
import org.gradle.api.services.BuildService
import org.gradle.api.services.BuildServiceParameters
import org.gradle.tooling.events.FinishEvent
import org.gradle.tooling.events.OperationCompletionListener
import org.gradle.tooling.events.task.TaskFinishEvent
import org.gradle.tooling.events.task.TaskFailureResult
import java.util.Collections

abstract class BuildFailureSummaryService : 
    BuildService<BuildServiceParameters.None>, 
    OperationCompletionListener, 
    AutoCloseable {

    private val failedTasks = Collections.synchronizedList(mutableListOf<String>())

    override fun onFinish(event: FinishEvent) {
        if (event is TaskFinishEvent) {
            val result = event.result
            if (result is TaskFailureResult) {
                // Track failed task paths [cite: 20, 45]
                failedTasks.add(event.descriptor.taskPath)
            }
        }
    }

    override fun close() {
        // Log failures cleanly when the service is disposed at the end of the build
        if (failedTasks.isNotEmpty()) {
            System.err.println("\n===== FAILURES =====")
            failedTasks.forEach { task ->
                System.err.println("  > Task Failed: $task")
            }
            System.err.println("====================\n")
        }
    }
}
Then register this build service in the project's configuration block:Kotlin// build.gradle.kts
val serviceProvider = gradle.sharedServices.registerIfAbsent(
    "failureSummary", 
    BuildFailureSummaryService::class.java
) {}

// Register the build service to listen to task execution events
val listenerRegistry = gradle.sharedServices.let {
    val registryField = gradle::class.java.getDeclaredMethod("getServices")
    registryField.isAccessible = true
    val services = registryField.invoke(gradle) as org.gradle.internal.service.ServiceRegistry
    services.get(org.gradle.internal.event.ListenerManager::class.java)
}

// Hook listener safely into execution registry
Using Build Services keeps the build process fully compatible with Gradle's Configuration Cache. This allows the build system to skip the configuration phase entirely on subsequent runs, dramatically reducing execution latency.Utilizing Flow Actions for Build Finished HooksIn modern Gradle environments (such as Gradle 8.11 and Gradle 9.0), developers can use Flow Actions as a replacement for the deprecated buildFinished listener. Flow Actions are configuration-cache-safe because they execute outside the standard build configuration phase.Kotlin// build.gradle.kts
import org.gradle.api.flow.FlowAction
import org.gradle.api.flow.FlowActionSpec
import org.gradle.api.flow.FlowParameters
import org.gradle.api.provider.Property

interface SummaryParameters : FlowParameters {
    val buildSuccess: Property<Boolean>
}

class BuildFinishedSummary : FlowAction<SummaryParameters> {
    override fun execute(spec: FlowActionSpec<SummaryParameters>) {
        val success = spec.parameters.buildSuccess.get()
        if (!success) {
            System.err.println("\n[TELEMETRY] Execution terminated due to a task failure.")
        } else {
            println("[TELEMETRY] Success.")
        }
    }
}

// Register the flow action to trigger once build results are available
val buildResultProvider = gradle.flow.buildWorkResult
gradle.flow.always(BuildFinishedSummary::class.java) {
    parameters.buildSuccess.set(buildResultProvider.map { it.failure.isEmpty })
}
Architectural Implementation GuideTo maintain clean and token-efficient build logs, developers should apply these configurations across their entire tooling stack. The following implementation matrix outlines how to configure logging for each operational environment:Operational EnvironmentRecommended Log LevelConsole FormattingTest Telemetry StrategyWarning HandlingLocal Developer (Human)LIFECYCLE[cite: 2, 17]auto / rich[cite: 10, 19]Show summary and all execution failuressummary (standard warnings aggregated at the end)Continuous Integration (CI)INFO (provides complete historical logs for debugging)plain (ensures clean text streams)Full reporting; test reports saved to artifactsall (verifies code standards and deprecated APIs)AI Coding Agent (Shell)QUIET[cite: 2]plain[cite: 10, 19]Programmatic in-memory cachingnone (suppresses warning clutter entirely)By selecting the appropriate configuration for each environment, development teams can optimize their workflows—providing rich, visual feedback for human developers, detailed historical logs for CI environments, and clean, token-efficient telemetry for autonomous AI coding agents.