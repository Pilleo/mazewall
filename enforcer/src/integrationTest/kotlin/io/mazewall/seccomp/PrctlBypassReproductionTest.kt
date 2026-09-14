package io.mazewall.seccomp

import io.mazewall.enforcer.api.*
import io.mazewall.enforcer.api.BaseIntegrationTest
import io.mazewall.enforcer.api.ContainedExecutors
import io.mazewall.enforcer.api.ContainmentViolationException
import io.mazewall.enforcer.api.EnabledIfLinuxAndSupported
import io.mazewall.enforcer.api.Policy
import io.mazewall.platform.fd.*
import io.mazewall.platform.lifecycle.*
import io.mazewall.platform.native.*
import io.mazewall.platform.native.LinuxNative
import io.mazewall.platform.process.*
import io.mazewall.platform.socket.*
import io.mazewall.platform.syscall.*
import io.mazewall.platform.syscall.PrctlCommand
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
                        val r =
                        LinuxNative.process.prctl(
                            PrctlCommand.SetPdeathsig(15L),
                        )

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
        fun `reproduce prctl PR_CAP_AMBIENT_RAISE bypass`() {
        val executor = Executors.newSingleThreadExecutor()
        val safeExecutor = ContainedExecutors.wrap(executor, Policy.builder().build())

        try {
            safeExecutor
                .submit(
                    Callable {
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
