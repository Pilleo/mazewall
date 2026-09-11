package io.mazewall

import java.io.File
import java.time.Duration
import java.util.concurrent.TimeUnit

/**
 * Standardized utility for running integration tests in an isolated JVM process.
 * This is required for tests that install irreversible seccomp or Landlock filters,
 * preventing them from "poisoning" the shared Gradle test worker thread/process.
 *
 * Do not use [io.mazewall.core.JvmChildProcess] here. Daemon children use a different
 * argv. Isolated children also drop inherited JAVA_TOOL_OPTIONS so GraalVM JVMCI
 * is not enabled (C1 mmap PROT_EXEC EPERM / exit 1).
 *
 * The external waiter owns the child and every observed descendant until bounded
 * cleanup completes, including successful exits. This cooperative test fixture
 * does not contain processes that escape ancestry before they can be observed.
 */
object IsolatedProcessTester {
    private const val DEFAULT_TIMEOUT_SECONDS = 120L
    private const val REAP_TIMEOUT_SECONDS = 2L

    /** Injectable process-tree boundary used by lifecycle contract tests. */
    interface ProcessLifecycleOperations {
        @Throws(InterruptedException::class)
        fun waitFor(
            process: Process,
            timeout: Duration,
        ): Boolean

        fun descendants(process: Process): List<ProcessHandle>

        fun destroy(process: Process)

        fun destroy(handle: ProcessHandle)

        fun destroyForcibly(process: Process)

        fun destroyForcibly(handle: ProcessHandle)

        fun awaitExit(
            process: Process,
            descendants: List<ProcessHandle>,
        ): Boolean
    }

    private class JdkProcessLifecycle(
        private val onDescendantsObserved: (List<ProcessHandle>) -> Unit,
    ) : ProcessLifecycleOperations {
        private val observedDescendants = linkedMapOf<Long, ProcessHandle>()

        override fun waitFor(
            process: Process,
            timeout: Duration,
        ): Boolean {
            val deadline = System.nanoTime() + timeout.toNanos()
            do {
                descendants(process)
                val remaining = deadline - System.nanoTime()
                if (remaining <= 0) return !process.isAlive
                if (process.waitFor(minOf(remaining, TimeUnit.MILLISECONDS.toNanos(10)), TimeUnit.NANOSECONDS)) {
                    descendants(process)
                    return true
                }
            } while (true)
        }

        override fun descendants(process: Process): List<ProcessHandle> {
            process.descendants().use { handles -> handles.forEach { observedDescendants[it.pid()] = it } }
            // Retain authority to discover grandchildren even after their parent
            // has outlived and been reparented away from the isolated JVM.
            observedDescendants.values.toList().forEach { child ->
                child.descendants().use { handles -> handles.forEach { observedDescendants[it.pid()] = it } }
            }
            return observedDescendants.values
                .toList()
                .asReversed()
                .also(onDescendantsObserved)
        }

        override fun destroy(process: Process) {
            process.destroy()
        }

        override fun destroy(handle: ProcessHandle) {
            handle.destroy()
        }

        override fun destroyForcibly(process: Process) {
            process.destroyForcibly()
        }

        override fun destroyForcibly(handle: ProcessHandle) {
            handle.destroyForcibly()
        }

        override fun awaitExit(
            process: Process,
            descendants: List<ProcessHandle>,
        ): Boolean {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(REAP_TIMEOUT_SECONDS)
            var interrupted = Thread.interrupted()
            try {
                while (process.isAlive || descendants.any(ProcessHandle::isAlive)) {
                    val remaining = deadline - System.nanoTime()
                    if (remaining <= 0) return false
                    try {
                        TimeUnit.NANOSECONDS.sleep(minOf(remaining, TimeUnit.MILLISECONDS.toNanos(10)))
                    } catch (_: InterruptedException) {
                        interrupted = true
                    }
                }
                return true
            } finally {
                if (interrupted) Thread.currentThread().interrupt()
            }
        }
    }

    /**
     * Spawns a new JVM process to run the specified [testClassName]'s [main] method with [args].
     * Asserts that the process exits with code 0.
     * [onDescendantsObserved] is a synchronous, nonblocking observation seam for
     * lifecycle fixture barriers; it must not transfer or dispose process ownership.
     */
    fun runIsolatedTest(
        testClassName: String,
        vararg args: String,
        onDescendantsObserved: (List<ProcessHandle>) -> Unit = {},
    ) {
        // Validate configuration before acquiring a child-process resource. A bad
        // test setting must never launch an unowned process.
        val timeout = configuredTimeout()
        val javaHome = System.getProperty("java.home")
        val javaBin = javaHome + File.separator + "bin" + File.separator + "java"
        val classpath = System.getProperty("java.class.path")

        val command = mutableListOf(
            javaBin,
            "-Xmx128m",
            "-XX:+UnlockExperimentalVMOptions",
            "-XX:-EnableJVMCI",
            "-XX:-UseJVMCICompiler",
            // Seccomp process-wide tests must exercise execve in the JVM process itself.
            // JDK 25's posix_spawn helper performs an earlier exec that the NO_EXEC filter
            // correctly denies, hiding the behavior under test.
            "-Djdk.lang.Process.launchMechanism=FORK",
            "-cp",
            classpath,
            "--enable-native-access=ALL-UNNAMED",
            testClassName,
        )
        command.addAll(args)

        val builder = ProcessBuilder(command)
        // Parent Gradle/Graal workers inject EnableJVMCIProduct via these.
        // Isolated children inherit them and then crash in C1 mmap(PROT_EXEC).
        io.mazewall.core.JvmChildProcess
            .stripInheritedJvmOptions(builder.environment())
        builder.inheritIO()
        val process = builder.start()
        verifyLifecycleForTesting(process, timeout, command, JdkProcessLifecycle(onDescendantsObserved))
    }

    /**
     * Owns [process] through post-start verification and unconditional cleanup.
     * Original failures retain identity; cleanup failures are suppressed and
     * interruption is restored only after all bounded cleanup attempts finish.
     */
    fun verifyLifecycleForTesting(
        process: Process,
        timeout: Duration,
        command: List<String>,
        lifecycle: ProcessLifecycleOperations,
    ) {
        var failure: Throwable? = null
        var interrupted = Thread.interrupted()
        try {
            if (interrupted) throw InterruptedException("Isolated test waiter was already interrupted")
            if (!lifecycle.waitFor(process, timeout)) {
                failure = IllegalStateException(
                    "Isolated test process exceeded ${timeout.seconds}s and was terminated.\n" +
                        "Command: ${command.joinToString(" ")}",
                )
            } else {
                val exitCode = process.exitValue()
                if (exitCode != 0) {
                    failure = IllegalStateException(
                        "Isolated test process failed with exit code $exitCode.\nCommand: ${command.joinToString(" ")}",
                    )
                }
            }
        } catch (caught: Throwable) {
            interrupted = interrupted || caught is InterruptedException
            failure = if (caught is InterruptedException) {
                IllegalStateException("Interrupted while waiting for isolated test process", caught)
            } else {
                caught
            }
        } finally {
            interrupted = Thread.interrupted() || interrupted
            try {
                terminateTree(process, lifecycle)
            } catch (cleanupFailure: Throwable) {
                if (failure == null) {
                    failure = cleanupFailure
                } else if (failure !== cleanupFailure) {
                    failure.addSuppressed(cleanupFailure)
                }
            } finally {
                interrupted = Thread.interrupted() || interrupted
                if (interrupted) Thread.currentThread().interrupt()
            }
        }
        failure?.let { throw it }
    }

    private fun configuredTimeout(): Duration {
        val raw = System.getProperty("io.mazewall.isolatedTestTimeoutSeconds") ?: return Duration.ofSeconds(DEFAULT_TIMEOUT_SECONDS)
        val seconds = raw.toLongOrNull()
            ?: throw IllegalArgumentException("io.mazewall.isolatedTestTimeoutSeconds must be an integer")
        require(seconds in 1..3600) { "io.mazewall.isolatedTestTimeoutSeconds must be between 1 and 3600" }
        return Duration.ofSeconds(seconds)
    }

    private fun terminateTree(
        process: Process,
        lifecycle: ProcessLifecycleOperations,
    ) {
        var failure: Throwable? = null
        var interrupted = Thread.interrupted()

        fun attempt(action: () -> Unit) {
            try {
                action()
            } catch (caught: Throwable) {
                interrupted = interrupted || caught is InterruptedException
                if (failure == null) {
                    failure = caught
                } else if (failure !== caught) {
                    failure.addSuppressed(caught)
                }
            } finally {
                interrupted = Thread.interrupted() || interrupted
            }
        }
        try {
            var descendants = emptyList<ProcessHandle>()
            attempt { descendants = lifecycle.descendants(process) }
            descendants.forEach { child -> attempt { lifecycle.destroy(child) } }
            attempt { lifecycle.destroy(process) }
            var reaped = false
            attempt { reaped = lifecycle.awaitExit(process, descendants) }
            if (!reaped) {
                attempt { descendants = (descendants + lifecycle.descendants(process)).distinctBy(ProcessHandle::pid) }
                descendants.forEach { child -> attempt { lifecycle.destroyForcibly(child) } }
                attempt { lifecycle.destroyForcibly(process) }
                attempt { reaped = lifecycle.awaitExit(process, descendants) }
                if (!reaped) attempt { error("Process cleanup could not confirm termination after forced cleanup") }
            }
            failure?.let { throw it }
        } finally {
            if (interrupted) Thread.currentThread().interrupt()
        }
    }

    /**
     * Spawns a new JVM process to instantiate [className] and invoke [methodName] via reflection.
     * Any additional [args] are passed to the method as strings.
     */
    fun runIsolatedMethod(
        className: String,
        methodName: String,
        vararg args: String,
    ) {
        runIsolatedTest(IsolatedTestRunner::class.java.name, className, methodName, *args)
    }
}

/**
 * Internal runner used by [IsolatedProcessTester.runIsolatedMethod].
 * Not intended for direct use.
 */
object IsolatedTestRunner {
    @JvmStatic
    fun main(args: Array<String>) {
        if (args.size < 2) {
            System.err.println("Usage: IsolatedTestRunner <className> <methodName> [args...]")
            System.exit(1)
        }
        val className = args[0]
        val methodName = args[1]
        val methodArgs = args.drop(2).toTypedArray()

        try {
            val clazz = Class.forName(className)
            val instance = try {
                clazz.getDeclaredConstructor().apply { isAccessible = true }.newInstance()
            } catch (e: NoSuchMethodException) {
                // Handle objects/singletons
                clazz.getField("INSTANCE").get(null)
            }

            val methods = clazz.declaredMethods.filter { it.name == methodName }
            if (methods.isEmpty()) {
                throw NoSuchMethodException("Method $methodName not found in $className")
            }

            // Prioritize the method with matching parameter count
            val method = methods.find { it.parameterCount == methodArgs.size }
                ?: methods.find { it.parameterCount == 1 && it.parameterTypes[0] == Array<String>::class.java }
                ?: methods.first()

            method.isAccessible = true

            when (method.parameterCount) {
                0 -> method.invoke(instance)
                1 -> {
                    if (method.parameterTypes[0] == Array<String>::class.java) {
                        method.invoke(instance, methodArgs)
                    } else {
                        method.invoke(instance, methodArgs[0])
                    }
                }

                else -> method.invoke(instance, *methodArgs)
            }
            System.exit(0)
        } catch (e: Exception) {
            val cause = if (e is java.lang.reflect.InvocationTargetException) e.cause ?: e else e
            System.err.println("IsolatedTestRunner failed to execute $className.$methodName: ${cause.message}")
            cause.printStackTrace(System.err)
            System.exit(2)
        }
    }
}
