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
        val policy = Policy.builder().allowMmapExec().build()
        try {
            Executors.newSingleThreadExecutor().use { raw ->
                val wrapped = ContainedExecutors.wrap(raw, policy, scoping)
                repeat(2) {
                    assertEquals("registered", wrapped.submit<String> { file.readText() }.get())
                }
                val depth = wrapped
                    .submit<Int> {
                    val nested = ContainedExecutors.installOnCurrentThread(policy, scoping)
                    val lease = checkNotNull(nested.supervisorSession)
                    lease.close()
                    lease.close()
                    assertEquals("registered", file.readText())
                    ContainmentStateRegistry.threadState.filterDepth
                }.get()
                assertEquals(1, depth, "sequential and nested task leases must not stack filters")
                assertTrue(calls.get() >= 3, "all target accesses must reach the registered scoping handler")
                val other = object : StacktraceScopingPolicy {
                    override val handlers = scoping.handlers
                }
                val otherWrapper = ContainedExecutors.wrap(raw, policy, other)
                val failure = assertFailsWith<java.util.concurrent.ExecutionException> {
                    otherWrapper.submit<String> { file.readText() }.get()
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
