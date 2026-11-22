package io.mazewall.enforcer

import io.mazewall.enforcer.api.*
import io.mazewall.enforcer.api.Policy
import io.mazewall.enforcer.engine.FilterInstallationPlanner
import io.mazewall.enforcer.internal.seccomp.BpfFilter
import io.mazewall.enforcer.state.ContainerState
import io.mazewall.enforcer.state.ContainmentStateRegistry
import io.mazewall.ffi.NativeConstants
import io.mazewall.platform.fd.*
import io.mazewall.platform.lifecycle.*
import io.mazewall.platform.native.*
import io.mazewall.platform.process.*
import io.mazewall.platform.socket.*
import io.mazewall.platform.syscall.*
import io.mazewall.platform.syscall.Arch
import io.mazewall.platform.syscall.SeccompAction
import io.mazewall.platform.syscall.Syscall
import io.mazewall.seccomp.BpfSimulator
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
import kotlin.test.assertIs

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
        val policy = ProcessPolicyBuilder
            .denyList(RuntimeProfile.NATIVE_IMAGE)
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
        val policy = ProcessPolicyBuilder
            .denyList(RuntimeProfile.NATIVE_IMAGE)
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
        val builder = ProcessPolicyBuilder.denyList(RuntimeProfile.NATIVE_IMAGE)
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
        val initialPolicy =
            ProcessPolicyBuilder
                .denyList(RuntimeProfile.NATIVE_IMAGE)
                .defaultAction(SeccompAction.ACT_ERRNO())
                .allow(Syscall.READ, Syscall.WRITE)
                .build()
                .definition
        val initialDecision = assertIs<FilterInstallationPlanner.PlanDecision.Install>(
            FilterInstallationPlanner.plan(initialPolicy, ContainerState()),
        )
        val installedState = initialDecision.nextProjection

        val narrowedPolicy =
            ProcessPolicyBuilder
                .denyList(RuntimeProfile.NATIVE_IMAGE)
                .defaultAction(SeccompAction.ACT_ERRNO())
                .allow(Syscall.READ)
                .build()
                .definition

        val plan = FilterInstallationPlanner.calculateNewFilter(narrowedPolicy, installedState)

        assertTrue(plan.needsNewFilter)
        assertEquals(SeccompAction.ACT_ERRNO(), plan.newBlocks[Syscall.WRITE])
        assertEquals(SeccompAction.ACT_ERRNO(), plan.newDefaultAction)
        assertEquals(SeccompAction.ACT_ALLOW, plan.toInstall.defaultAction)

        val narrowedState = installedState.withAppliedFilter(plan.toInstall)
        assertEquals(setOf(Syscall.READ), narrowedState.allowedSyscalls)
        assertEquals(SeccompAction.ACT_ERRNO(), narrowedState.effectiveAction(Syscall.WRITE))
    }

    @Test
    fun `ACT_ERRNO escalates over ACT_TRACE using canonical restrictivenessRank`() {
        val policy =
            ProcessPolicyBuilder
                .denyList(RuntimeProfile.NATIVE_IMAGE)
                .defaultAction(SeccompAction.ACT_ALLOW)
                .addAction(SeccompAction.ACT_ERRNO(), Syscall.GETPPID)
                .build()
        val state =
            ContainerState(
                syscallActions = mapOf(Syscall.GETPPID to SeccompAction.ACT_TRACE(traceId = 7)),
                defaultAction = SeccompAction.ACT_ALLOW,
                allowsMmapExec = false,
                allowsNonThreadClone = false,
                allowsUnsafePrctl = false,
                filterDepth = 1,
            )

        val plan = FilterInstallationPlanner.calculateNewFilter(policy.definition, state)

        assertTrue(plan.needsNewFilter, "ACT_ERRNO must escalate over ACT_TRACE even though both have priority 4")
        assertEquals(SeccompAction.ACT_ERRNO(), plan.newBlocks[Syscall.GETPPID])
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

    @Test
    fun `calculateNewFilter follows canonical action rank for explicit and default transitions`() {
        val eperm = SeccompAction.ACT_ERRNO(io.mazewall.ffi.NativeConstants.EPERM)
        val eacces = SeccompAction.ACT_ERRNO(io.mazewall.ffi.NativeConstants.EACCES)
        val notify = SeccompAction.ACT_NOTIFY
        val trace = SeccompAction.ACT_TRACE(traceId = 7)
        val log = SeccompAction.ACT_LOG

        val explicitEscalations =
            listOf(
                trace to eacces,
                trace to notify,
                notify to eacces,
                log to trace,
            )
        for ((existing, incoming) in explicitEscalations) {
            val policy =
                ProcessPolicyBuilder
                    .denyList(RuntimeProfile.NATIVE_IMAGE)
                    .defaultAction(SeccompAction.ACT_ALLOW)
                    .addAction(incoming, Syscall.GETPPID)
                    .build()
                    .definition
            val state =
                ContainerState(
                    syscallActions = mapOf(Syscall.GETPPID to existing),
                    defaultAction = SeccompAction.ACT_ALLOW,
                    allowsMmapExec = false,
                    allowsNonThreadClone = false,
                    allowsUnsafePrctl = false,
                    filterDepth = 1,
                )
            val plan = FilterInstallationPlanner.calculateNewFilter(policy, state)
            assertTrue(plan.needsNewFilter, "Expected escalation $existing -> $incoming")
            assertEquals(incoming, plan.newBlocks[Syscall.GETPPID], "Expected $incoming block over $existing")
        }

        val defaultEscalations =
            listOf(
                trace to eacces,
                trace to notify,
                notify to eacces,
            )
        for ((existingDefault, incomingDefault) in defaultEscalations) {
            val policy =
                ProcessPolicyBuilder
                    .denyList(RuntimeProfile.NATIVE_IMAGE)
                    .defaultAction(incomingDefault)
                    .build()
                    .definition
            val state =
                ContainerState(
                    defaultAction = existingDefault,
                    allowsMmapExec = false,
                    allowsNonThreadClone = false,
                    allowsUnsafePrctl = false,
                    filterDepth = 1,
                )
            val plan = FilterInstallationPlanner.calculateNewFilter(policy, state)
            assertTrue(plan.needsNewFilter, "Expected default escalation $existingDefault -> $incomingDefault")
            assertEquals(incomingDefault, plan.newDefaultAction)
        }

        val nonEscalations =
            listOf(
                eperm to trace,
                notify to trace,
                eperm to notify,
                eperm to eacces,
            )
        for ((existing, incoming) in nonEscalations) {
            val explicitPolicy =
                ProcessPolicyBuilder
                    .denyList(RuntimeProfile.NATIVE_IMAGE)
                    .defaultAction(SeccompAction.ACT_ALLOW)
                    .addAction(incoming, Syscall.GETPPID)
                    .build()
                    .definition
            val explicitState =
                ContainerState(
                    syscallActions = mapOf(Syscall.GETPPID to existing),
                    defaultAction = SeccompAction.ACT_ALLOW,
                    allowsMmapExec = false,
                    allowsNonThreadClone = false,
                    allowsUnsafePrctl = false,
                    filterDepth = 1,
                )
            val explicitPlan = FilterInstallationPlanner.calculateNewFilter(explicitPolicy, explicitState)
            assertFalse(explicitPlan.needsNewFilter, "Did not expect escalation $existing -> $incoming")
            assertTrue(explicitPlan.newBlocks.isEmpty())

            val defaultPolicy =
                ProcessPolicyBuilder
                    .denyList(RuntimeProfile.NATIVE_IMAGE)
                    .defaultAction(incoming)
                    .build()
                    .definition
            val defaultState =
                ContainerState(
                    defaultAction = existing,
                    allowsMmapExec = false,
                    allowsNonThreadClone = false,
                    allowsUnsafePrctl = false,
                    filterDepth = 1,
                )
            val defaultPlan = FilterInstallationPlanner.calculateNewFilter(defaultPolicy, defaultState)
            assertFalse(defaultPlan.needsNewFilter, "Did not expect default escalation $existing -> $incoming")
            assertEquals(existing, defaultPlan.newDefaultAction)
        }
    }

    @Test
    fun `planner and stacked compiled BPF match independent intersection across narrowing and default escalations`() {
        fun assertStackedMatchesIndependentIntersection(
            first: PolicyDefinition<*>,
            second: PolicyDefinition<*>,
            expectSecondInstall: Boolean,
            syscallsToCheck: List<Syscall>,
        ): ContainerState {
            var state = ContainerState()
            val installedFragments = mutableListOf<PolicyDefinition<*>>()

            val d1 =
                assertIs<FilterInstallationPlanner.PlanDecision.Install>(
                    FilterInstallationPlanner.plan(first, state),
                )
            installedFragments += d1.fragment
            state = d1.nextProjection

            val d2 = FilterInstallationPlanner.plan(second, state)
            if (expectSecondInstall) {
                val install = assertIs<FilterInstallationPlanner.PlanDecision.Install>(d2)
                installedFragments += install.fragment
                state = install.nextProjection
                // Repeating the already-installed second policy must now be satisfied.
                assertIs<FilterInstallationPlanner.PlanDecision.AlreadySatisfied>(
                    FilterInstallationPlanner.plan(second, state),
                )
            } else {
                assertIs<FilterInstallationPlanner.PlanDecision.AlreadySatisfied>(d2)
            }

            for (arch in listOf(Arch.AMD64, Arch.AARCH64)) {
                val baselineFirst = BpfFilter.build(arch, first).instructions
                val baselineSecond = BpfFilter.build(arch, second).instructions
                val stackedPrograms = installedFragments.map { BpfFilter.build(arch, it).instructions }

                val probeNumbers = syscallsToCheck.map { it.numberFor(arch) }.filter { it >= 0 } + 9999
                for (nr in probeNumbers) {
                    val expectedVerdict =
                        minOf(
                            BpfSimulator.simulate(baselineFirst, nr, arch)!!,
                            BpfSimulator.simulate(baselineSecond, nr, arch)!!,
                        )
                    val stackedVerdict =
                        stackedPrograms.minOf { program -> BpfSimulator.simulate(program, nr, arch)!! }
                    assertEquals(expectedVerdict, stackedVerdict, "Stacked mismatch for nr=$nr on ${arch.name}")
                }
            }
            return state
        }

        // 1. Trigger: ProcessPolicyBuilder.allowList(HOTSPOT_JIT).allow(GETPPID) narrowed by removing GETPPID.
        val allowWithGetppid =
            ProcessPolicyBuilder
                .allowList(RuntimeProfile.HOTSPOT_JIT)
                .allow(Syscall.GETPPID)
                .build()
                .definition
        val allowWithoutGetppid =
            ProcessPolicyBuilder
                .allowList(RuntimeProfile.HOTSPOT_JIT)
                .build()
                .definition
        val narrowedState =
            assertStackedMatchesIndependentIntersection(
                first = allowWithGetppid,
                second = allowWithoutGetppid,
                expectSecondInstall = true,
                syscallsToCheck = listOf(Syscall.READ, Syscall.GETPID, Syscall.GETPPID, Syscall.GETUID),
            )
        assertEquals(2, narrowedState.filterDepth)
        assertEquals(SeccompAction.ACT_ERRNO(), narrowedState.effectiveAction(Syscall.GETPPID))
        assertEquals(SeccompAction.ACT_ALLOW, narrowedState.effectiveAction(Syscall.READ))
        assertFalse(Syscall.GETPPID in narrowedState.allowedSyscalls.orEmpty())

        // 2. LOG-default -> ERRNO-default with identical explicit ALLOW set.
        val logDefault =
            ProcessPolicyBuilder
                .denyList(RuntimeProfile.NATIVE_IMAGE)
                .defaultAction(SeccompAction.ACT_LOG)
                .allow(Syscall.READ, Syscall.GETPID)
                .build()
                .definition
        val errnoDefault =
            ProcessPolicyBuilder
                .denyList(RuntimeProfile.NATIVE_IMAGE)
                .defaultAction(SeccompAction.ACT_ERRNO())
                .allow(Syscall.READ, Syscall.GETPID)
                .build()
                .definition
        val escalatedFromLog =
            assertStackedMatchesIndependentIntersection(
                first = logDefault,
                second = errnoDefault,
                expectSecondInstall = true,
                syscallsToCheck = listOf(Syscall.READ, Syscall.GETPID, Syscall.GETPPID, Syscall.GETUID),
            )
        assertEquals(SeccompAction.ACT_ERRNO(), escalatedFromLog.defaultAction)
        assertEquals(SeccompAction.ACT_ALLOW, escalatedFromLog.effectiveAction(Syscall.READ))

        // 3. ERRNO-default -> KILL_PROCESS-default with identical explicit ALLOW set.
        val killDefault =
            ProcessPolicyBuilder
                .denyList(RuntimeProfile.NATIVE_IMAGE)
                .defaultAction(SeccompAction.ACT_KILL_PROCESS)
                .allow(Syscall.READ, Syscall.GETPID)
                .build()
                .definition
        val escalatedToKill =
            assertStackedMatchesIndependentIntersection(
                first = errnoDefault,
                second = killDefault,
                expectSecondInstall = true,
                syscallsToCheck = listOf(Syscall.READ, Syscall.GETPID, Syscall.GETPPID, Syscall.GETUID),
            )
        assertEquals(SeccompAction.ACT_KILL_PROCESS, escalatedToKill.defaultAction)
        assertEquals(SeccompAction.ACT_ALLOW, escalatedToKill.effectiveAction(Syscall.READ))

        // 4. Permissive non-ALLOW exception (ACT_LOG) removed under restrictive ERRNO default.
        val errnoWithWriteLog =
            ProcessPolicyBuilder
                .denyList(RuntimeProfile.NATIVE_IMAGE)
                .defaultAction(SeccompAction.ACT_ERRNO())
                .allow(Syscall.READ, Syscall.GETPID)
                .addAction(SeccompAction.ACT_LOG, Syscall.WRITE)
                .build()
                .definition
        val errnoWithoutWriteLog =
            ProcessPolicyBuilder
                .denyList(RuntimeProfile.NATIVE_IMAGE)
                .defaultAction(SeccompAction.ACT_ERRNO())
                .allow(Syscall.READ, Syscall.GETPID)
                .build()
                .definition
        val narrowedLogState =
            assertStackedMatchesIndependentIntersection(
                first = errnoWithWriteLog,
                second = errnoWithoutWriteLog,
                expectSecondInstall = true,
                syscallsToCheck = listOf(Syscall.READ, Syscall.WRITE, Syscall.GETPPID),
            )
        assertEquals(SeccompAction.ACT_ERRNO(), narrowedLogState.effectiveAction(Syscall.WRITE))

        // 5. Identical and superset allow-list controls remain AlreadySatisfied.
        assertStackedMatchesIndependentIntersection(
            first = allowWithoutGetppid,
            second = allowWithoutGetppid,
            expectSecondInstall = false,
            syscallsToCheck = listOf(Syscall.READ, Syscall.GETPID, Syscall.GETPPID),
        )
        assertStackedMatchesIndependentIntersection(
            first = allowWithoutGetppid,
            second = allowWithGetppid,
            expectSecondInstall = false,
            syscallsToCheck = listOf(Syscall.READ, Syscall.GETPID, Syscall.GETPPID),
        )
    }

    @Test
    fun `thread allow-list narrowing against process baseline preserves process baseline and denies removed syscall`() {
        val processPolicy =
            ProcessPolicyBuilder
                .allowList(RuntimeProfile.HOTSPOT_JIT)
                .allow(Syscall.GETPPID)
                .build()
                .definition
        val threadPolicy =
            ProcessPolicyBuilder
                .allowList(RuntimeProfile.HOTSPOT_JIT)
                .build()
                .definition

        ContainmentStateRegistry.processState = ContainerState()
        ContainmentStateRegistry.threadState = ContainerState()
        try {
            val processDecision =
                assertIs<FilterInstallationPlanner.PlanDecision.Install>(
                    FilterInstallationPlanner.plan(processPolicy, ContainmentStateRegistry.processState),
                )
            ContainmentStateRegistry.processState = processDecision.nextProjection

            val mergedBeforeThread = ContainmentStateRegistry.resolveCurrentState()
            val threadDecision =
                assertIs<FilterInstallationPlanner.PlanDecision.Install>(
                    FilterInstallationPlanner.plan(threadPolicy, mergedBeforeThread),
                )
            // Thread installs only the narrowed syscall block, without absorbing process ERRNO default.
            assertEquals(SeccompAction.ACT_ALLOW, threadDecision.fragment.defaultAction)
            assertEquals(
                mapOf(Syscall.GETPPID to SeccompAction.ACT_ERRNO(NativeConstants.EPERM)),
                threadDecision.newBlocks,
            )

            ContainmentStateRegistry.threadState =
                ContainmentStateRegistry.threadState.withAppliedFilter(threadDecision.fragment)
            val mergedAfterThread = ContainmentStateRegistry.resolveCurrentState()
            assertEquals(SeccompAction.ACT_ERRNO(), mergedAfterThread.effectiveAction(Syscall.GETPPID))
            assertEquals(SeccompAction.ACT_ALLOW, mergedAfterThread.effectiveAction(Syscall.READ))
            assertEquals(2, mergedAfterThread.filterDepth)
        } finally {
            ContainmentStateRegistry.threadState = ContainerState()
            ContainmentStateRegistry.processState = ContainerState()
        }
    }

    @Test
    fun `same-rank payload ties preserve first-policy AlreadySatisfied semantics`() {
        val tiePairs =
            listOf(
                SeccompAction.ACT_ERRNO(NativeConstants.EACCES) to SeccompAction.ACT_ERRNO(NativeConstants.EPERM),
                SeccompAction.ACT_ERRNO(NativeConstants.EPERM) to SeccompAction.ACT_ERRNO(NativeConstants.EACCES),
                SeccompAction.ACT_TRACE(1) to SeccompAction.ACT_TRACE(2),
                SeccompAction.ACT_TRACE(2) to SeccompAction.ACT_TRACE(1),
            )

        for ((existing, incoming) in tiePairs) {
            val defaultState =
                ContainerState(
                    filterDepth = 1,
                    defaultAction = existing,
                    syscallActions = mapOf(Syscall.READ to SeccompAction.ACT_ALLOW),
                    allowsMmapExec = false,
                    allowsNonThreadClone = false,
                    allowsUnsafePrctl = false,
                )
            val incomingDefaultPolicy =
                ProcessPolicyBuilder
                    .denyList(RuntimeProfile.NATIVE_IMAGE)
                    .defaultAction(incoming)
                    .allow(Syscall.READ)
                    .build()
                    .definition
            assertIs<FilterInstallationPlanner.PlanDecision.AlreadySatisfied>(
                FilterInstallationPlanner.plan(incomingDefaultPolicy, defaultState),
            )

            val syscallState =
                ContainerState(
                    filterDepth = 1,
                    defaultAction = SeccompAction.ACT_ALLOW,
                    syscallActions = mapOf(Syscall.GETPPID to existing),
                    allowsMmapExec = false,
                    allowsNonThreadClone = false,
                    allowsUnsafePrctl = false,
                )
            val incomingSyscallPolicy =
                ProcessPolicyBuilder
                    .denyList(RuntimeProfile.NATIVE_IMAGE)
                    .defaultAction(SeccompAction.ACT_ALLOW)
                    .addAction(incoming, Syscall.GETPPID)
                    .build()
                    .definition
            assertIs<FilterInstallationPlanner.PlanDecision.AlreadySatisfied>(
                FilterInstallationPlanner.plan(incomingSyscallPolicy, syscallState),
            )
        }
    }
}
