package io.mazewall.profiler.triage
import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses
import io.mazewall.platform.native.*
import io.mazewall.platform.syscall.*
import io.mazewall.profiler.api.*
import io.mazewall.profiler.api.ObservationConstructorContract
import io.mazewall.profiler.internal.engine.SyscallEventConstructorContract
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class DiagnosticTriageRunnerTest {
    @TempDir
    lateinit var temporaryDirectory: File

    @Test
    fun `diagnostics cannot enumerate or attach to ambient processes`() {
        val classes = ClassFileImporter().importPackages("io.mazewall.profiler.triage")
        val forbidden = classes.flatMap { it.methodCallsFromSelf }.filter {
            (it.target.owner.name == "java.lang.ProcessHandle" && it.target.name == "allProcesses") ||
                it.target.owner.name
                    .startsWith("com.sun.tools.attach.")
        }
        assertTrue(forbidden.isEmpty(), "Self diagnostics must not enumerate or attach: $forbidden")
        noClasses()
            .that()
            .haveSimpleName("SelfThreadSnapshot")
            .should()
            .dependOnClassesThat()
            .haveNameMatching(
                "java\\.lang\\.ProcessBuilder|.*ManagedProcessCapture|com\\.sun\\.tools\\.attach\\..*",
            ).check(classes)
    }

    @Test
    fun `test triage runner creates report file`() {
        val probes = mapOf(
            "dmesg_seccomp_logs" to "explicit audit result",
            "jvm_thread_dump" to SelfThreadSnapshot().capture(),
            "system_syscall_definitions" to "explicit syscall result",
            "hs_err_logs" to "explicit crash result",
            "kernel_security_config" to "explicit kernel result",
        )
        val reportFile = File(temporaryDirectory, "triage_report.json")
        DiagnosticTriageRunner.writeReport(reportFile, 17L, probes)
        assertTrue(reportFile.exists())

        val content = reportFile.readText()
        assertTrue(content.contains("timestamp"))
        assertTrue(content.contains("diagnostics"))
        assertTrue(content.contains("dmesg_seccomp_logs"))
        assertTrue(content.contains("jvm_thread_dump"))
        assertTrue(content.contains("system_syscall_definitions"))
        assertTrue(content.contains("hs_err_logs"))
        assertTrue(content.contains("kernel_security_config"))
        val parsed = Json.parseToJsonElement(content).jsonObject
        assertEquals(setOf("timestamp", "diagnostics"), parsed.keys)
        assertFalse(parsed.getValue("timestamp").jsonPrimitive.isString)
        assertEquals(17L, parsed.getValue("timestamp").jsonPrimitive.long)
        val diagnostics = parsed.getValue("diagnostics").jsonObject
        assertEquals(probes.keys, diagnostics.keys)
        for ((key, value) in probes) {
            assertTrue(diagnostics.getValue(key).jsonPrimitive.isString)
            assertEquals(value, diagnostics.getValue(key).jsonPrimitive.content)
        }
    }

    @Test
    fun `self snapshot contains fresh owned platform thread and latch frame`() {
        val ready = CountDownLatch(1)
        val release = CountDownLatch(1)
        val name = "owned-self-snapshot-${System.nanoTime()}"
        val thread = Thread.ofPlatform().name(name).unstarted { holdForSelfSnapshot(ready, release) }
        thread.start()
        try {
            assertTrue(ready.await(5, TimeUnit.SECONDS), "Owned thread did not become ready")
            val dump = SelfThreadSnapshot().capture()
            assertTrue(dump.contains("collector PID ${ProcessHandle.current().pid()}"))
            assertTrue(dump.contains("current JVM only; platform threads; best-effort"))
            assertTrue(dump.contains(name))
            assertTrue(dump.contains("holdForSelfSnapshot"))
        } finally {
            release.countDown()
            thread.join(5000)
            assertFalse(thread.isAlive, "Owned thread was not physically joined")
        }
    }

    @Test
    fun `rendering orders threads and visibly caps threads and frames`() {
        val first = Thread.ofPlatform().name("first-owned").unstarted {}
        val second = Thread.ofPlatform().name("second-owned").unstarted {}
        val frames = arrayOf(
            StackTraceElement("OwnedFixture", "firstFrame", "OwnedFixture.kt", 1),
            StackTraceElement("OwnedFixture", "omittedFrame", "OwnedFixture.kt", 2),
        )
        val acquisition = { linkedMapOf(second to frames, first to frames) as Map<Thread, Array<StackTraceElement>> }
        val dump = SelfThreadSnapshot(acquisition, ThreadSnapshotLimits(1, 1, 4096)).capture()
        assertTrue(dump.contains("first-owned"))
        assertFalse(dump.contains("second-owned"))
        assertTrue(dump.contains("firstFrame"))
        assertFalse(dump.contains("omittedFrame"))
        assertTrue(dump.contains("[truncated: frame limit]"))
        assertTrue(dump.contains("[truncated: thread limit]"))
        val ordered = SelfThreadSnapshot(acquisition).capture()
        assertTrue(ordered.indexOf("first-owned") < ordered.indexOf("second-owned"))
    }

    @Test
    fun `character cap includes visible marker even for oversized thread name`() {
        val thread = Thread.ofPlatform().name("long-owned-" + "x".repeat(1000)).unstarted {}
        val dump = SelfThreadSnapshot({ mapOf(thread to emptyArray()) }, ThreadSnapshotLimits(1, 1, 256)).capture()
        assertEquals(256, dump.length)
        assertTrue(dump.endsWith("[truncated: character limit]\n"))
    }

    @Test
    fun `empty snapshot remains explicitly self scoped`() {
        val dump = SelfThreadSnapshot({ emptyMap() }).capture()
        assertTrue(dump.contains("collector PID ${ProcessHandle.current().pid()}"))
        assertTrue(dump.contains("No platform-thread stacks available"))
    }

    @Test
    fun `optional acquisition failure is visible but fatal JVM error propagates`() {
        val failed = SelfThreadSnapshot({ throw SecurityException("snapshot denied") }).capture()
        assertTrue(failed.contains("collector PID ${ProcessHandle.current().pid()}"))
        assertTrue(failed.contains("SecurityException: snapshot denied"))
        val fatal = OutOfMemoryError("synthetic fatal acquisition failure")
        assertSame(fatal, assertThrows(OutOfMemoryError::class.java) { SelfThreadSnapshot({ throw fatal }).capture() })
    }

    @Test
    fun `rendering controls reject invalid bounds`() {
        assertThrows(IllegalArgumentException::class.java) { ThreadSnapshotLimits(0, 1, 256) }
        assertThrows(IllegalArgumentException::class.java) { ThreadSnapshotLimits(1, 0, 256) }
        assertThrows(IllegalArgumentException::class.java) { ThreadSnapshotLimits(1, 1, 255) }
    }

    private fun holdForSelfSnapshot(
        ready: CountDownLatch,
        release: CountDownLatch,
    ) {
        ready.countDown()
        check(release.await(5, TimeUnit.SECONDS)) { "Owned fixture release was not received" }
    }
}
