package demo.vulnapp

import io.mazewall.enforcer.api.IsolatedProcessTester
import io.mazewall.enforcer.api.Platform
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder

@Tag("presentation")
@DisplayName("08 — Optional real web application: compare, restrict, then observe")
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class WebPresentationTest {
    @Test
    @Order(1)
    @DisplayName("08.1 — The unprotected service launches a command through its real bean")
    fun unprotectedApplication() {
        actualApplication("unprotectedBody")
        println("RESULT: real service command creates its owned marker; existing file preserved; standard unsafe fixture routes absent")
    }

    @Test
    @Order(2)
    @DisplayName("08.2 — The protected service rejects that effect while benign HTTP and upload work")
    fun protectedApplication() {
        actualApplication("protectedBody")
        println("RESULT: benign HTTP200 and owned ZIP upload; command has actual errno1/13 and no marker; evidence is INFERRED_PERMISSION_FAILURE")
        println("LIMIT: this compares a prepared service contract; its fixed policy fixture is not profile-backed discovery proof")
    }

    @Test
    @Order(3)
    @DisplayName("08.3 — Real service profiling publishes on shutdown and incomplete coverage refuses enforcement")
    fun profilePublication() {
        actualApplication("profileBody")
        println("RESULT: real HTTP/command observations publish only after application shutdown; incomplete generation stays diagnostic and rejects enforcement")
        println("CLEANUP: fresh application JVM and acquired resources physically retired")
    }

    private fun actualApplication(body: String) {
        check(Platform.isSupported()) { "The web feature example requires actual Linux containment/profiling support" }
        // Reuse the real bean/HTTP/filesystem assertions and owners rather than replaying a report or a script.
        IsolatedProcessTester.runIsolatedMethod(VulnerableWebAppFreshJvmTest::class.java.name, body)
    }
}
