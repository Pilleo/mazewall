package io.mazewall.profiler.strace

import io.mazewall.profiler.TraceableWorkload
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class StraceWorkloadRunnerTest {
    @Test
    fun `runner instantiates and executes the requested workload`() {
        RecordingWorkload.runs = 0

        StraceWorkloadRunner.main(arrayOf(RecordingWorkload::class.java.name))

        assertEquals(1, RecordingWorkload.runs)
    }

    @Test
    fun `runner rejects a missing workload class`() {
        assertThrows(IllegalArgumentException::class.java) {
            StraceWorkloadRunner.main(emptyArray())
        }
    }

    @Test
    fun `runner rejects a class that is not a workload`() {
        assertThrows(IllegalArgumentException::class.java) {
            StraceWorkloadRunner.main(arrayOf(String::class.java.name))
        }
    }
}

class RecordingWorkload : TraceableWorkload {
    override fun run() {
        runs += 1
    }

    companion object {
        var runs: Int = 0
    }
}
