package io.mazewall.platform.process

import io.mazewall.platform.lifecycle.MazewallInternal
import java.io.BufferedReader
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Which `-javaagent:` flags from this JVM to copy onto a child. */
@MazewallInternal
public sealed interface JavaAgentSelection {
    public data object None : JavaAgentSelection

    public data object All : JavaAgentSelection

    public data object JacocoOnly : JavaAgentSelection
}

/**
 * Arguments for spawning a sibling HotSpot JVM (daemon or portal worker).
 */
@MazewallInternal
public data class JvmChildSpec(
    val mainClass: String,
    val mainArgs: List<String> = emptyList(),
    val maxHeap: String = "64m",
    val extraJvmArgs: List<String> = emptyList(),
    val javaAgents: JavaAgentSelection = JavaAgentSelection.All,
    val enableNativeAccess: Boolean = true,
    val disableJvmci: Boolean = true,
    /**
     * Child `-cp`. `null` copies this JVM's `java.class.path` (daemons).
     * Portal workers must pass a curated classpath so guest classes are not
     * taken from the broker runtime.
     */
    val classpath: String? = null,
)

@MazewallInternal
public data class ChildStdoutPump(
    val ready: CountDownLatch,
    val thread: Thread,
    internal val readinessObserved: java.util.concurrent.atomic.AtomicBoolean = java.util.concurrent.atomic
        .AtomicBoolean(true),
)

/**
 * Shared `java` command-line construction for mazewall child JVMs.
 */
@MazewallInternal
internal object JvmChildProcess {
    public fun commandLine(spec: JvmChildSpec): List<String> {
        val javaBin = System.getProperty("java.home") + "/bin/java"
        val classpath = spec.classpath ?: System.getProperty("java.class.path")
        val args = mutableListOf<String>()
        args.add(javaBin)
        if (spec.enableNativeAccess) {
            args.add("--enable-native-access=ALL-UNNAMED")
        }
        args.add("-Xmx" + spec.maxHeap.removePrefix("-Xmx"))
        if (spec.disableJvmci) {
            // UnlockExperimentalVMOptions must precede the flags it unlocks. On
            // GraalVM-flavored JDKs these are product options and the unlock is a
            // no-op; on standard OpenJDK builds (Temurin CI image) mentioning
            // EnableJVMCI without prior unlock aborts JVM startup entirely.
            args.add("-XX:+UnlockExperimentalVMOptions")
            args.add("-XX:-EnableJVMCI")
            args.add("-XX:-UseJVMCICompiler")
        }
        args.addAll(selectedJavaAgents(spec.javaAgents))
        args.addAll(spec.extraJvmArgs)
        args.add("-cp")
        args.add(classpath)
        args.add(spec.mainClass)
        args.addAll(spec.mainArgs)
        return args
    }

    public fun start(
        launcher: ProcessLauncher,
        spec: JvmChildSpec,
        redirectErrorStream: Boolean = true,
    ): Process = launcher.startProcess(commandLine(spec), redirectErrorStream)

    /** Explicit trusted operator preparation; no environment selection or copying is owned here. */
    public fun start(
        launcher: ProcessLauncher,
        spec: JvmChildSpec,
        redirectErrorStream: Boolean = true,
        preparation: JvmLaunchPreparation,
    ): Process = launcher.startProcess(commandLine(spec), redirectErrorStream, preparation)

    public fun startStdoutPump(
        process: Process,
        readySentinel: String?,
        onLine: (String) -> Unit,
        threadName: String,
        onStreamClosed: () -> Unit = {},
    ): ChildStdoutPump = startStdoutPump(process, readySentinel, onLine, threadName, onStreamClosed, BufferedReader::readLine)

    internal fun startBoundedStdoutPump(
        process: Process,
        readySentinel: String,
        maxLineChars: Int,
        onLine: (String) -> Unit,
        onOversizedLine: () -> Unit,
        threadName: String,
        onStreamClosed: () -> Unit,
    ): ChildStdoutPump {
        require(maxLineChars > 0)
        val boundedReader = BoundedOutputReader(maxLineChars, onOversizedLine)
        return startStdoutPump(process, readySentinel, onLine, threadName, onStreamClosed, boundedReader::readLine)
    }

    private fun startStdoutPump(
        process: Process,
        readySentinel: String?,
        onLine: (String) -> Unit,
        threadName: String,
        onStreamClosed: () -> Unit,
        readLine: (BufferedReader) -> String?,
    ): ChildStdoutPump {
        val ready = CountDownLatch(1)
        val observed = java.util.concurrent.atomic
            .AtomicBoolean(false)
        val thread =
            Thread {
                try {
                    process.inputStream.bufferedReader().use { reader ->
                        while (true) {
                            val line = readLine(reader) ?: break
                            if (readySentinel != null && line.contains(readySentinel)) {
                                observed.set(true)
                                ready.countDown()
                            }
                            onLine(line)
                        }
                    }
                } catch (_: IOException) {
                    // Child closed stdout.
                } finally {
                    ready.countDown()
                    onStreamClosed()
                }
            }.apply {
                isDaemon = true
                name = threadName
            }
        thread.start()
        return ChildStdoutPump(ready, thread, observed)
    }

    private class BoundedOutputReader(
        private val maxLineChars: Int,
        private val onOversizedLine: () -> Unit,
    ) {
        private var skipLineFeed = false

        fun readLine(reader: BufferedReader): String? {
            val line = StringBuilder()
            var oversized = false
            while (true) {
                val next = reader.read()
                if (skipLineFeed) {
                    skipLineFeed = false
                    if (next == '\n'.code) continue
                }
                when (next) {
                    -1 -> return line.takeIf { !oversized && it.isNotEmpty() }?.toString()
                    '\r'.code, '\n'.code -> {
                        skipLineFeed = next == '\r'.code
                        if (!oversized) return line.toString()
                        line.setLength(0)
                        oversized = false
                    }
                    else -> {
                        if (oversized) continue
                        if (line.length < maxLineChars) {
                            line.append(next.toChar())
                        } else {
                            oversized = true
                            onOversizedLine()
                        }
                    }
                }
            }
        }
    }

    public fun awaitReady(
        pump: ChildStdoutPump,
        timeoutSeconds: Long,
    ): Boolean = pump.ready.await(timeoutSeconds, TimeUnit.SECONDS) && pump.readinessObserved.get()

    public fun stripInheritedJvmOptions(env: MutableMap<String, String>) {
        INHERITED_JVM_OPTION_KEYS.forEach { env.remove(it) }
    }

    internal fun selectedJavaAgents(selection: JavaAgentSelection): List<String> {
        val jvmArgs =
            java.lang.management.ManagementFactory
                .getRuntimeMXBean()
                .inputArguments
        val agents = jvmArgs.filter { it.startsWith("-javaagent:") }
        return when (selection) {
            is JavaAgentSelection.None -> emptyList()
            is JavaAgentSelection.All -> agents
            is JavaAgentSelection.JacocoOnly -> agents.filter { it.contains("jacoco") }
        }
    }

    private val INHERITED_JVM_OPTION_KEYS =
        listOf("JAVA_TOOL_OPTIONS", "_JAVA_OPTIONS", "JDK_JAVA_OPTIONS")
}
