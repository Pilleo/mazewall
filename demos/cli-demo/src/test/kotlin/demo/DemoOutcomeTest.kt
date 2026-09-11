package demo

import org.junit.jupiter.api.Test
import java.io.IOException
import java.nio.file.Files
import io.mazewall.core.Syscall
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

class DemoOutcomeTest {
    @Test
    fun `executor failure cannot disguise fatal error as an outcome`() {
        val fatal = OutOfMemoryError("fatal fixture")
        val thrown = assertFailsWith<OutOfMemoryError> {
            observeScenario("fatal", ExpectedResult.ALLOWED) { throw java.util.concurrent.ExecutionException(fatal) }
        }
        assertEquals(fatal, thrown)
    }
    @Test
    fun `expected allow and denial are successful but opposite observations fail`() {
        assertTrue(ScenarioResult("legitimate", ExpectedResult.ALLOWED, ScenarioOutcome.Completed("ok")).passed)
        assertTrue(ScenarioResult("attack", ExpectedResult.DENIED, ScenarioOutcome.Denied("EPERM")).passed)
        assertFalse(ScenarioResult("attack", ExpectedResult.DENIED, ScenarioOutcome.Completed("escaped")).passed)
        assertFalse(ScenarioResult("legitimate", ExpectedResult.ALLOWED, ScenarioOutcome.Denied("EPERM")).passed)
    }

    @Test
    fun `infrastructure failure never satisfies an expected denial`() {
        val failure = ScenarioResult(
            "attack",
            ExpectedResult.DENIED,
            ScenarioOutcome.InfrastructureFailure(IOException("daemon unavailable")),
        )

        assertFalse(failure.passed)
        assertTrue(DemoReport(listOf(failure)).summary().contains("INFRASTRUCTURE FAILURE"))
    }

    @Test
    fun `report succeeds only when every scenario matches its expectation`() {
        val success = ScenarioResult("workload", ExpectedResult.ALLOWED, ScenarioOutcome.Completed("ok"))
        val failure = ScenarioResult("attack", ExpectedResult.DENIED, ScenarioOutcome.Completed("escaped"))

        assertTrue(DemoReport(listOf(success)).passed)
        assertFalse(DemoReport(listOf(success, failure)).passed)
    }

    @Test
    fun `safe demo policy implements both advertised contracts`() {
        assertFalse(SafeRunner.policy.isSyscallAllowed(Syscall.EXECVE))
        assertFalse(SafeRunner.policy.isSyscallAllowed(Syscall.CONNECT))
        assertFalse(SafeRunner.policy.isSyscallAllowed(Syscall.SOCKET))
    }

    @Test
    fun `demo fixture is private and cannot replace a preexisting path or symlink`() {
        val parent = Files.createTempDirectory("demo-fixture-parent-")
        val existing = parent.resolve("existing").also { Files.writeString(it, "keep") }
        val target = parent.resolve("target").also { Files.writeString(it, "target") }
        val link = parent.resolve("link").also { Files.createSymbolicLink(it, target.fileName) }

        DemoFixture.create().use { fixture ->
            assertTrue(fixture.marker.toPath().startsWith(fixture.root))
        }

        assertEquals("keep", Files.readString(existing))
        assertTrue(Files.isSymbolicLink(link))
        parent.toFile().deleteRecursively()
    }

    @Test
    fun `fixture cleanup cannot follow a symlink inside the owned root`() {
        val external = Files.createTempDirectory("demo-external-")
        val keep = Files.writeString(external.resolve("keep"), "preserve")
        val fixture = DemoFixture.create()
        Files.createSymbolicLink(fixture.root.resolve("outside"), external)
        fixture.close()
        assertEquals("preserve", Files.readString(keep))
        assertFalse(Files.exists(fixture.root))
        Files.delete(keep)
        Files.delete(external)
    }
}
