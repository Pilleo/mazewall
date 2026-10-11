package io.mazewall.seccomp

import io.mazewall.enforcer.api.*
import io.mazewall.enforcer.api.BaseIntegrationTest
import io.mazewall.enforcer.api.ContainedExecutors
import io.mazewall.enforcer.api.ContainmentViolationException
import io.mazewall.enforcer.api.EnabledIfLinuxAndSupported
import io.mazewall.enforcer.api.Policy
import io.mazewall.enforcer.internal.seccomp.BpfFilter
import io.mazewall.ffi.NativeConstants
import io.mazewall.platform.fd.*
import io.mazewall.platform.lifecycle.*
import io.mazewall.platform.native.*
import io.mazewall.platform.native.LinuxNative
import io.mazewall.platform.process.*
import io.mazewall.platform.socket.*
import io.mazewall.platform.syscall.*
import io.mazewall.platform.syscall.PrctlCommand
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.test.fail

// These callbacks run on HotSpot; unsafe prctl remains denied while JIT mappings are allowed.
internal fun prctlFixturePolicy() = ProcessPolicyBuilder.denyList(RuntimeProfile.HOTSPOT_JIT).build()

class PrctlFixtureSimulationTest {
    companion object {
        private const val READ_EXECUTE_PROTECTION = 5L
        private const val READ_WRITE_PROTECTION = 3L
        private const val PR_SET_PDEATHSIG = 1L
        private const val PR_CAP_AMBIENT = 47L
        private const val PR_SET_NAME = 15L
        private const val PR_GET_NAME = 16L
        private const val PR_GET_SECCOMP = 21L
        private const val PR_CAPBSET_READ = 22L
        private const val PR_SET_NO_NEW_PRIVS = 38L
        private const val PR_GET_NO_NEW_PRIVS = 39L
        private const val UPPER_WORD_GARBAGE = 0x12345678L
        private const val C_INT_WORD_BITS = 32
        private const val AMBIENT_RAISE = 2L
        private const val CAP_IPC_OWNER = 15L
        private const val MAPPING_LENGTH = 4096L
        private const val PRIVATE_ANONYMOUS_MAPPING_FLAGS = 0x22L

        @JvmStatic
        fun architectures() = listOf(Arch.AMD64, Arch.AARCH64)
    }

    @ParameterizedTest
    @MethodSource("architectures")
    fun fixtureAllowsExecutableMmapForHotSpot(arch: Arch) {
        assertMapping(arch, prctlFixturePolicy().definition, Syscall.MMAP, READ_EXECUTE_PROTECTION, NativeConstants.SECCOMP_RET_ALLOW)
    }

    @ParameterizedTest
    @MethodSource("architectures")
    fun fixtureAllowsExecutableMprotectForHotSpot(arch: Arch) {
        assertMapping(arch, prctlFixturePolicy().definition, Syscall.MPROTECT, READ_EXECUTE_PROTECTION, NativeConstants.SECCOMP_RET_ALLOW)
    }

    @ParameterizedTest
    @MethodSource("architectures")
    fun fixtureDeniesUnsafePrctlWithExactEpermVerdict(arch: Arch) {
        for (option in listOf(PR_SET_PDEATHSIG, PR_CAP_AMBIENT)) {
            assertPrctl(arch, option, NativeConstants.SECCOMP_RET_ERRNO or 1)
        }
    }

    @ParameterizedTest
    @MethodSource("architectures")
    fun fixtureAllowsSafePrctlIncludingHighWordGarbageIntAbi(arch: Arch) {
        for (option in listOf(PR_SET_NAME, PR_GET_NAME, PR_GET_SECCOMP, PR_CAPBSET_READ, PR_SET_NO_NEW_PRIVS, PR_GET_NO_NEW_PRIVS)) {
            assertPrctl(arch, option, NativeConstants.SECCOMP_RET_ALLOW)
            assertPrctl(arch, (UPPER_WORD_GARBAGE shl C_INT_WORD_BITS) or option, NativeConstants.SECCOMP_RET_ALLOW)
        }
    }

    @ParameterizedTest
    @MethodSource("architectures")
    fun strictNativeImageDeniesExecutableMappingsButAllowsNonExecutableMappings(arch: Arch) {
        val strict = ProcessPolicyBuilder.denyList(RuntimeProfile.NATIVE_IMAGE).build().definition
        for (syscall in listOf(Syscall.MMAP, Syscall.MPROTECT)) {
            assertMapping(arch, strict, syscall, READ_EXECUTE_PROTECTION, NativeConstants.SECCOMP_RET_ERRNO or 1)
            assertMapping(arch, strict, syscall, READ_WRITE_PROTECTION, NativeConstants.SECCOMP_RET_ALLOW)
            assertMapping(arch, prctlFixturePolicy().definition, syscall, READ_WRITE_PROTECTION, NativeConstants.SECCOMP_RET_ALLOW)
        }
    }

    private fun assertPrctl(
        arch: Arch,
        option: Long,
        expected: Int,
    ) {
        val program = BpfFilter.build(arch, prctlFixturePolicy().definition).instructions
        assertEquals(
            expected,
            BpfSimulator.simulate(program, Syscall.PRCTL.numberFor(arch), arch.audit, longArrayOf(option, AMBIENT_RAISE, CAP_IPC_OWNER, 0L, 0L, 0L)),
            "$arch prctl option $option",
        )
    }

    private fun assertMapping(
        arch: Arch,
        definition: PolicyDefinition<*>,
        syscall: Syscall,
        protection: Long,
        expected: Int,
    ) {
        val program = BpfFilter.build(arch, definition).instructions
        // Synthetic -1 is syscall data, not an owned file descriptor.
        val args = longArrayOf(0L, MAPPING_LENGTH, protection, PRIVATE_ANONYMOUS_MAPPING_FLAGS, -1L, 0L)
        assertEquals(expected, BpfSimulator.simulate(program, syscall.numberFor(arch), arch.audit, args), "$arch $syscall protection $protection")
    }
}

class PrctlBypassReproductionTest : BaseIntegrationTest() {
    @Test
    @EnabledIfLinuxAndSupported
    fun `reproduce prctl PR_SET_PDEATHSIG bypass`() {
        val executor = Executors.newSingleThreadExecutor()
        val entered = AtomicBoolean()
        val denied = AtomicReference<LinuxNative.SyscallResult.Error<*>>()
        val thrown = AtomicReference<IllegalStateException>()
        // Unsafe prctl must remain blocked on the HotSpot-compatible fixture.
        val safeExecutor = ContainedExecutors.wrap(executor, prctlFixturePolicy())

        try {
            safeExecutor
                .submit(
                    Callable {
                        entered.set(true)
                        assertEquals(1L, LinuxNative.process.prctl(PrctlCommand.GetNoNewPrivs).getOrThrow("prctl(PR_GET_NO_NEW_PRIVS)"))
                        val r =
                        LinuxNative.process.prctl(
                            PrctlCommand.SetPdeathsig(15L),
                        )

                        if (r is LinuxNative.SyscallResult.Error && r.errno != 1) {
                            throw IllegalStateException("SECURITY BYPASS: prctl(PR_SET_PDEATHSIG) reached kernel (errno ${r.errno} instead of EPERM)")
                        }
                        if (r is LinuxNative.SyscallResult.Error) denied.set(r)
                        try {
                            r.getOrThrow("prctl(PR_SET_PDEATHSIG)")
                        } catch (failure: IllegalStateException) {
                            thrown.set(failure)
                            throw failure
                        }
                    },
                ).get()
            throw IllegalStateException("SECURITY BYPASS: prctl(PR_SET_PDEATHSIG, SIGTERM) was allowed")
        } catch (e: ExecutionException) {
            val cause = e.cause
            if (cause is ContainmentViolationException) {
                assertTrue(entered.get(), "Native callback must have entered")
                val result = assertNotNull(denied.get(), "Native prctl must have returned an error")
                assertEquals(1, result.errno)
                assertEquals(-1L, result.rawValue)
                val original = assertNotNull(thrown.get(), "getOrThrow must have thrown")
                assertEquals(IllegalStateException::class, original::class)
                assertEquals("prctl(PR_SET_PDEATHSIG) failed with errno=1 (EPERM) (raw return=-1)", original.message)
                assertSame(original, cause.cause, "Translation must preserve the actual getOrThrow exception")
                return
            }
            if (cause is IllegalStateException && cause.message?.contains("SECURITY BYPASS") == true) {
                fail(cause.message)
            }
            throw e
        } finally {
            executor.shutdown()
        }
    }

    @Test
    @EnabledIfLinuxAndSupported
        fun `reproduce prctl PR_CAP_AMBIENT_RAISE bypass`() {
        val executor = Executors.newSingleThreadExecutor()
        val entered = AtomicBoolean()
        val denied = AtomicReference<LinuxNative.SyscallResult.Error<*>>()
        val thrown = AtomicReference<IllegalStateException>()
        val safeExecutor = ContainedExecutors.wrap(executor, prctlFixturePolicy())

        try {
            safeExecutor
                .submit(
                    Callable {
                        entered.set(true)
                        assertEquals(1L, LinuxNative.process.prctl(PrctlCommand.GetNoNewPrivs).getOrThrow("prctl(PR_GET_NO_NEW_PRIVS)"))
                        val r =
                        LinuxNative.process.prctl(
                            PrctlCommand.CapAmbient(2L, 15L),
                        )

                        if (r is LinuxNative.SyscallResult.Error) {
                            if (r.errno == 22) {
                                throw IllegalStateException("SECURITY BYPASS: prctl(PR_CAP_AMBIENT) reached kernel (EINVAL instead of EPERM)")
                            }
                            if (r.errno != 1) {
                                throw IllegalStateException("SECURITY BYPASS: prctl(PR_CAP_AMBIENT) reached kernel (errno ${r.errno} instead of EPERM)")
                            }
                        }
                        if (r is LinuxNative.SyscallResult.Error) denied.set(r)
                        try {
                            r.getOrThrow("prctl(PR_CAP_AMBIENT)")
                        } catch (failure: IllegalStateException) {
                            thrown.set(failure)
                            throw failure
                        }
                    },
                ).get()
            throw IllegalStateException("CRITICAL SECURITY BYPASS: prctl(PR_CAP_AMBIENT) succeeded")
        } catch (e: ExecutionException) {
            val cause = e.cause
            if (cause is ContainmentViolationException) {
                assertTrue(entered.get(), "Native callback must have entered")
                val result = assertNotNull(denied.get(), "Native prctl must have returned an error")
                assertEquals(1, result.errno)
                assertEquals(-1L, result.rawValue)
                val original = assertNotNull(thrown.get(), "getOrThrow must have thrown")
                assertEquals(IllegalStateException::class, original::class)
                assertEquals("prctl(PR_CAP_AMBIENT) failed with errno=1 (EPERM) (raw return=-1)", original.message)
                assertSame(original, cause.cause, "Translation must preserve the actual getOrThrow exception")
                return
            }
            if (cause is IllegalStateException && cause.message?.contains("BYPASS") == true) {
                fail(cause.message)
            }
            throw e
        } finally {
            executor.shutdown()
        }
    }
}
