package io.mazewall.demo.koog

import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.serialization.JSONObject
import ai.koog.serialization.kotlinx.KotlinxSerializer
import io.mazewall.enforcer.api.ContainmentViolationException
import io.mazewall.enforcer.api.IsolatedProcessTester
import io.mazewall.enforcer.api.Mazewall
import io.mazewall.enforcer.api.Platform
import io.mazewall.enforcer.api.Policy
import io.mazewall.enforcer.api.RuntimeProfile
import io.mazewall.enforcer.api.WorkerPolicyBuilder
import io.mazewall.enforcer.api.execution.DomainLimits
import io.mazewall.enforcer.api.execution.ExecutionDiagnosticState
import io.mazewall.enforcer.api.execution.ManagedRuntime
import io.mazewall.koog.ThreadToolAdapter
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.nio.file.AccessDeniedException
import java.nio.file.Files
import java.time.Duration
import java.util.Comparator
import java.util.concurrent.Callable
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

@Tag("presentation")
@DisplayName("05 — Bind an upstream Koog tool to an owned platform worker")
class ThreadReadPresentationTest {
    @Test
    fun `actual registry validates before admission and retains its worker through retirement`() {
        check(Platform.isSupported()) { "Thread reader example requires actual Linux containment" }
        IsolatedProcessTester.runIsolatedMethod(javaClass.name, "readerBody")
    }

    @Suppress("unused")
    fun readerBody() = runBlocking {
        val root = Files.createTempDirectory("mazewall-koog-thread-test-")
        try {
            val allowed = Files.createDirectory(root.resolve("allowed"))
            val note = Files.writeString(allowed.resolve("note.txt"), "public é🙂")
            val sibling = Files.writeString(root.resolve("sibling.txt"), "private sibling")
            val budget = Duration.ofSeconds(10)
            lateinit var worker: Thread
            lateinit var tool: ThreadToolAdapter<ThreadReadArguments, String>
            ManagedRuntime.open(RuntimeProfile.HOTSPOT_JIT, Policy.NO_EXEC_HOTSPOT, budget, budget).use { runtime ->
                val policy = WorkerPolicyBuilder.denyList(RuntimeProfile.HOTSPOT_JIT).allowJvmClasspath().allowFsRead(allowed).build()
                val domain = runtime.prepare(policy, DomainLimits(1, 8))
                worker = Mazewall.callContained(runtime, domain, Callable { Thread.currentThread() })
                tool = threadReadTool(runtime, domain)
                val registry = ToolRegistry { tool(tool) }
                assertEquals(listOf("read_note"), registry.tools.map { it.name })
                assertTrue(registry.getTool("read_note") === tool)
                val serializer = KotlinxSerializer(Json { ignoreUnknownKeys = false })
                assertFailsWith<SerializationException> {
                    tool.decodeArgs(serializer.decodeJSONElementFromString("{\"path\":\"note.txt\",\"policy\":\"unrestricted\"}") as JSONObject, serializer)
                }
                val beforeInvalid = tool.diagnostics().invocations.admitted
                for (invalid in listOf("", " ", "bad\u0000path", "é".repeat(2049))) {
                    assertFailsWith<IllegalArgumentException> { tool.execute(ThreadReadArguments(invalid)) }
                }
                assertEquals(beforeInvalid, tool.diagnostics().invocations.admitted)
                assertEquals("public é🙂", tool.execute(ThreadReadArguments(note.toString())))
                assertEquals("private sibling", Files.readString(sibling))
                val denied = assertFailsWith<ContainmentViolationException> { tool.execute(ThreadReadArguments(sibling.toString())) }
                assertIs<AccessDeniedException>(denied.cause)
                assertEquals("public é🙂", tool.execute(ThreadReadArguments(note.toString())))
                assertFalse(worker.isVirtual)
                assertFalse(tool.diagnostics().retirementComplete)
            }
            worker.join(5000)
            assertFalse(worker.isAlive)
            assertEquals(ExecutionDiagnosticState.CLOSED, tool.diagnostics().state)
            assertTrue(tool.diagnostics().retirementComplete)
            assertEquals("private sibling", Files.readString(sibling))
            println("RESULT: real read_note registry; bounded validation before admission; approved read and actual sibling denial")
            println("CLEANUP: actual platform worker retired; caller can still read the sibling")
        } finally {
            Files.walk(root).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
        }
    }
}
