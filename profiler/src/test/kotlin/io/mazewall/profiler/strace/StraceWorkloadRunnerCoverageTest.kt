package io.mazewall.profiler.strace

import io.mazewall.profiler.TraceableWorkload
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DummyWorkload : TraceableWorkload {
    var ran = false
    override fun run() {
        ran = true
    }
}

class StraceWorkloadRunnerCoverageTest {

    @Test
    fun `test main runs workload`() {
        val args = arrayOf(DummyWorkload::class.java.name)
        // DummyWorkload is instantiated internally, we can't easily assert ran here,
        // but it covers the instantiation logic.
        StraceWorkloadRunner.main(args)
    }

    @Test
    fun `test main throws when missing args`() {
        assertThrows(IllegalArgumentException::class.java) {
            StraceWorkloadRunner.main(emptyArray())
        }
    }

    @Test
    fun `test main throws when wrong class`() {
        val args = arrayOf(String::class.java.name)
        assertThrows(IllegalArgumentException::class.java) {
            StraceWorkloadRunner.main(args)
        }
    }
}
