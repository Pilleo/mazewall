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
 */
object IsolatedProcessTester {
    private const val DEFAULT_TIMEOUT_SECONDS = 120L
    private const val REAP_TIMEOUT_SECONDS = 2L

    /**
     * Spawns a new JVM process to run the specified [testClassName]'s [main] method with [args].
     * Asserts that the process exits with code 0.
     */
    fun runIsolatedTest(
        testClassName: String,
        vararg args: String,
    ) {
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
        val timeout = configuredTimeout()
        val completed =
            try {
                process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)
            } catch (interrupted: InterruptedException) {
                terminateTree(process)
                Thread.currentThread().interrupt()
                throw IllegalStateException("Interrupted while waiting for isolated test process", interrupted)
            }
        if (!completed) {
            terminateTree(process)
            throw IllegalStateException(
                "Isolated test process exceeded ${timeout.seconds}s and was terminated.\n" +
                    "Command: ${command.joinToString(" ")}",
            )
        }
        val exitCode = process.exitValue()
        if (exitCode != 0) {
            throw IllegalStateException(
                "Isolated test process failed with exit code $exitCode.\nCommand: ${command.joinToString(" ")}",
            )
        }
    }

    private fun configuredTimeout(): Duration {
        val raw = System.getProperty("io.mazewall.isolatedTestTimeoutSeconds") ?: return Duration.ofSeconds(DEFAULT_TIMEOUT_SECONDS)
        val seconds = raw.toLongOrNull()
            ?: throw IllegalArgumentException("io.mazewall.isolatedTestTimeoutSeconds must be an integer")
        require(seconds in 1..3600) { "io.mazewall.isolatedTestTimeoutSeconds must be between 1 and 3600" }
        return Duration.ofSeconds(seconds)
    }

    private fun terminateTree(process: Process) {
        val descendants = process.descendants().toList().asReversed()
        descendants.forEach(ProcessHandle::destroy)
        process.destroy()
        if (!awaitExit(process, descendants)) {
            descendants.filter(ProcessHandle::isAlive).forEach(ProcessHandle::destroyForcibly)
            process.destroyForcibly()
            awaitExit(process, descendants)
        }
    }

    private fun awaitExit(
        process: Process,
        descendants: List<ProcessHandle>,
    ): Boolean {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(REAP_TIMEOUT_SECONDS)
        descendants.forEach { child ->
            val remaining = deadline - System.nanoTime()
            if (remaining > 0) {
                runCatching { child.onExit().get(remaining, TimeUnit.NANOSECONDS) }
            }
        }
        val remaining = deadline - System.nanoTime()
        if (remaining > 0) {
            runCatching { process.waitFor(remaining, TimeUnit.NANOSECONDS) }
        }
        return !process.isAlive && descendants.none(ProcessHandle::isAlive)
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
