package io.mazewall.enforcer

import io.mazewall.enforcer.api.*
import io.mazewall.enforcer.api.BaseIntegrationTest
import io.mazewall.enforcer.api.ContainedExecutors
import io.mazewall.enforcer.api.IsolatedProcessTester
import io.mazewall.enforcer.api.NeedsFreshJvm
import io.mazewall.enforcer.api.Policy
import io.mazewall.platform.fd.*
import io.mazewall.platform.lifecycle.*
import io.mazewall.platform.native.*
import io.mazewall.platform.process.*
import io.mazewall.platform.socket.*
import io.mazewall.platform.syscall.*
import org.junit.jupiter.api.Test
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@NeedsFreshJvm
class VirtualThreadGuardrailTest : BaseIntegrationTest() {
    @Test
    fun `installOnCurrentThread throws IllegalStateException on virtual thread`() {
        val executor = Executors.newVirtualThreadPerTaskExecutor()
        try {
            val future =
                executor.submit {
                    ContainedExecutors.installOnCurrentThread(ProcessPolicyBuilder.denyList(RuntimeProfile.NATIVE_IMAGE).build())
                }
            val exception =
                assertFailsWith<ExecutionException> {
                    future.get()
                }
            val cause = assertIs<IllegalStateException>(exception.cause)
            val message = assertNotNull(cause.message, "Virtual-thread guard failure must explain the rejection")
            assertTrue(message.contains("virtual thread"))
        } finally {
            executor.shutdown()
        }
    }

    @Test
    fun `installOnCurrentThread succeeds on platform thread`() {
        val executor = Executors.newSingleThreadExecutor()
        try {
            executor
                .submit {
                    ContainedExecutors.installOnCurrentThread(ProcessPolicyBuilder.denyList(RuntimeProfile.NATIVE_IMAGE).build())
                    // If it doesn't throw, it succeeds
                }.get()
        } finally {
            executor.shutdown()
        }
    }

    @Test
    fun `wrap does not throw on virtual thread submission`() {
        // Even if the executor uses virtual threads, wrap() itself should be safe.
        // The guardrail only kicks in when the task actually runs.
        val vExecutor = Executors.newVirtualThreadPerTaskExecutor()
        try {
            val safeExecutor = ContainedExecutors.wrap(vExecutor, ProcessPolicyBuilder.denyList(RuntimeProfile.NATIVE_IMAGE).build())

            val future =
                safeExecutor.submit {
                    // This task will fail when it tries to apply containment
                }

            assertFailsWith<ExecutionException> {
                future.get()
            }
        } finally {
            vExecutor.shutdown()
        }
    }

    fun testInstallOnProcessOnVirtualThread() {
        val executor = Executors.newVirtualThreadPerTaskExecutor()
        try {
            val future =
                executor.submit {
                    ContainedExecutors.installOnProcess(ProcessPolicyBuilder.denyList(RuntimeProfile.NATIVE_IMAGE).build())
                }
            val exception =
                assertFailsWith<ExecutionException> {
                    future.get()
                }
            val cause = assertIs<IllegalStateException>(exception.cause)
            val message = assertNotNull(cause.message, "Virtual-thread guard failure must explain the rejection")
            assertTrue(message.contains("virtual thread"))
        } finally {
            executor.shutdown()
        }
    }

    @Test
    fun `installOnProcess throws IllegalStateException on virtual thread`() {
        IsolatedProcessTester.runIsolatedMethod(this::class.java.name, "testInstallOnProcessOnVirtualThread")
    }
}
