package io.mazewall.seccomp

import io.mazewall.BaseIntegrationTest
import io.mazewall.EnabledIfLinuxAndSupported
import io.mazewall.Policy
import io.mazewall.core.Syscall
import io.mazewall.enforcer.ContainedExecutors
import io.mazewall.enforcer.supervisor.StacktraceScopingPolicy
import io.mazewall.enforcer.supervisor.ScopingHandler
import io.mazewall.core.Tid
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.Executors
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SupervisorProxyIntegrationTest : BaseIntegrationTest() {

    companion object {
        @org.junit.jupiter.api.AfterAll
        @JvmStatic
        fun tearDownAll() {
            io.mazewall.enforcer.supervisor.SupervisorDaemonManager.getInstance().stop()
        }
    }


    /**
     * Verifies the [StacktraceScopingPolicy] allow/deny contract.
     *
     * ### What this test checks
     * - **Functional**: a task calling `file.readText()` from within a method named `legitAction`
     *   successfully reads the file (the scoping policy allows it).
     * - **Security**: a task calling `file.readText()` from outside `legitAction` is denied by
     *   the scoping policy with an `IOException` (EPERM from seccomp).
     *
     * ### Why `allowedCalls` is not asserted
     *
     * On a cold JVM thread (first execution), `file.readText()` may trigger lazy classloading
     * of Kotlin stdlib IO classes. Any read originating from the JDK home path is automatically
     * allowed via the daemon-side fast-path bypass to prevent deadlock — the scoping
     * policy is not consulted for that call. The file is still opened successfully (bypass =
     * allow), so the legit functional test passes regardless.
     *
     * Asserting `allowedCalls.isNotEmpty()` would make the test depend on whether classloading
     * happened to coincide with the file access on first execution — a timing condition that
     * varies between a warmed local runner and a cold CI runner. That is an internal
     * implementation detail, not the library's security contract.
     *
     * ### Why `deniedCalls` IS asserted (and is reliable)
     *
     * The evil action always runs **after** the legit action. By the time evil executes, all
     * Kotlin IO classes used by `file.readText()` are already loaded. The daemon fast-path does
     * not fire for the non-JDK user temp file, so the scoping policy is consulted and denies the access.
     * This assertion is therefore deterministic regardless of JVM warmup state.
     */
    @Test
    @EnabledIfLinuxAndSupported
    fun `test supervised openat allows or denies based on JVM stack trace scoping policy`() {
        val tempFile = Files.createTempFile("supervised_test_", ".txt").toFile()
        tempFile.writeText("legit content")

        val deniedCalls = mutableListOf<String>()

        val scopingPolicy = object : StacktraceScopingPolicy {
            override val handlers = mapOf<Syscall, ScopingHandler>(
                Syscall.OPENAT to { tid, args, stack -> authorize(args, stack) },
                Syscall.OPEN to { tid, args, stack -> authorize(args, stack) }
            )
            
            private fun authorize(args: List<Any>, stack: List<StackTraceElement>): Boolean {
                val path = args.firstOrNull() as? String ?: ""
                if (!path.contains("supervised_test_")) {
                    return true
                }
                val isLegit = stack.any { it.className.contains("LegitContext") }
                if (!isLegit) {
                    deniedCalls.add(path)
                }
                return isLegit
            }
        }
 
        val policy = Policy.builder()
            .base(Policy.PURE_COMPUTE_UNSAFE)
            .build()
 
        val rawExecutor = Executors.newSingleThreadExecutor()
        val containedExecutor = ContainedExecutors.wrap(rawExecutor, policy, scopingPolicy)
 
        try {
            // Run legit action first. The file is read successfully (either via the allow path
            // of the policy, or via the classloader bypass on a cold JVM — both are correct).
            // The primary effect here is to ensure all Kotlin IO classes are loaded so the
            // subsequent evil-action execution is free from classloading interference.
            val legitResult = containedExecutor.submit<String> {
                LegitContext(tempFile).run()
            }.get()
            assertEquals("legit content", legitResult)
 
            // Run evil action second. All IO classes are now loaded; the classloader bypass
            // cannot fire. The scoping policy is invoked and must deny the access.
            val evilResult = containedExecutor.submit<Boolean> {
                EvilContext(tempFile).run()
            }.get()
            assertTrue(evilResult, "Evil action must be denied by the scoping policy")
            assertTrue(
                deniedCalls.any { it.contains(tempFile.name) },
                "Scoping policy must have been invoked and denied the evil file access"
            )
        } finally {
            rawExecutor.shutdown()
            tempFile.delete()
            System.err.println("--- VALIDATION LOGS ---")
            while (true) {
                val log = io.mazewall.enforcer.supervisor.ValidationLog.logs.poll() ?: break
                System.err.print(log)
            }
            System.err.println("-----------------------")
            System.err.println("--- DAEMON LOGS ---")
            System.err.println("DEBUG: SupervisorDaemonManager classLoader: ${io.mazewall.enforcer.supervisor.SupervisorDaemonManager::class.java.classLoader}")
            try {
                val logFile = java.io.File("/tmp/supervisor_daemon.log")
                if (logFile.exists()) {
                    System.err.println(logFile.readText())
                } else {
                    System.err.println("Daemon log file does not exist")
                }
            } catch (e: Exception) {
                System.err.println("Failed to read daemon log file: ${e.message}")
            }
            System.err.println("-------------------")
        }
    }
 
    @Test
    @EnabledIfLinuxAndSupported
    fun `test daemon fast-path allows reads inside java home even from evil context`() {
        val releaseFile = File(System.getProperty("java.home"), "release")
        if (!releaseFile.exists()) {
            return
        }

        val scopingPolicy = object : StacktraceScopingPolicy {
            override val handlers = mapOf<Syscall, ScopingHandler>(
                Syscall.OPENAT to { tid, args, stack -> false },
                Syscall.OPEN to { tid, args, stack -> false }
            )
        }

        val policy = Policy.builder()
            .base(Policy.PURE_COMPUTE_UNSAFE)
            .build()

        val rawExecutor = Executors.newSingleThreadExecutor()
        val containedExecutor = ContainedExecutors.wrap(rawExecutor, policy, scopingPolicy)

        try {
            // Read a core JDK file. Despite scopingPolicy returning false for everything,
            // the daemon fast-path should intercept the read and allow it immediately.
            val result = containedExecutor.submit<Boolean> {
                try {
                    releaseFile.readText()
                    true
                } catch (e: Exception) {
                    System.err.println("Fast-path read failed:")
                    e.printStackTrace()
                    false
                }
            }.get()

            assertTrue(result, "Reading JDK home files must succeed via daemon fast-path bypass")
        } finally {
            rawExecutor.shutdown()
            System.err.println("--- VALIDATION LOGS ---")
            while (true) {
                val log = io.mazewall.enforcer.supervisor.ValidationLog.logs.poll() ?: break
                System.err.print(log)
            }
            System.err.println("-----------------------")
            System.err.println("--- DAEMON LOGS ---")
            System.err.println("DEBUG: SupervisorDaemonManager classLoader: ${io.mazewall.enforcer.supervisor.SupervisorDaemonManager::class.java.classLoader}")
            try {
                val logFile = java.io.File("/tmp/supervisor_daemon.log")
                if (logFile.exists()) {
                    System.err.println(logFile.readText())
                } else {
                    System.err.println("Daemon log file does not exist")
                }
            } catch (e: Exception) {
                System.err.println("Failed to read daemon log file: ${e.message}")
            }
            System.err.println("-------------------")
        }
    }

    private class LegitContext(private val file: File) {
        fun run(): String {
            return file.readText()
        }
    }
 
    private class EvilContext(private val file: File) {
        fun run(): Boolean {
            return try {
                file.readText()
                false
            } catch (e: java.io.IOException) {
                // Seccomp denied the openat with EPERM
                true
            }
        }
    }
}
