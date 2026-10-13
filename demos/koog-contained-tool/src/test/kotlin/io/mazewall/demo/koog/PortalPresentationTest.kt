package io.mazewall.demo.koog

import io.mazewall.enforcer.api.ContainedExecutors
import io.mazewall.enforcer.api.IsolatedProcessTester
import io.mazewall.enforcer.api.Platform
import io.mazewall.enforcer.api.ProcessPolicyBuilder
import io.mazewall.enforcer.api.RuntimeProfile
import io.mazewall.koog.PortalToolAdapter
import io.mazewall.platform.syscall.Syscall
import io.mazewall.portal.PortalRuntime
import io.mazewall.portal.PortalServiceHealthState
import io.mazewall.portal.PortalWorkerLifecycleState
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.util.Comparator
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@Tag("presentation")
@DisplayName("05 — Cross an explicit process boundary with a generated Koog tool")
class PortalPresentationTest {
    @Test
    @DisplayName("05.1 — Typed bounded data enters a distinct protected child, then the child retires")
    fun generatedProcessTool() {
        check(Platform.isSupported()) { "The process-service showcase requires actual Linux containment" }
        IsolatedProcessTester.runIsolatedMethod(javaClass.name, "portalBody")
    }

    @Suppress("unused")
    fun portalBody() = runBlocking {
        val root = Files.createTempDirectory("mazewall-presentation-portal-")
        var child: ProcessHandle? = null
        try {
            assertFailsWith<ClassNotFoundException> { Class.forName("io.mazewall.demo.koog.worker.AsciiTextTransformer") }
            // A Portal host needs child launch: use the existing launch-compatible Tier1, not NO_EXEC.
            val baseline = ProcessPolicyBuilder.denyList(RuntimeProfile.HOTSPOT_JIT)
                .allowMmapExec().block(Syscall.PTRACE, Syscall.MOUNT, Syscall.UNSHARE, Syscall.CHROOT).build()
            ContainedExecutors.installOnProcess(baseline)
            val configuration = TextTools.configuration(root.resolve("worker-cache"))
            PortalRuntime.open(configuration).use { runtime ->
                val registry = TextTools.registry(runtime)
                val tool = registry.getTool<PortalToolAdapter<TextArguments, String>>()
                val input = "aBc éß🙂"
                assertEquals("ABC éß🙂", tool.execute(TextArguments(input)))
                val group = configuration.manifests.single { it.serviceId.value == "bounded-text" }.isolationGroup
                val health = runtime.health().single { it.isolationGroup == group }
                assertEquals(PortalServiceHealthState.AVAILABLE, health.state)
                assertEquals(PortalWorkerLifecycleState.STARTED, health.lastWorkerLifecycle.state)
                val pid = health.workerPids.single()
                assertTrue(pid > 0 && pid != ProcessHandle.current().pid())
                child = ProcessHandle.of(pid).orElseThrow()
                assertTrue(child!!.isAlive)
                assertFailsWith<IllegalArgumentException> { TextBounds.requireBounded("x".repeat(65_537)) }
                assertFailsWith<IllegalArgumentException> { TextBounds.requireBounded("\uD800") }
                println("RESULT: $input → ABC éß🙂 via actual Koog registry/generated service")
                println("BOUNDARY: host PID=${ProcessHandle.current().pid()}, distinct ready worker PID=$pid; guest class not loadable on host")
                println("BOUNDS: oversized and malformed UTF8 rejected; tool arguments cannot choose a policy or path")
            }
            assertFalse(checkNotNull(child).isAlive, "the acquired protected child must physically retire")
            println("CLEANUP: acquired Portal worker dead\nNEXT: apply the same explicit contract to framework-managed operations and transactions.")
        } finally {
            Files.walk(root).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
        }
    }
}
