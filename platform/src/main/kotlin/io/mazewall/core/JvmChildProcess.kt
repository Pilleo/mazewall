package io.mazewall.core

import io.mazewall.MazewallInternal
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
)

@MazewallInternal
public data class ChildStdoutPump(
    val ready: CountDownLatch,
    val thread: Thread,
)

/**
 * Shared `java` command-line construction for mazewall child JVMs.
 */
@MazewallInternal
public object JvmChildProcess {
    public fun commandLine(spec: JvmChildSpec): List<String> {
        val javaBin = System.getProperty("java.home") + "/bin/java"
        val classpath = System.getProperty("java.class.path")
        val args = mutableListOf<String>()
        args.add(javaBin)
        if (spec.enableNativeAccess) {
            args.add("--enable-native-access=ALL-UNNAMED")
        }
        args.add("-Xmx" + spec.maxHeap.removePrefix("-Xmx"))
        if (spec.disableJvmci) {
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

    public fun startStdoutPump(
        process: Process,
        readySentinel: String?,
        onLine: (String) -> Unit,
        threadName: String,
        onStreamClosed: () -> Unit = {},
    ): ChildStdoutPump {
        val ready = CountDownLatch(1)
        val thread =
            Thread {
                try {
                    val reader = process.inputStream.bufferedReader()
                    while (true) {
                        val line = reader.readLine() ?: break
                        if (readySentinel != null && line.contains(readySentinel)) {
                            ready.countDown()
                        }
                        onLine(line)
                    }
                } catch (_: IOException) {
                    // Child closed stdout.
                } finally {
                    onStreamClosed()
                }
            }.apply {
                isDaemon = true
                name = threadName
            }
        thread.start()
        return ChildStdoutPump(ready, thread)
    }

    public fun awaitReady(
        pump: ChildStdoutPump,
        timeoutSeconds: Long,
    ): Boolean = pump.ready.await(timeoutSeconds, TimeUnit.SECONDS)

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
