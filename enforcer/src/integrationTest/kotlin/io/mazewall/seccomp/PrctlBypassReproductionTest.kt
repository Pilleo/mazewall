package io.mazewall.seccomp

import io.mazewall.BaseIntegrationTest
import io.mazewall.EnabledIfLinuxAndSupported
import io.mazewall.LinuxNative
import io.mazewall.Policy
import io.mazewall.core.NativeArg
import io.mazewall.enforcer.ContainedExecutors
import io.mazewall.enforcer.ContainmentViolationException
import org.junit.jupiter.api.Test
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import kotlin.test.fail

class PrctlBypassReproductionTest : BaseIntegrationTest() {
    @Test
    @EnabledIfLinuxAndSupported
    fun `reproduce prctl PR_SET_PDEATHSIG bypass`() {
        val executor = Executors.newSingleThreadExecutor()
        // Strict policy should block everything except whitelisted prctl options
        val safeExecutor = ContainedExecutors.wrap(executor, Policy.builder().build())

        try {
            safeExecutor
                .submit(
                    Callable {
                        val r = LinuxNative.withTransaction {
                            LinuxNative.process.prctl(
                                1,
                                NativeArg.IntArg(15),
                                NativeArg.NullArg,
                                NativeArg.NullArg,
                                NativeArg.NullArg,
                            )
                        }
                        if (r is LinuxNative.SyscallResult.Error && r.errno != 1) {
                            throw IllegalStateException("SECURITY BYPASS: prctl(PR_SET_PDEATHSIG) reached kernel (errno ${r.errno} instead of EPERM)")
                        }
                        r.getOrThrow("prctl(PR_SET_PDEATHSIG)")
                    },
                ).get()
            throw IllegalStateException("SECURITY BYPASS: prctl(PR_SET_PDEATHSIG, SIGTERM) was allowed")
        } catch (e: ExecutionException) {
            val cause = e.cause
            if (cause is ContainmentViolationException) {
                // Correctly blocked!
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
    @Suppress("ThrowsCount")
    fun `reproduce prctl PR_CAP_AMBIENT_RAISE bypass`() {
        val executor = Executors.newSingleThreadExecutor()
        val safeExecutor = ContainedExecutors.wrap(executor, Policy.builder().build())

        try {
            safeExecutor
                .submit(
                    Callable {
                        val r = LinuxNative.withTransaction {
                            LinuxNative.process.prctl(
                                47,
                                NativeArg.IntArg(2),
                                NativeArg.IntArg(15),
                                NativeArg.NullArg,
                                NativeArg.NullArg,
                            )
                        }
                        if (r is LinuxNative.SyscallResult.Error) {
                            if (r.errno == 22) {
                                throw IllegalStateException("SECURITY BYPASS: prctl(PR_CAP_AMBIENT) reached kernel (EINVAL instead of EPERM)")
                            }
                            if (r.errno != 1) {
                                throw IllegalStateException("SECURITY BYPASS: prctl(PR_CAP_AMBIENT) reached kernel (errno ${r.errno} instead of EPERM)")
                            }
                        }
                        r.getOrThrow("prctl(PR_CAP_AMBIENT)")
                    },
                ).get()
            throw IllegalStateException("CRITICAL SECURITY BYPASS: prctl(PR_CAP_AMBIENT) succeeded")
        } catch (e: ExecutionException) {
            val cause = e.cause
            if (cause is ContainmentViolationException) {
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
