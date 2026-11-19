package io.mazewall.seccomp

import io.mazewall.enforcer.api.*
import io.mazewall.enforcer.api.Policy
import io.mazewall.enforcer.internal.ffi.RealPlatformProvider
import io.mazewall.enforcer.internal.seccomp.BpfFilter
import io.mazewall.platform.fd.*
import io.mazewall.platform.lifecycle.*
import io.mazewall.platform.native.*
import io.mazewall.platform.process.*
import io.mazewall.platform.socket.*
import io.mazewall.platform.syscall.*
import io.mazewall.platform.syscall.Arch
import io.mazewall.platform.syscall.Syscall
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BpfFilterPropertyTest {
    @Test
    fun `dispatch boundaries preserve explicit default and profiling verdicts on both architectures`() {
        val actionsAndCodes = listOf(
            SeccompAction.ACT_ERRNO(13) to 0x0005000d,
            SeccompAction.ACT_TRACE(17) to 0x7ff00011,
            SeccompAction.ACT_KILL_PROCESS to Int.MIN_VALUE,
        )
        for (arch in listOf(Arch.AMD64, Arch.AARCH64)) {
            for (count in listOf(0, 1, 2, 3, 31, 32, 33, 99, 100, 101, 300)) {
                for ((default, defaultCode) in listOf(SeccompAction.ACT_ALLOW to 0x7fff0000, SeccompAction.ACT_ERRNO() to 0x00050001)) {
                    for (profiling in listOf(false, true)) {
                        val actions = (0 until count).associate { index -> (1000 + index) to actionsAndCodes[index % 3].first }
                        val program = BpfStaticVerifier.verify(
                            BpfFilter.buildFromActions(arch, actions, default, DefaultSyscallInspectionPipeline(emptyList()), profilingMode = profiling),
                        )
                        for (nr in 999..1000 + count) {
                            val expected = if (nr in actions) actionsAndCodes[(nr - 1000) % 3].second else defaultCode
                            val profiled = if (profiling && expected ushr 16 == 5) 0x7fc00000 else expected
                            assertEquals(profiled, BpfSimulator.simulate(program.instructions, nr, arch), "${arch.name} count=$count nr=$nr profiling=$profiling")
                        }
                        for (nr in BpfFilter.getJvmCriticalNrs(arch)) {
                            assertEquals(0x7fff0000, BpfSimulator.simulate(program.instructions, nr, arch))
                        }
                    }
                }
            }
        }
    }

    @Test
    fun `bpf filter generation handles random combinations of syscalls without exceeding size limits`() {
        runBlocking {
            Syscall.entries.chunked(10).forEach { randomSyscalls ->
                val policy = ProcessPolicyBuilder
                    .denyList(RuntimeProfile.NATIVE_IMAGE)
                    .apply {
                        randomSyscalls.forEach { block(it) }
                    }.build()

                val filter = BpfFilter.build(Arch.AMD64, policy.definition).instructions

                // Linux limits BPF programs to 4096 instructions
                assertTrue(filter.size < 4096, "Filter size ${filter.size} should be less than 4096")
            }
        }
    }
}
