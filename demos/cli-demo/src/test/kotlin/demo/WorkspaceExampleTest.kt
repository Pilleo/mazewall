package demo

import io.mazewall.enforcer.api.ContainmentViolationException
import io.mazewall.enforcer.api.IsolatedProcessTester
import io.mazewall.enforcer.api.Platform
import io.mazewall.enforcer.api.Policy
import io.mazewall.enforcer.api.RuntimeProfile
import io.mazewall.enforcer.api.WorkerPolicyBuilder
import io.mazewall.enforcer.api.execution.DomainLimits
import org.junit.jupiter.api.Test
import java.nio.file.AccessDeniedException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.util.concurrent.Callable
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class WorkspaceExampleTest {
    @Test
    fun `workspace reads require the declared grant and preserve actual IO failures`() {
        check(Platform.isSupported()) { "Workspace example requires actual Linux containment" }
        IsolatedProcessTester.runIsolatedMethod(javaClass.name, "workspaceBody")
    }

    @Suppress("unused")
    fun workspaceBody() {
        DemoFixture.create("workspace-example").use { fixture ->
            val workspace = Files.createDirectory(fixture.root.resolve("workspace"))
            val allowed = Files.writeString(workspace.resolve("input.txt"), "approved input")
            val sibling = Files.writeString(fixture.root.resolve("sibling.txt"), "private sibling")
            assertEquals("private sibling", Files.readString(sibling))
            lateinit var worker: Thread
            OwnedDemoRuntime.run(Policy.NO_EXEC_HOTSPOT) { runtime ->
                val policy = WorkerPolicyBuilder.denyList(RuntimeProfile.HOTSPOT_JIT)
                    .allowJvmClasspath().allowFsRead(workspace).build()
                val domain = runtime.prepare(policy, DomainLimits(1, 1))
                worker = OwnedDemoRuntime.await(runtime.submit(domain, Callable { Thread.currentThread() }))
                assertFalse(worker.isVirtual)
                assertEquals("approved input", readWorkspaceFile(runtime, domain, allowed))
                val denied = assertFailsWith<ContainmentViolationException> { readWorkspaceFile(runtime, domain, sibling) }
                assertIs<AccessDeniedException>(denied.cause)
                assertFailsWith<NoSuchFileException> { readWorkspaceFile(runtime, domain, workspace.resolve("missing.txt")) }
                assertEquals("approved input", readWorkspaceFile(runtime, domain, allowed))
            }
            worker.join(5000)
            assertFalse(worker.isAlive, "The actual worker must physically retire")
            assertEquals("private sibling", Files.readString(sibling))
        }
        assertTrue(OwnedDemoRuntime.drainRetained().isEmpty())
    }
}
