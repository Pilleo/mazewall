package demo.vulnapp.config

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class EnforcementEvidenceTest {
    @TempDir
    lateinit var root: Path

    @Test
    fun `enforcement rejects incomplete mounted generation`() {
        val target = root.resolve("sbob.json")
        ProfileArtifactPublisher.publish(target, io.mazewall.profiler.BillOfBehavior(), ExecutorDrain.Incomplete.Interrupted)
        val previous = System.getProperty("mazewall.sbob.path")
        System.setProperty("mazewall.sbob.path", target.toString())
        val owner = ServiceExecutorOwner(java.time.Duration.ofSeconds(1))
        try {
            assertFailsWith<IncompleteProfileEvidenceException> { MazewallConfig(owner).adminService() }
        } finally {
            if (previous == null) System.clearProperty("mazewall.sbob.path") else System.setProperty("mazewall.sbob.path", previous)
            owner.stopAdmissionAndDrain()
        }
    }

    @Test
    fun `enforcement rejects missing profile before publishing any executor`() {
        val previous = System.getProperty("mazewall.sbob.path")
        System.setProperty("mazewall.sbob.path", root.resolve("missing.json").toString())
        val owner = ServiceExecutorOwner(java.time.Duration.ofSeconds(1))
        try {
            val failure = assertFailsWith<IllegalStateException> { MazewallConfig(owner).adminService() }
            assertTrue(failure.message.orEmpty().contains("profile", ignoreCase = true))
        } finally {
            if (previous == null) System.clearProperty("mazewall.sbob.path") else System.setProperty("mazewall.sbob.path", previous)
            owner.stopAdmissionAndDrain()
        }
    }
}
