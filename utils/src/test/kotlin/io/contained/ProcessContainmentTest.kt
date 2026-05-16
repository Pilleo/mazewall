package io.contained

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import java.io.File
import kotlin.test.assertTrue
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** 
 * Helper object used to run seccomp installation tests in an isolated JVM process.
 * This prevents the Gradle Test Worker from being permanently "poisoned" by
 * irreversible seccomp filters.
 */
object SeccompIsolatedTestApp {
    @JvmStatic
    fun main(args: Array<String>) {
        if (!Platform.isSupported()) System.exit(0)
        
        val mode = args.firstOrNull() ?: "process-wide"
        when (mode) {
            "process-wide" -> testProcessWide()
            "thread-depth" -> testThreadDepth()
            else -> System.exit(1)
        }
    }

    private fun testProcessWide() {
        val safeGlobalPolicy = Policy.builder()
            .block(Syscall.EXECVE, Syscall.EXECVEAT)
            .allowMmapExec()
            .allowNonThreadClone()
            .build()

        ContainedExecutors.installOnProcess(safeGlobalPolicy)
        try {
            Runtime.getRuntime().exec(arrayOf("echo", "should-fail"))
            System.exit(1) // Should have failed
        } catch (e: Exception) {
            if (ContainedExecutors.isContainmentViolation(e)) {
                System.exit(0) // Success
            }
            e.printStackTrace()
            System.exit(2)
        }
    }

    private fun testThreadDepth() {
        val safeSyscalls = Syscall.entries.filter { 
            it !in listOf(
                Syscall.MMAP, Syscall.MPROTECT, Syscall.PRCTL, Syscall.IOCTL, 
                Syscall.OPEN, Syscall.OPENAT, Syscall.OPENAT2, Syscall.GETTID,
                Syscall.EXECVE, Syscall.EXECVEAT, Syscall.CLONE, Syscall.CLONE3
            ) 
        }

        // Install 32 thread-local filters
        for (i in 0 until 32) {
            ContainedExecutors.installOnCurrentThread(
                Policy.builder().block(safeSyscalls[i]).allowMmapExec().allowNonThreadClone().build()
            )
        }

        // The 33rd installation should fail
        try {
            ContainedExecutors.installOnCurrentThread(
                Policy.builder().block(safeSyscalls[32]).allowMmapExec().allowNonThreadClone().build()
            )
            System.exit(1) // Should have failed
        } catch (e: IllegalStateException) {
            if (e.message!!.contains("32 seccomp filters")) {
                System.exit(0) // Success
            }
            System.exit(3)
        }
    }
}

class ProcessContainmentTest {

    private fun runIsolatedTest(mode: String) {
        val javaHome = System.getProperty("java.home")
        val javaBin = javaHome + File.separator + "bin" + File.separator + "java"
        val classpath = System.getProperty("java.class.path")

        val builder = ProcessBuilder(
            javaBin, "-cp", classpath,
            "--enable-native-access=ALL-UNNAMED",
            "io.contained.SeccompIsolatedTestApp",
            mode
        )
        builder.inheritIO()
        val process = builder.start()
        val exitCode = process.waitFor()
        assertEquals(0, exitCode, "Isolated test process ($mode) failed with exit code $exitCode")
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    fun `installOnProcess applies containment globally`() {
        if (!Platform.isSupported()) return
        runIsolatedTest("process-wide")
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    fun `thread-local installation respects filter depth`() {
        if (!Platform.isSupported()) return
        runIsolatedTest("thread-depth")
    }
}
