package io.mazewall.demo.koog

import io.mazewall.enforcer.api.IsolatedProcessTester
import io.mazewall.enforcer.api.Platform
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.util.Comparator

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
        try {
            runPortalExample(root.resolve("worker-cache"))
            println("NEXT: apply the same explicit contract to framework-managed operations and transactions.")
        } finally {
            Files.walk(root).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
        }
    }
}
