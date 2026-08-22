package io.mazewall

import java.io.File

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
            "-XX:-EnableJVMCI",
            "-XX:-UseJVMCICompiler",
            "-cp",
            classpath,
            "--enable-native-access=ALL-UNNAMED",
            testClassName,
        )
        command.addAll(args)

        val builder = ProcessBuilder(command)
        // Parent Gradle/Graal workers inject EnableJVMCIProduct via these.
        // Isolated children inherit them and then crash in C1 mmap(PROT_EXEC).
        builder.environment().remove("JAVA_TOOL_OPTIONS")
        builder.environment().remove("_JAVA_OPTIONS")
        builder.environment().remove("JDK_JAVA_OPTIONS")
        builder.inheritIO()
        val process = builder.start()
        val exitCode = process.waitFor()
        if (exitCode != 0) {
            throw IllegalStateException(
                "Isolated test process failed with exit code $exitCode.\nCommand: ${command.joinToString(" ")}",
            )
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
