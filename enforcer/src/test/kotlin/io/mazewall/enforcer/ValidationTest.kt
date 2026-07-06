package io.mazewall.enforcer

import io.mazewall.Platform
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.util.concurrent.Executors

class ValidationTest {

    @Test
    fun `test validateLinuxAndNotVirtual passes on platform thread`() {
        assumeTrue(Platform.isLinux)
        // Should not throw
        validateLinuxAndNotVirtual()
    }

    @Test
    fun `test validateLinuxAndNotVirtual throws on virtual thread`() {
        assumeTrue(Platform.isLinux)
        val executor = Executors.newVirtualThreadPerTaskExecutor()
        val future = executor.submit {
            validateLinuxAndNotVirtual()
        }
        val e = assertThrows(java.util.concurrent.ExecutionException::class.java) {
            future.get()
        }
        assumeTrue(e.cause is IllegalStateException)
    }
}
