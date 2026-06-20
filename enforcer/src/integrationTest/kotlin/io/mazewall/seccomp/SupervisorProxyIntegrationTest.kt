package io.mazewall.seccomp

import io.mazewall.BaseIntegrationTest
import io.mazewall.EnabledIfLinuxAndSupported
import io.mazewall.Policy
import io.mazewall.core.Arch
import io.mazewall.core.Syscall
import io.mazewall.enforcer.ContainedExecutors
import io.mazewall.enforcer.supervisor.StacktraceScopingPolicy
import io.mazewall.core.Tid
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.Executors
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SupervisorProxyIntegrationTest : BaseIntegrationTest() {

    @Test
    @EnabledIfLinuxAndSupported
    fun `test supervised openat allows or denies based on JVM stack trace scoping policy`() {
        val tempFile = Files.createTempFile("supervised_test_", ".txt").toFile()
        tempFile.writeText("legit content")

        val allowedCalls = mutableListOf<String>()
        val deniedCalls = mutableListOf<String>()

        val scopingPolicy = object : StacktraceScopingPolicy {
            override fun authorize(
                tid: Tid,
                syscall: Syscall,
                args: List<Any>,
                stack: List<StackTraceElement>
            ): Boolean {
                val path = args.firstOrNull() as? String ?: ""
                if (!path.contains("supervised_test_")) {
                    return true
                }
                val isLegit = stack.any { it.methodName.contains("legitAction") }
                if (isLegit) {
                    allowedCalls.add(path)
                    return true
                } else {
                    deniedCalls.add(path)
                    return false
                }
            }
        }

        val policy = Policy.builder()
            .base(Policy.PURE_COMPUTE_UNSAFE)
            .supervise(Syscall.OPENAT)
            .supervise(Syscall.OPEN)
            .build()

        val rawExecutor = Executors.newSingleThreadExecutor()
        val containedExecutor = ContainedExecutors.wrap(rawExecutor, policy, scopingPolicy)

        try {
            // 1. Run legit action (should succeed)
            val legitResult = containedExecutor.submit<String> {
                legitAction(tempFile)
            }.get()
            assertEquals("legit content", legitResult)
            assertTrue(allowedCalls.any { it.contains(tempFile.name) }, "Expected tempFile open to be allowed")

            // 2. Run evil action (should fail)
            val evilResult = containedExecutor.submit<Boolean> {
                evilAction(tempFile)
            }.get()
            assertTrue(evilResult, "Evil action should have been blocked")
            assertTrue(deniedCalls.any { it.contains(tempFile.name) }, "Expected tempFile open to be denied")

        } finally {
            rawExecutor.shutdown()
            tempFile.delete()
        }
    }

    private fun legitAction(file: File): String {
        return file.readText()
    }

    private fun evilAction(file: File): Boolean {
        return try {
            file.readText()
            false
        } catch (e: java.io.IOException) {
            // Expect Landlock or Seccomp to deny it with EPERM / Operation not permitted
            true
        }
    }
}
