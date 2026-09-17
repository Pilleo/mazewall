package io.mazewall.profiler.api

import io.mazewall.enforcer.api.RuntimeProfile
import io.mazewall.platform.syscall.Syscall
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PortalWorkerPolicyTest {
    @Test
    fun `incomplete coverage cannot compile dedicated worker policies`() {
        val result = ProfilingResult(Unit, BillOfBehavior(), emptyMap())
        assertFailsWith<IncompleteProfileException> { result.toDedicatedWorkerPolicies() }
    }

    @Test
    fun `complete coverage compiles worker deny-exec deny-uring and deny-network policies`() {
        val coverage =
            ProfilingCoverage.infer(
                strategy = ProfileStrategy.USER_NOTIF,
                strategyReason = "test",
                processWide = true,
                observations = emptyList(),
                stacks = StackAttribution.SKIPPED,
                droppedEvents = 0,
                drainComplete = true,
                environment = ProfileEnvironment("test", EbpfLoad.Denied("test")),
            )
        assertTrue(coverage.complete)
        val policies = ProfilingResult(Unit, BillOfBehavior(), emptyMap(), coverage).toDedicatedWorkerPolicies()
        assertTrue(policies.any { policy -> !policy.isSyscallAllowed(Syscall.EXECVE) })
        assertTrue(policies.any { policy -> !policy.isSyscallAllowed(Syscall.IO_URING_SETUP) })
        assertTrue(policies.any { policy -> !policy.isSyscallAllowed(Syscall.CONNECT) })
        assertFalse(
            io.mazewall.enforcer.api.ProcessPolicies
                .denyProcessCreation(RuntimeProfile.HOTSPOT_JIT)
                .isSyscallAllowed(Syscall.IO_URING_ENTER),
        )
    }

    @Test
    fun `plain SBoB document is incomplete`() {
        assertFailsWith<IncompleteProfileException> {
            ProfilingResult.dedicatedWorkerPoliciesFromDocument("""{"syscalls":[]}""")
        }
    }

    @Test
    fun `measured complete document compiles dedicated worker policies`() {
        val coverage =
            ProfilingCoverage.infer(
                strategy = ProfileStrategy.USER_NOTIF,
                strategyReason = "test",
                processWide = true,
                observations = emptyList(),
                stacks = StackAttribution.SKIPPED,
                droppedEvents = 0,
                drainComplete = true,
                environment = ProfileEnvironment("test", EbpfLoad.Denied("test")),
            )
        val policies =
            ProfilingResult.dedicatedWorkerPoliciesFromDocument(
                ProfilingResult(Unit, BillOfBehavior(), emptyMap(), coverage).toProfileDocumentJson(),
            )
        assertTrue(policies.any { policy -> !policy.isSyscallAllowed(Syscall.EXECVE) })
    }
}
