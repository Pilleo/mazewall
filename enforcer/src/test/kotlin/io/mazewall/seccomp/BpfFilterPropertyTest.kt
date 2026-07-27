package io.mazewall.seccomp
import kotlin.test.assertTrue
import io.mazewall.BpfFilter
import io.mazewall.Policy
import io.mazewall.core.Arch
import io.mazewall.core.Syscall
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test

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
