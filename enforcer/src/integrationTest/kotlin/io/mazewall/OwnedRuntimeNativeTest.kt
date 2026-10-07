package io.mazewall.enforcer.api.execution

import io.mazewall.enforcer.api.*
import io.mazewall.platform.native.LinuxNative
import io.mazewall.platform.syscall.Arch
import io.mazewall.platform.syscall.NativeArg
import io.mazewall.platform.syscall.Syscall
import org.junit.jupiter.api.Test
import java.nio.file.AccessDeniedException
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class OwnedRuntimeNativeTest : BaseIntegrationTest() {
    @Test
    @EnabledIfLinuxAndSupported
    fun `explicit process baseline and every owned worker enforce before readiness`() {
        val file = Files.createTempFile("owned-runtime-private-", ".txt")
        try {
            Files.writeString(file, "caller-readable")
            IsolatedProcessTester.runIsolatedMethod(javaClass.name, "verifyOwnedRuntime", file.toString())
        } finally {
            Files.deleteIfExists(file)
        }
    }

    fun verifyOwnedRuntime(file: String) {
        val timeout = Duration.ofSeconds(10)
        ManagedRuntime.open(RuntimeProfile.HOTSPOT_JIT, Policy.NO_EXEC_HOTSPOT, timeout, timeout).use { runtime ->
            val policy = WorkerPolicyBuilder.denyList(RuntimeProfile.HOTSPOT_JIT).filesystem(FileSystemPolicy.DenyAll).build()
            val domain = runtime.prepare(policy, DomainLimits(2, 2))
            val entered = CountDownLatch(2)
            val handles = List(2) {
                runtime.submit(
                    domain,
                    Callable {
                    entered.countDown()
                    check(entered.await(5, TimeUnit.SECONDS))
                    assertFailsWith<AccessDeniedException> { Files.readString(Path.of(file)) }
                    42
                },
                )
            }
            handles.forEach {
                assertEquals(42, it.result().toCompletableFuture().get(10, TimeUnit.SECONDS))
                assertIs<ExecutionEnd.Finished>(it.executionEnd().toCompletableFuture().get(10, TimeUnit.SECONDS))
            }
            assertFailsWith<ContainmentViolationException> {
                Mazewall.callContained(
                    runtime,
                    domain,
                    Callable {
                    assertFailsWith<ContainmentViolationException> {
                        Mazewall.callContained(runtime, domain, Callable { Files.readString(Path.of(file)) })
                    }
                    42
                },
                )
            }
            val denied = LinuxNative.raw.syscall(Syscall.EXECVE.numberFor(Arch.current()).toLong(), NativeArg.LongArg(0L), NativeArg.LongArg(0L), NativeArg.LongArg(0L))
            assertEquals(1, assertIs<LinuxNative.SyscallResult.Error<*>>(denied).errno)
            assertEquals("caller-readable", Files.readString(Path.of(file)))
            assertEquals(DrainReport.Complete, runtime.drain(timeout))
        }
    }
}
