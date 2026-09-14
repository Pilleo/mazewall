package io.mazewall.seccomp

import io.mazewall.enforcer.api.*
import io.mazewall.enforcer.api.ContainedExecutors
import io.mazewall.enforcer.api.IsolatedProcessTester
import io.mazewall.enforcer.api.ProcessPolicies
import io.mazewall.enforcer.api.RuntimeProfile
import io.mazewall.platform.fd.*
import io.mazewall.platform.lifecycle.*
import io.mazewall.platform.native.*
import io.mazewall.platform.process.*
import io.mazewall.platform.socket.*
import io.mazewall.platform.syscall.*
import org.junit.jupiter.api.Test
import java.nio.file.AccessDeniedException
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class DedicatedProcessBaselineTest {
    @Test
    fun `dedicated baseline restricts already running sibling and allows JDK read`() {
        IsolatedProcessTester.runIsolatedMethod(this::class.java.name, "verifyExistingSibling")
    }

    fun verifyExistingSibling() {
        val ready = CountDownLatch(1)
        val proceed = CountDownLatch(1)
        val outcome = AtomicReference<Throwable?>()
        val forbidden = Path.of("/etc/passwd")
        check(Files.readAllBytes(forbidden).isNotEmpty()) // Positive control before restriction.
        val sibling = Thread.ofPlatform().start {
            ready.countDown()
            try {
                check(proceed.await(10, TimeUnit.SECONDS))
                Files.readAllBytes(forbidden)
                outcome.set(AssertionError("existing sibling escaped process Landlock"))
            } catch (_: AccessDeniedException) {
                // Typed filesystem denial proves the existing thread acquired Landlock.
            } catch (failure: Throwable) {
                outcome.set(failure)
            }
        }
        check(ready.await(10, TimeUnit.SECONDS))
        try {
            val receipt = ContainedExecutors.installOnDedicatedProcess(
                ProcessPolicies.workerFilesystem(RuntimeProfile.HOTSPOT_JIT),
                ProcessPolicies.denyProcessCreation(RuntimeProfile.HOTSPOT_JIT),
                ProcessPolicies.denyNetwork(RuntimeProfile.HOTSPOT_JIT),
            )
            check(receipt.processWide && receipt.installed && receipt.landlockApplied)
            check(Files.readAllBytes(Path.of(System.getProperty("java.home"), "release")).isNotEmpty())
        } finally {
            proceed.countDown()
            sibling.join(10_000)
        }
        check(!sibling.isAlive)
        outcome.get()?.let { throw it }
    }
}
