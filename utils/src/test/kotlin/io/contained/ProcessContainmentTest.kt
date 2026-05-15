package io.contained

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

class ProcessContainmentTest {

    @Test
    @EnabledOnOs(OS.LINUX)
    fun `installOnProcess applies containment globally`() {
        if (!Platform.isSupported()) return

        // Use a policy that blocks something obvious (EXECVE) but allows the JVM to 
        // keep its JIT/threads happy during the test.
        val safeGlobalPolicy = Policy.builder()
            .block(Syscall.EXECVE, Syscall.EXECVEAT)
            .allowMmapExec()
            .allowNonThreadClone()
            .build()

        // Spawn a thread to install process-wide containment
        val installerThread = Thread {
            ContainedExecutors.installOnProcess(safeGlobalPolicy)
        }
        installerThread.start()
        installerThread.join()

        // Even though the filter was installed by a different thread,
        // it should apply to THIS thread because of TSYNC.
        val ex = assertFailsWith<Exception> {
            Runtime.getRuntime().exec(arrayOf("echo", "should-fail"))
        }

        assertTrue(ContainedExecutors.isContainmentViolation(ex), "Expected containment violation, got $ex")
    }
}
