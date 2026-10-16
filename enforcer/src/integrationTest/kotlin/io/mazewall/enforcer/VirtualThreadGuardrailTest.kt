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
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
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
    fun `required acquired virtual worker installation rejects before task entry`() {
        val vExecutor = Executors.newVirtualThreadPerTaskExecutor()
        val entries = AtomicInteger()
        try {
            val future =
                vExecutor.submit {
                    ContainedExecutors.installOnCurrentThread(ProcessPolicyBuilder.denyList(RuntimeProfile.NATIVE_IMAGE).build()).use { receipt ->
                        receipt.requireInstalled()
                        entries.incrementAndGet()
                    }
                }

            val failure = assertFailsWith<ExecutionException> {
                future.get(10, TimeUnit.SECONDS)
            }
            assertTrue(assertIs<IllegalStateException>(failure.cause).message.orEmpty().contains("virtual thread"))
            assertEquals(0, entries.get())
        } finally {
            vExecutor.close()
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
