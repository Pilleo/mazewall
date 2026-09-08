package io.mazewall.enforcer

import io.mazewall.Policy
import io.mazewall.core.SeccompAction
import io.mazewall.core.Syscall
import io.mazewall.enforcer.engine.FilterInstallationPlanner
import io.mazewall.enforcer.state.ContainerState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import org.junit.jupiter.params.provider.ValueSource
import java.util.logging.Handler
import java.util.logging.Level
import java.util.logging.LogRecord
import java.util.logging.Logger
import java.util.stream.Stream

class FilterInstallationPlannerTest {
    companion object {
        @JvmStatic
        fun protectionCombinations(): Stream<Arguments> =
            Stream.of(
                Arguments.of(false, false, false, true),
                Arguments.of(false, false, true, true),
                Arguments.of(false, true, false, true),
                Arguments.of(false, true, true, true),
                Arguments.of(true, false, false, true),
                Arguments.of(true, false, true, true),
                Arguments.of(true, true, false, true),
                Arguments.of(true, true, true, false),
            )
    }

    @Test
    fun `identical whitelist does not require another filter`() {
        val policy = Policy
            .builder()
            .defaultAction(SeccompAction.ACT_KILL_PROCESS)
            .allow(Syscall.READ, Syscall.WRITE)
            .build()

        val state = ContainerState(
            syscallActions = mapOf(
                Syscall.READ to SeccompAction.ACT_ALLOW,
                Syscall.WRITE to SeccompAction.ACT_ALLOW,
            ),
            defaultAction = SeccompAction.ACT_KILL_PROCESS,
            allowsMmapExec = false,
            allowsNonThreadClone = false,
            allowsUnsafePrctl = false,
            filterDepth = 1,
        )

        val plan = FilterInstallationPlanner.calculateNewFilter(policy.definition, state)

        assertFalse(plan.needsNewFilter, "Should skip installing identical whitelist filter")
    }

    @Test
    fun `stricter syscall action creates an installation block`() {
        val policy = Policy
            .builder()
            .defaultAction(SeccompAction.ACT_ALLOW)
            .addAction(SeccompAction.ACT_KILL_PROCESS, Syscall.EXECVE)
            .build()

        val state = ContainerState(
            syscallActions = mapOf(
                Syscall.EXECVE to SeccompAction.ACT_LOG,
            ),
            defaultAction = SeccompAction.ACT_ALLOW,
            allowsMmapExec = true,
            allowsNonThreadClone = true,
            allowsUnsafePrctl = true,
            filterDepth = 1,
        )

        val plan = FilterInstallationPlanner.calculateNewFilter(policy.definition, state)

        assertTrue(plan.needsNewFilter, "Should install filter to escalate action severity")
        assertTrue(plan.newBlocks.containsKey(Syscall.EXECVE))
        assertEquals(SeccompAction.ACT_KILL_PROCESS, plan.newBlocks[Syscall.EXECVE])
    }

    @ParameterizedTest(name = "allow mmap={0}, clone={1}, prctl={2} -> new filter={3}")
    @MethodSource("protectionCombinations")
    fun `planner installs only missing argument protections`(
        allowMmapExec: Boolean,
        allowNonThreadClone: Boolean,
        allowUnsafePrctl: Boolean,
        expectedNewFilter: Boolean,
    ) {
        val builder = Policy.builder()
        if (allowMmapExec) builder.allowMmapExec()
        if (allowNonThreadClone) builder.allowNonThreadClone()
        if (allowUnsafePrctl) builder.allowUnsafePrctl()
        val policy = builder.build()
        val state = ContainerState(
            defaultAction = SeccompAction.ACT_ALLOW,
            allowsMmapExec = true,
            allowsNonThreadClone = true,
            allowsUnsafePrctl = true,
        )

        val plan = FilterInstallationPlanner.calculateNewFilter(policy.definition, state)

        assertEquals(expectedNewFilter, plan.needsNewFilter)
        assertTrue(plan.newBlocks.isEmpty())
    }

    @Test
    fun `restrictive default re-installs the policy when an existing whitelist is narrowed`() {
        val policy = Policy
            .builder()
            .defaultAction(SeccompAction.ACT_ERRNO())
            .allow(Syscall.READ)
            .build()
        val state = ContainerState(
            defaultAction = SeccompAction.ACT_ALLOW,
            allowedSyscalls = setOf(Syscall.READ, Syscall.WRITE),
            allowsMmapExec = false,
            allowsNonThreadClone = false,
            allowsUnsafePrctl = false,
        )

        val plan = FilterInstallationPlanner.calculateNewFilter(policy.definition, state)

        assertTrue(plan.needsNewFilter)
        assertEquals(policy.definition, plan.toInstall)
        assertEquals(SeccompAction.ACT_ERRNO(), plan.newBlocks[Syscall.WRITE])
        assertEquals(SeccompAction.ACT_ERRNO(), plan.newDefaultAction)
    }

    @ParameterizedTest(name = "depth {0} is below the installation limit")
    @ValueSource(ints = [0, 10, 11, 31])
    fun `verifyFilterDepth permits depths below 32`(depth: Int) {
        FilterInstallationPlanner.verifyFilterDepth(depth)
    }

    @ParameterizedTest(name = "depth {0} is rejected at the installation limit")
    @ValueSource(ints = [32, 33])
    fun `verifyFilterDepth rejects limit and overflow`(depth: Int) {
        assertThrows<IllegalStateException> {
            FilterInstallationPlanner.verifyFilterDepth(depth)
        }
    }

    @Test
    fun `verifyFilterDepth warns after ten installed filters`() {
        val logger = Logger.getLogger(FilterInstallationPlanner::class.java.name)
        val captured = mutableListOf<LogRecord>()
        val handler =
            object : Handler() {
                override fun publish(record: LogRecord) {
                    captured += record
                }

                override fun flush() = Unit

                override fun close() = Unit
            }
        handler.level = Level.ALL
        logger.addHandler(handler)
        try {
            FilterInstallationPlanner.verifyFilterDepth(10)
            assertTrue(captured.isEmpty(), "Depth 10 must not emit the early-warning log")

            FilterInstallationPlanner.verifyFilterDepth(11)
            assertEquals(1, captured.size)
            assertEquals(Level.WARNING, captured.single().level)
            assertTrue(captured.single().message.contains("11 seccomp filters"))
        } finally {
            logger.removeHandler(handler)
        }
    }
}
