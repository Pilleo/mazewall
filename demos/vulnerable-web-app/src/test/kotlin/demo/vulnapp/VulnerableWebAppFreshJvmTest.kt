package demo.vulnapp

import demo.vulnapp.config.ServiceExecutorOwner
import demo.vulnapp.service.DefaultFileService
import io.mazewall.EnabledIfLinuxAndSupported
import io.mazewall.IsolatedProcessTester
import io.mazewall.Policy
import io.mazewall.enforcer.api.ContainedExecutors
import io.mazewall.enforcer.diagnostics.ContainmentViolationDetector
import io.mazewall.profiler.Profiler
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.time.Duration
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VulnerableWebAppFreshJvmTest {
    @Test
    @EnabledIfLinuxAndSupported
    fun `unprotected exploit succeeds without damaging preexisting paths`() {
        IsolatedProcessTester.runIsolatedMethod(javaClass.name, "unprotectedBody")
    }

    @Test
    @EnabledIfLinuxAndSupported
    fun `protected exploit is denied while benign work succeeds`() {
        IsolatedProcessTester.runIsolatedMethod(javaClass.name, "protectedBody")
    }

    @Test
    @EnabledIfLinuxAndSupported
    fun `profiling records nonempty evidence`() {
        IsolatedProcessTester.runIsolatedMethod(javaClass.name, "profileBody")
    }

    @Suppress("unused")
    fun unprotectedBody() {
        val root = Files.createTempDirectory("mazewall-web-unprotected-")
        val marker = root.resolve("marker")
        Files.writeString(marker, "preserve")
        val created = root.resolve("created")
        try {
            DefaultFileService().runCommand("printf exploited > '${created}'")
            assertEquals("exploited", Files.readString(created))
            assertEquals("preserve", Files.readString(marker))
        } finally {
            Files.deleteIfExists(created)
            Files.deleteIfExists(marker)
            Files.deleteIfExists(root)
        }
    }

    @Suppress("unused")
    fun protectedBody() {
        val owner = ServiceExecutorOwner(Duration.ofSeconds(2))
        val protected = owner.own(
            ContainedExecutors.wrap(
                Executors.newSingleThreadExecutor(),
                Policy.threadLocalBuilder().base(Policy.NO_EXEC).allowMmapExec().build(),
            ),
        )
        try {
            assertEquals("benign", protected.call { "benign" })
            val failure = runCatching { protected.call { DefaultFileService().runCommand("true") } }.exceptionOrNull()
            assertTrue(failure is ExecutionException)
            assertTrue(ContainmentViolationDetector.findViolationCause(failure) != null)
        } finally {
            owner.stopAdmissionAndDrain()
        }
    }

    @Suppress("unused")
    fun profileBody() {
        val root = Files.createTempDirectory("mazewall-web-profile-")
        val input = root.resolve("input")
        Files.writeString(input, "evidence")
        val wrapper = Profiler.wrap(
            Executors.newSingleThreadExecutor(),
            Policy.threadLocalBuilder().base(Policy.PURE_COMPUTE_UNSAFE).allowMmapExec().build(),
        )
        try {
            assertEquals("evidence", wrapper.submit<String> { Files.readString(input) }.get())
            wrapper.shutdown()
            assertTrue(wrapper.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS))
            val deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos()
            while (wrapper.recentLogs.isEmpty() && System.nanoTime() < deadline) {
                java.util.concurrent.locks.LockSupport.parkNanos(Duration.ofMillis(10).toNanos())
            }
            val evidence = wrapper.compileBillOfBehavior()
            assertFalse(evidence.opens.isEmpty(), "profiling must produce filesystem evidence")
        } finally {
            wrapper.shutdownNow()
            Files.deleteIfExists(input)
            Files.deleteIfExists(root)
            Profiler.shutdown()
        }
    }
}
