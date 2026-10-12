package io.mazewall.seccomp

import io.mazewall.enforcer.api.*
import io.mazewall.enforcer.api.BaseIntegrationTest
import io.mazewall.enforcer.api.ContainedExecutors
import io.mazewall.enforcer.api.EnabledIfLinuxAndSupported
import io.mazewall.enforcer.api.NeedsFreshJvm
import io.mazewall.enforcer.api.Policy
import io.mazewall.enforcer.api.ScopingHandler
import io.mazewall.enforcer.api.StacktraceScopingPolicy
import io.mazewall.enforcer.state.ContainmentStateRegistry
import io.mazewall.platform.fd.*
import io.mazewall.platform.lifecycle.*
import io.mazewall.platform.native.*
import io.mazewall.platform.process.*
import io.mazewall.platform.socket.*
import io.mazewall.platform.syscall.*
import io.mazewall.platform.syscall.Syscall
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

@NeedsFreshJvm
class PermanentSupervisorRegistrationIntegrationTest : BaseIntegrationTest() {
    @Test
    @EnabledIfLinuxAndSupported
    fun `sequential and nested tasks retain one permanent supervisor runtime`() {
        val file = Files.createTempFile("permanent-supervisor-", ".txt").toFile()
        file.writeText("registered")
        val calls = AtomicInteger()
        val scoping = object : StacktraceScopingPolicy {
            override val handlers = mapOf<Syscall, ScopingHandler>(
                Syscall.OPENAT to { _, args, _ ->
                    if (args.any { it.toString().contains(file.name) }) calls.incrementAndGet()
                    true
                },
                Syscall.OPEN to { _, args, _ ->
                    if (args.any { it.toString().contains(file.name) }) calls.incrementAndGet()
                    true
                },
            )
        }
        val policy = ProcessPolicyBuilder.denyList(RuntimeProfile.NATIVE_IMAGE).allowMmapExec().build()
        try {
            Executors.newSingleThreadExecutor().use { raw ->
                repeat(2) {
                    assertEquals(
                        "registered",
                        raw
                            .submit<String> {
                        ContainedExecutors.installOnCurrentThread(policy, scoping).use { receipt ->
                            receipt.requireInstalled()
                            file.readText()
                        }
                    }.get(10, TimeUnit.SECONDS),
                    )
                }
                val depth = raw
                    .submit<Int> {
                    ContainedExecutors.installOnCurrentThread(policy, scoping).use { outer ->
                        outer.requireInstalled()
                        val lease = ContainedExecutors.installOnCurrentThread(policy, scoping).requireInstalled()
                        lease.close()
                        lease.close()
                        assertEquals("registered", file.readText())
                        ContainmentStateRegistry.threadState.filterDepth
                    }
                }.get(10, TimeUnit.SECONDS)
                assertEquals(1, depth, "sequential and nested task leases must not stack filters")
                assertTrue(calls.get() >= 3, "all target accesses must reach the registered scoping handler")
                val other = object : StacktraceScopingPolicy {
                    override val handlers = scoping.handlers
                }
                val failure = assertFailsWith<java.util.concurrent.ExecutionException> {
                    raw
                        .submit<String> {
                        ContainedExecutors.installOnCurrentThread(policy, other).use { receipt ->
                            receipt.requireInstalled()
                            file.readText()
                        }
                    }.get(10, TimeUnit.SECONDS)
                }
                assertTrue(
                    failure.cause
                    ?.message
                    .orEmpty()
                    .contains("Cannot replace the scoping policy"),
                )
            }
        } finally {
            file.delete()
            io.mazewall.enforcer.internal.supervisor.SupervisorDaemonManager
                .getInstance()
                .stop()
        }
    }
}
