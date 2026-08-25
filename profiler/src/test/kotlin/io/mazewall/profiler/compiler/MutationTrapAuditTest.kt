package io.mazewall.profiler.compiler

import io.mazewall.BpfFilter
import io.mazewall.Policy
import io.mazewall.core.Arch
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MutationTrapAuditTest {

    private val arch = Arch.AMD64

    @Test
    fun `PURE_COMPUTE_UNSAFE profiling filter traps all mutation syscalls`() {
        val program = BpfFilter.build(arch, Policy.PURE_COMPUTE_UNSAFE.definition, profilingMode = true).instructions
        assertEquals(emptyList(), MutationTrapAudit.untrapped(program, arch))
    }

    @Test
    fun `a floor missing truncate is reported as untrapped`() {
        // Simulate a narrow floor: allow-list style default EPERM with only read allowed.
        val program = BpfFilter.build(
            arch,
            Policy.builder()
                .defaultAction(io.mazewall.core.SeccompAction.ACT_ALLOW)
                .block(io.mazewall.core.Syscall.CONNECT)
                .build()
                .definition,
        ).instructions

        val untrapped = MutationTrapAudit.untrapped(program, arch)
        assertTrue(untrapped.any { it.startsWith("truncate") }, "truncate must be reported untrapped: $untrapped")
        assertTrue(untrapped.any { it.startsWith("creat") }, "creat must be reported untrapped: $untrapped")
    }

    @Test
    fun `unmapped mutation nrs are excluded rather than flagged`() {
        // CREAT does not exist on aarch64; the audit must not demand the impossible.
        val program = BpfFilter.build(Arch.AARCH64, Policy.PURE_COMPUTE_UNSAFE.definition, profilingMode = true).instructions
        val untrapped = MutationTrapAudit.untrapped(program, Arch.AARCH64)
        assertTrue(untrapped.none { it.startsWith("creat(") }, "unmapped creat(nr=-1) must be excluded: $untrapped")
    }
}
