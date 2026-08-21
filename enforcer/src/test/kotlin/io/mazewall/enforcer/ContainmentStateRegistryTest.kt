package io.mazewall.enforcer

import io.mazewall.enforcer.state.ContainerState
import io.mazewall.enforcer.state.ContainmentStateRegistry
import io.mazewall.ffi.memory.ManagedSegment
import io.mazewall.seccomp.SeccompInstallationState
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.Test

internal class ContainmentStateRegistryTest {

    @AfterEach
    fun teardown() {
        ContainmentStateRegistry.threadState = ContainerState()
        ContainmentStateRegistry.processState = ContainerState()
    }

    @Test
    fun `test ProcessStateRegistry update`() {
        val originalState = ContainmentStateRegistry.processState
        assertNotNull(originalState)

        try {
            ContainmentStateRegistry.updateProcessState { state ->
                state.copy(filterDepth = 100)
            }

            assertEquals(100, ContainmentStateRegistry.processState.filterDepth)
        } finally {
            // Restore state to avoid polluting other tests
            ContainmentStateRegistry.processState = originalState
        }
    }

    @Test
    fun `test ProcessStateRegistry concurrent updates and state resolutions`() {
        val originalState = ContainmentStateRegistry.processState
        val executor = java.util.concurrent.Executors.newFixedThreadPool(8)
        val stopFlag = java.util.concurrent.atomic.AtomicBoolean(false)
        val exceptions = java.util.concurrent.CopyOnWriteArrayList<Throwable>()

        try {
            val tasks = mutableListOf<java.util.concurrent.Future<*>>()

            // Spawn 4 threads updating the process state
            repeat(4) {
                tasks.add(executor.submit {
                    while (!stopFlag.get()) {
                        try {
                            ContainmentStateRegistry.updateProcessState { state ->
                                state.copy(filterDepth = state.filterDepth + 1)
                            }
                        } catch (t: Throwable) {
                            exceptions.add(t)
                        }
                    }
                })
            }

            // Spawn 4 threads concurrently resolving current state
            repeat(4) {
                tasks.add(executor.submit {
                    while (!stopFlag.get()) {
                        try {
                            val resolved = ContainmentStateRegistry.resolveCurrentState()
                            assertNotNull(resolved)
                        } catch (t: Throwable) {
                            exceptions.add(t)
                        }
                    }
                })
            }

            // Let them run for 300ms
            Thread.sleep(300)
            stopFlag.set(true)

            // Wait for all tasks to complete
            tasks.forEach { it.get() }

            // Check if any exceptions were thrown
            assertTrue(exceptions.isEmpty(), "Expected no concurrent modification exceptions, but got: ${exceptions.map { it.stackTraceToString() }}")

        } finally {
            executor.shutdownNow()
            executor.awaitTermination(1, java.util.concurrent.TimeUnit.SECONDS)
            ContainmentStateRegistry.processState = originalState
        }
    }

    @Test
    fun `test state getting and setting`() {
        val original = ContainmentStateRegistry.threadState
        assertNotNull(original)

        val newState = ContainerState(filterDepth = 42)
        ContainmentStateRegistry.threadState = newState
        assertEquals(newState, ContainmentStateRegistry.threadState)
        assertEquals(42, ContainmentStateRegistry.threadState.filterDepth)
    }

    @Test
    fun `test resolveCurrentState caches resolved state and invalidates correctly`() {
        val state1 = ContainmentStateRegistry.resolveCurrentState()
        val state2 = ContainmentStateRegistry.resolveCurrentState()

        // They must be the exact same instance due to caching!
        assertTrue(state1 === state2, "Expected cached state to be returned on subsequent resolves")

        // Invalidate thread state
        ContainmentStateRegistry.threadState = ContainerState(filterDepth = 1)
        val state3 = ContainmentStateRegistry.resolveCurrentState()
        assertTrue(state1 !== state3, "Expected new resolved state after thread state modification")
        assertEquals(1, state3.filterDepth)

        val state4 = ContainmentStateRegistry.resolveCurrentState()
        assertTrue(state3 === state4, "Expected cached state after new thread state resolution")

        // Invalidate process state
        ContainmentStateRegistry.processState = ContainerState(filterDepth = 5)
        val state5 = ContainmentStateRegistry.resolveCurrentState()
        assertTrue(state4 !== state5, "Expected new resolved state after process state modification")
        assertEquals(6, state5.filterDepth) // 1 (thread) + 5 (process)

        val state6 = ContainmentStateRegistry.resolveCurrentState()
        assertTrue(state5 === state6, "Expected cached state after new process state resolution")
    }

    @Test
    fun `sanitizeThreadState throws UnsupportedOperationException`() {
        val exception = assertThrows<UnsupportedOperationException> {
            ContainmentStateRegistry.sanitizeThreadState()
        }
        val message = exception.message ?: ""
        assertTrue(
            message.contains("permanent") || message.contains("thread") || message.contains("lifetime"),
            "Exception message should mention permanent restrictions or thread lifetime, got: $message"
        )
    }

    @Test
    fun `sanitizeThreadState has return type Nothing`() {
        // In Kotlin, a function returning Nothing compiles to Void in Java bytecode.
        // The Java reflection API returns java.lang.Void.class for the return type.
        val method = ContainmentStateRegistry::class.java.getDeclaredMethod("sanitizeThreadState")
        assertEquals(
            Void::class.java, method.returnType,
            "Return type should be java.lang.Void (which represents Kotlin's Nothing)"
        )
    }

    @Test
    fun `sanitizeThreadState does not clear threadState on throw`() {
        val initialState = ContainerState(filterDepth = 3)
        ContainmentStateRegistry.threadState = initialState

        assertThrows<UnsupportedOperationException> {
            ContainmentStateRegistry.sanitizeThreadState()
        }

        assertEquals(
            3, ContainmentStateRegistry.threadState.filterDepth,
            "threadState should not be cleared after sanitizeThreadState throws"
        )
    }

    companion object {
        private val dummyError = RuntimeException("err")
        private val dummyFailed = SeccompInstallationState.Failed("step", 1, dummyError)
        private val dummyFilterBuilt = SeccompInstallationState.FilterBuilt(ManagedSegment.NULL)
        private val dummyPrivLocked = SeccompInstallationState.PrivilegesLocked(ManagedSegment.NULL)

        @JvmStatic
        fun engineStateCombinations(): java.util.stream.Stream<org.junit.jupiter.params.provider.Arguments> =
            java.util.stream.Stream.of(
                org.junit.jupiter.params.provider.Arguments.of(
                    SeccompInstallationState.Uninitialized,
                    SeccompInstallationState.Uninitialized,
                    SeccompInstallationState.Uninitialized,
                ),
                org.junit.jupiter.params.provider.Arguments.of(
                    SeccompInstallationState.Uninitialized,
                    SeccompInstallationState.Verified,
                    SeccompInstallationState.Verified,
                ),
                org.junit.jupiter.params.provider.Arguments.of(
                    SeccompInstallationState.Verified,
                    SeccompInstallationState.Uninitialized,
                    SeccompInstallationState.Verified,
                ),
                org.junit.jupiter.params.provider.Arguments.of(
                    dummyPrivLocked,
                    dummyFilterBuilt,
                    dummyPrivLocked,
                ),
                org.junit.jupiter.params.provider.Arguments.of(
                    dummyFilterBuilt,
                    SeccompInstallationState.SystemCallApplied,
                    SeccompInstallationState.SystemCallApplied,
                ),
                org.junit.jupiter.params.provider.Arguments.of(
                    SeccompInstallationState.FallbackPrctlApplied,
                    SeccompInstallationState.SystemCallApplied,
                    SeccompInstallationState.FallbackPrctlApplied,
                ),
                org.junit.jupiter.params.provider.Arguments.of(
                    dummyFailed,
                    SeccompInstallationState.Uninitialized,
                    dummyFailed,
                ),
            )

        @JvmStatic
        fun allowedSyscallsCombinations(): java.util.stream.Stream<org.junit.jupiter.params.provider.Arguments> =
            java.util.stream.Stream.of(
                org.junit.jupiter.params.provider.Arguments.of(
                    null,
                    setOf(io.mazewall.core.Syscall.READ, io.mazewall.core.Syscall.WRITE),
                    setOf(io.mazewall.core.Syscall.READ, io.mazewall.core.Syscall.WRITE),
                ),
                org.junit.jupiter.params.provider.Arguments.of(
                    setOf(io.mazewall.core.Syscall.READ, io.mazewall.core.Syscall.WRITE),
                    null,
                    setOf(io.mazewall.core.Syscall.READ, io.mazewall.core.Syscall.WRITE),
                ),
                org.junit.jupiter.params.provider.Arguments.of(
                    setOf(io.mazewall.core.Syscall.READ, io.mazewall.core.Syscall.WRITE, io.mazewall.core.Syscall.OPEN),
                    setOf(io.mazewall.core.Syscall.WRITE, io.mazewall.core.Syscall.CLOSE),
                    setOf(io.mazewall.core.Syscall.WRITE),
                ),
                org.junit.jupiter.params.provider.Arguments.of(
                    setOf(io.mazewall.core.Syscall.READ),
                    setOf(io.mazewall.core.Syscall.WRITE),
                    emptySet<io.mazewall.core.Syscall>(),
                ),
            )
    }

    @org.junit.jupiter.params.ParameterizedTest(name = "thread {0} + process {1} -> merged {2}")
    @org.junit.jupiter.params.provider.MethodSource("engineStateCombinations")
    fun `test composite mergeEngineStates resolution`(
        threadEngineState: SeccompInstallationState,
        processEngineState: SeccompInstallationState,
        expectedMergedEngineState: SeccompInstallationState,
    ) {
        ContainmentStateRegistry.threadState = ContainerState(engineState = threadEngineState)
        ContainmentStateRegistry.processState = ContainerState(engineState = processEngineState)

        val resolved = ContainmentStateRegistry.resolveCurrentState()
        assertEquals(expectedMergedEngineState, resolved.engineState)
    }

    @org.junit.jupiter.params.ParameterizedTest(name = "thread allows={0}, process allows={1} -> merged allows={2}")
    @org.junit.jupiter.params.provider.CsvSource(
        "true,  true,  true",
        "true,  false, false",
        "false, true,  false",
        "false, false, false",
    )
    fun `test composite security capability flags conjunction`(
        threadAllows: Boolean,
        processAllows: Boolean,
        expectedMergedAllows: Boolean,
    ) {
        ContainmentStateRegistry.threadState = ContainerState(
            allowsMmapExec = threadAllows,
            allowsNonThreadClone = threadAllows,
            allowsUnsafePrctl = threadAllows,
        )
        ContainmentStateRegistry.processState = ContainerState(
            allowsMmapExec = processAllows,
            allowsNonThreadClone = processAllows,
            allowsUnsafePrctl = processAllows,
        )

        val resolved = ContainmentStateRegistry.resolveCurrentState()
        assertEquals(expectedMergedAllows, resolved.allowsMmapExec)
        assertEquals(expectedMergedAllows, resolved.allowsNonThreadClone)
        assertEquals(expectedMergedAllows, resolved.allowsUnsafePrctl)
    }

    @org.junit.jupiter.params.ParameterizedTest(name = "thread {0} + process {1} -> merged {2}")
    @org.junit.jupiter.params.provider.MethodSource("allowedSyscallsCombinations")
    fun `test composite allowedSyscalls set intersection`(
        threadAllowed: Set<io.mazewall.core.Syscall>?,
        processAllowed: Set<io.mazewall.core.Syscall>?,
        expectedMerged: Set<io.mazewall.core.Syscall>?,
    ) {
        ContainmentStateRegistry.threadState = ContainerState(allowedSyscalls = threadAllowed)
        ContainmentStateRegistry.processState = ContainerState(allowedSyscalls = processAllowed)

        val resolved = ContainmentStateRegistry.resolveCurrentState()
        assertEquals(expectedMerged, resolved.allowedSyscalls)
    }

    private fun parseAction(name: String): io.mazewall.core.SeccompAction = when (name.trim()) {
        "ACT_KILL_PROCESS" -> io.mazewall.core.SeccompAction.ACT_KILL_PROCESS
        "ACT_KILL_THREAD" -> io.mazewall.core.SeccompAction.ACT_KILL_THREAD
        "ACT_TRAP" -> io.mazewall.core.SeccompAction.ACT_TRAP
        "ACT_ERRNO" -> io.mazewall.core.SeccompAction.ACT_ERRNO()
        "ACT_NOTIFY" -> io.mazewall.core.SeccompAction.ACT_NOTIFY
        "ACT_LOG" -> io.mazewall.core.SeccompAction.ACT_LOG
        else -> io.mazewall.core.SeccompAction.ACT_ALLOW
    }

    @org.junit.jupiter.params.ParameterizedTest(name = "thread action {0} vs process action {1} -> merged {2}")
    @org.junit.jupiter.params.provider.CsvSource(
        "ACT_ALLOW,        ACT_ERRNO,        ACT_ERRNO",
        "ACT_ERRNO,        ACT_ALLOW,        ACT_ERRNO",
        "ACT_ERRNO,        ACT_KILL_PROCESS, ACT_KILL_PROCESS",
        "ACT_KILL_PROCESS, ACT_ERRNO,        ACT_KILL_PROCESS",
        "ACT_KILL_THREAD,  ACT_KILL_PROCESS, ACT_KILL_PROCESS",
    )
    fun `test composite seccomp action priority resolution`(
        threadActionName: String,
        processActionName: String,
        expectedMergedActionName: String,
    ) {
        val threadAction = parseAction(threadActionName)
        val processAction = parseAction(processActionName)
        val expectedMergedAction = parseAction(expectedMergedActionName)

        ContainmentStateRegistry.threadState = ContainerState(
            defaultAction = threadAction,
            syscallActions = mapOf(io.mazewall.core.Syscall.READ to threadAction),
        )
        ContainmentStateRegistry.processState = ContainerState(
            defaultAction = processAction,
            syscallActions = mapOf(io.mazewall.core.Syscall.READ to processAction),
        )

        val resolved = ContainmentStateRegistry.resolveCurrentState()
        assertEquals(expectedMergedAction, resolved.defaultAction)
        assertEquals(expectedMergedAction, resolved.syscallActions[io.mazewall.core.Syscall.READ])
    }

    @Test
    fun `compile-time exhaustive check on SeccompInstallationState variants`() {
        val states: List<SeccompInstallationState> = listOf(
            SeccompInstallationState.Uninitialized,
            dummyFailed,
            dummyFilterBuilt,
            dummyPrivLocked,
            SeccompInstallationState.SystemCallApplied,
            SeccompInstallationState.FallbackPrctlApplied,
            SeccompInstallationState.Verified,
        )

        for (state in states) {
            when (state) {
                is SeccompInstallationState.Uninitialized -> Unit
                is SeccompInstallationState.Failed -> Unit
                is SeccompInstallationState.FilterBuilt -> Unit
                is SeccompInstallationState.PrivilegesLocked -> Unit
                is SeccompInstallationState.SystemCallApplied -> Unit
                is SeccompInstallationState.FallbackPrctlApplied -> Unit
                is SeccompInstallationState.Verified -> Unit
            }
        }
    }
}
