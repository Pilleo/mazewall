package io.mazewall.seccomp

import io.mazewall.enforcer.api.*
import io.mazewall.enforcer.api.Policy
import io.mazewall.enforcer.api.compile
import io.mazewall.platform.fd.*
import io.mazewall.platform.lifecycle.*
import io.mazewall.platform.native.*
import io.mazewall.platform.process.*
import io.mazewall.platform.socket.*
import io.mazewall.platform.syscall.*
import io.mazewall.platform.syscall.Arch
import org.junit.jupiter.api.Test
import java.util.concurrent.Executors
import kotlin.test.assertFailsWith

class PureJavaBpfEngineBypassTest {
    @Test
    fun `direct state machine calls throw IllegalStateException from virtual thread`() {
        val virtualThreadExecutor = Executors.newVirtualThreadPerTaskExecutor()

        virtualThreadExecutor
            .submit {
            val built = SeccompInstallationState.FilterBuilt(io.mazewall.ffi.memory.ManagedSegment.NULL)
            assertFailsWith<IllegalStateException> {
                built.lockPrivileges()
            }
        }.get()
    }

    @Test
    fun `PureJavaBpfEngine install still throws IllegalStateException from virtual thread`() {
        val policy = Policy.PURE_COMPUTE_UNSAFE.definition.compile(Arch.current())
        val virtualThreadExecutor = Executors.newVirtualThreadPerTaskExecutor()

        virtualThreadExecutor
            .submit {
            assertFailsWith<IllegalStateException> {
                PureJavaBpfEngine.install(policy)
            }
        }.get()
    }
}
