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
import kotlin.test.assertTrue

class BpfFilterPropertyTest {
    @Test
    fun `bpf filter generation handles random combinations of syscalls without exceeding size limits`() {
        runBlocking {
            Syscall.entries.chunked(10).forEach { randomSyscalls ->
                val policy = Policy
                    .builder()
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
