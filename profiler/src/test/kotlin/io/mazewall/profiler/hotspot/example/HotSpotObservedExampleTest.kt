package io.mazewall.profiler.hotspot.example

import io.mazewall.enforcer.api.EnabledIfLinuxAndSupported
import io.mazewall.platform.lifecycle.Pid
import io.mazewall.profiler.api.HotSpotObservationTarget
import io.mazewall.profiler.api.HotSpotOptions
import io.mazewall.profiler.api.HotSpotProfilingException
import io.mazewall.profiler.api.StructuredProfileEvidence
import io.mazewall.profiler.hotspot.HotSpotAssetsTest
import io.mazewall.profiler.hotspot.HotSpotChildFixture
import io.mazewall.profiler.internal.hotspot.ProcHotSpotTargetIdentity
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.api.parallel.Isolated
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.io.PrintStream
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Fake Java collector tests only: no native attach or support certification. */
@EnabledIfLinuxAndSupported
@Isolated
class HotSpotObservedExampleTest {
    @TempDir lateinit var directory: Path

    @Test
    fun `rejects zero target pid before acquisition or output creation`() {
        val assets = Files.createDirectory(directory.resolve("assets"))
        val prefix = Files.writeString(directory.resolve("prefix.json"), "[]")
        val output = directory.resolve("output")

        val bytes = ByteArrayOutputStream()
        PrintStream(bytes, false, UTF_8).use { console ->
            withConsole(console) {
                assertFailsWith<IllegalArgumentException> {
                    HotSpotObservedExample.main(
                        arrayOf(assets.toString(), output.toString(), prefix.toString(), "0", "456", "789", "42", "1"),
                    )
                }
            }
        }

        assertEquals("", bytes.toString(UTF_8))
        assertFalse(Files.exists(output))
        assertEquals("[]", Files.readString(prefix))
        assertEquals(emptyList(), Files.list(assets).use { it.toList() })
    }

    @Test
    fun `rejects wrong argument count`() {
        assertFailsWith<IllegalArgumentException> { HotSpotObservedExample.main(emptyArray()) }
        assertFailsWith<IllegalArgumentException> { HotSpotObservedExample.main(Array(9) { "unused" }) }
    }

    @Test
    fun `validates every identity and window before output or helper launch`() {
        val args = validationArgs()
        val unsignedInvalid = listOf("", "0", "-1", "+1", " 1", "1 ", "1.0", "18446744073709551616")
        for (index in 3..7) {
            val invalid = when (index) {
                3 -> unsignedInvalid + "2147483648"
                7 -> unsignedInvalid + listOf("30001", "9223372036854775808")
                else -> unsignedInvalid
            }
            for (value in invalid) {
                assertFailsWith<IllegalArgumentException>("index=$index value=$value") {
                    HotSpotObservedExample.main(args.copyOf().also { it[index] = value })
                }
                assertFalse(Files.exists(Path.of(args[1])))
            }
        }
    }

    @Test
    fun `prefix must contain only literal nonempty strings with at most 64 entries`() {
        val args = validationArgs()
        val invalid = listOf("{", "{}", "null", "[1]", "[true]", "[null]", "[[]]", "[{}]", "[\"\"]") +
            JsonArray(List(65) { JsonPrimitive("unused") }).toString()
        for (text in invalid) {
            Files.writeString(Path.of(args[2]), text)
            assertFailsWith<IllegalArgumentException>(text) { HotSpotObservedExample.main(args) }
            assertFalse(Files.exists(Path.of(args[1])))
        }
    }

    @Test
    fun `invalid paths and preexisting output never overwrite inputs`() {
        val args = validationArgs()
        for (index in 0..2) {
            for (value in listOf("", " ", "relative")) {
                assertFailsWith<IllegalArgumentException> {
                    HotSpotObservedExample.main(args.copyOf().also { it[index] = value })
                }
            }
        }
        for (index in listOf(0, 2)) {
            assertFailsWith<IllegalArgumentException> {
                HotSpotObservedExample.main(args.copyOf().also { it[index] = directory.resolve("missing").toString() })
            }
        }
        assertFailsWith<IllegalArgumentException> {
            HotSpotObservedExample.main(args.copyOf().also { it[0] = args[2] })
        }
        assertFailsWith<IllegalArgumentException> {
            HotSpotObservedExample.main(args.copyOf().also { it[2] = args[0] })
        }
        val output = Files.createDirectory(Path.of(args[1]))
        val sentinel = Files.writeString(output.resolve("sentinel"), "user-owned")
        assertFailsWith<IllegalArgumentException> { HotSpotObservedExample.main(args) }
        assertEquals("user-owned", Files.readString(sentinel))
        assertEquals(listOf(sentinel), Files.list(output).use { it.toList() })
    }

    @Test
    fun `ordinary observation retains typed evidence and never becomes policy eligible`() {
        val target = target().copy(cgroupId = ULong.MAX_VALUE, mountNamespace = ULong.MAX_VALUE)
        val args = fixtureArgs("success", target = target)
        HotSpotObservedExample.main(args)
        val output = Path.of(args[1])
        val evidence = StructuredProfileEvidence.fromJson(Files.readString(output.resolve("evidence.json")))
        assertEquals("otel_hotspot25_observed_v1", evidence.runtimeProfile)
        assertEquals(
            listOf("leaf", "caller"),
            evidence.records
                .single()
                .evidence.frames
                .map { it.functionName },
        )
        assertTrue(evidence.finalized)
        assertFalse(evidence.collectionLossComplete || evidence.policyEligibility)
        assertTrue(evidence.terminalAccountingComplete && evidence.summary.physicalDrainComplete && evidence.retentionComplete)
        assertEquals(1uL, evidence.summary.received)
        assertEquals(1uL, evidence.summary.resolved)
        assertEquals(0uL, evidence.summary.unavailable)
        assertEquals(0uL, evidence.summary.skipped)
        assertEquals(0uL, evidence.pendingOccurrences)
        assertEquals(0uL, evidence.discardedOccurrences)
        assertTrue(evidence.retainedBytes > 0)
        val identity = json(output, "target-identity.json")
        assertEquals(target.pid.value.toString(), identity.getValue("host_pid").jsonPrimitive.content)
        assertEquals(target.processStartTicks.toString(), identity.getValue("birth_ticks").jsonPrimitive.content)
        assertEquals(ULong.MAX_VALUE.toString(), identity.getValue("cgroupv2_id").jsonPrimitive.content)
        assertEquals(ULong.MAX_VALUE.toString(), identity.getValue("mount_namespace_inode").jsonPrimitive.content)
        val raw = Files.readString(output.resolve("evidence.json"))
        assertTrue(raw.contains(ULong.MAX_VALUE.toString()))
        val outcome = json(output)
        assertEquals("successful", outcome.getValue("outcome").jsonPrimitive.content)
        for (key in listOf("coverage_complete", "policy_eligibility", "collection_loss_complete", "final_pending_ownership")) {
            assertEquals(JsonPrimitive(false), outcome[key], key)
        }
        assertEquals(JsonPrimitive(true), outcome["evidence_written"])
        assertEquals(JsonPrimitive(true), outcome["close_attempted"])
        assertEquals(JsonPrimitive(true), outcome["close_succeeded"])
        assertEquals(JsonPrimitive(false), outcome["retry_cleanup_attempted"])
        assertEquals(
            evidence.summary.observedEventLoss
                ?.toString()
                ?.let(::JsonPrimitive) ?: JsonNull,
            outcome["observed_event_loss"],
        )
        assertEquals(JsonArray(emptyList()), outcome["failures"])
        assertTrue(ProcessHandle.current().isAlive)
    }

    @Test
    fun `admitted observation flushes exactly one readiness line before collection completes`() {
        val args = fixtureArgs("success")
        val output = Path.of(args[1])
        val flushed = mutableListOf<String>()
        val completedAtFlush = mutableListOf<Boolean>()
        var consoleClosed = false
        val bytes = object : ByteArrayOutputStream() {
            override fun flush() {
                flushed += toString(UTF_8)
                completedAtFlush += Files.exists(output.resolve("evidence.json")) || Files.exists(output.resolve("outcome.json"))
            }

            override fun close() {
                consoleClosed = true
                super.close()
            }
        }
        PrintStream(bytes, false, UTF_8).use { console ->
            withConsole(console) { HotSpotObservedExample.main(args) }

            val marker = "MAZEWALL_OBSERVATION_READY\n"
            assertEquals(marker, bytes.toString(UTF_8))
            assertTrue(flushed.isNotEmpty(), "Readiness must be explicitly flushed with auto-flush disabled")
            assertTrue(flushed.all { it == marker }, "Every publication flush must contain the complete, single readiness line")
            assertTrue(completedAtFlush.none { it }, "Readiness must be flushed before collection completion output")
            assertFalse(consoleClosed, "The example must not close borrowed stdout")
            assertEquals("successful", json(output).getValue("outcome").jsonPrimitive.content)
            assertEquals(JsonPrimitive(false), json(output)["final_pending_ownership"])
        }
    }

    @Test
    fun `strict assets reject observed admission with no owned session or evidence`() {
        val args = fixtureArgs("success", observed = false)
        val bytes = ByteArrayOutputStream()
        val failure = PrintStream(bytes, false, UTF_8).use { console ->
            withConsole(console) { assertFailsWith<HotSpotProfilingException> { HotSpotObservedExample.main(args) } }
        }
        assertEquals("", bytes.toString(UTF_8))
        assertTrue(failure.cause is IllegalArgumentException)
        val output = Path.of(args[1])
        val outcome = json(output)
        assertEquals("failed", outcome.getValue("outcome").jsonPrimitive.content)
        assertEquals(JsonPrimitive(false), outcome["evidence_available"])
        assertEquals(JsonPrimitive(failure.pendingOwnership), outcome["final_pending_ownership"])
        assertFalse(Files.exists(output.resolve("evidence.json")))
        assertTrue(Files.isRegularFile(output.resolve("diagnostics.txt")))
    }

    @Test
    fun `denied acquisition retains compound native errors and actual owner state`() {
        val args = fixtureArgs("denied")
        val bytes = ByteArrayOutputStream()
        val failure = PrintStream(bytes, false, UTF_8).use { console ->
            withConsole(console) { assertFailsWith<HotSpotProfilingException> { HotSpotObservedExample.main(args) } }
        }
        assertEquals("", bytes.toString(UTF_8))
        assertEquals(listOf(5u, 13u), failure.nativeErrnos)
        val output = Path.of(args[1])
        val outcome = json(output)
        assertEquals(JsonArray(listOf(JsonPrimitive(5), JsonPrimitive(13))), outcome["native_errnos"])
        assertEquals(JsonPrimitive(failure.pendingOwnership), outcome["final_pending_ownership"])
        assertEquals(JsonPrimitive(failure.partialResult?.structuredEvidence != null), outcome["evidence_available"])
        assertTrue(outcome.getValue("failures").toString().contains("synthetic original native failure"))
        assertTrue(Files.readString(output.resolve("diagnostics.txt")).contains(failure.diagnostics))
        assertTrue(ProcessHandle.current().isAlive)
    }

    @Test
    fun `malformed stream preserves partial evidence primary failure and close failure`() {
        val args = fixtureArgs("malformed")
        val failure = assertFailsWith<HotSpotProfilingException> { HotSpotObservedExample.main(args) }
        val output = Path.of(args[1])
        val evidence = StructuredProfileEvidence.fromJson(Files.readString(output.resolve("evidence.json")))
        assertFalse(evidence.finalized || evidence.policyEligibility || evidence.collectionLossComplete)
        assertTrue(failure.suppressed.isNotEmpty())
        val outcome = json(output)
        assertEquals("failed", outcome.getValue("outcome").jsonPrimitive.content)
        assertEquals(JsonPrimitive(false), outcome["final_pending_ownership"])
        assertEquals(listOf("primary", "close"), stages(outcome))
    }

    @Test
    fun `hung helper retains failed retirement and close diagnostics without inventing clear ownership`() {
        val args = fixtureArgs("hang")
        val failure = assertFailsWith<HotSpotProfilingException> { HotSpotObservedExample.main(args) }
        val outcome = json(Path.of(args[1]))
        assertEquals("failed", outcome.getValue("outcome").jsonPrimitive.content)
        assertTrue(stages(outcome).containsAll(listOf("primary", "close")))
        assertTrue(failure.suppressed.isNotEmpty())
        assertEquals(JsonPrimitive(failure.pendingOwnership), outcome["final_pending_ownership"])
        assertEquals(JsonPrimitive(false), outcome["policy_eligibility"])
        assertTrue(ProcessHandle.current().isAlive)
    }

    @Test
    fun `evidence output collision still closes acquired session and preserves primary error`() {
        val args = fixtureArgs("success")
        val output = Files.createDirectory(Path.of(args[1]))
        val collision = Files.writeString(output.resolve("evidence.json"), "user-owned")
        assertFailsWith<java.nio.file.FileAlreadyExistsException> {
            HotSpotObservedExample.collect(output, target(), options(args), 1)
        }
        assertEquals("user-owned", Files.readString(collision))
        val outcome = json(output)
        assertEquals(JsonPrimitive(false), outcome["final_pending_ownership"])
        assertEquals(JsonPrimitive(true), outcome["evidence_available"])
        assertEquals(JsonPrimitive(false), outcome["evidence_written"])
        assertEquals(listOf("primary"), stages(outcome))
    }

    @Test
    fun `partial evidence output failure is suppressed without skipping failed-session cleanup`() {
        val args = fixtureArgs("malformed")
        val output = Files.createDirectory(Path.of(args[1]))
        Files.createDirectory(output.resolve("evidence.json"))
        val failure = assertFailsWith<HotSpotProfilingException> {
            HotSpotObservedExample.collect(output, target(), options(args), 1)
        }
        assertTrue(failure.suppressed.any { it is java.nio.file.FileAlreadyExistsException })
        val outcome = json(output)
        assertEquals(listOf("primary", "partial_evidence_output", "close"), stages(outcome))
        assertEquals(JsonPrimitive(false), outcome["final_pending_ownership"])
    }

    @Test
    @Timeout(15)
    fun `broken readiness publication retains primary failure and closes owned session without collecting`() {
        val args = fixtureArgs("success").also { it[7] = "30000" }
        val output = Path.of(args[1])
        val broken = object : OutputStream() {
            override fun write(value: Int) {
                Thread.currentThread().interrupt()
                throw IOException("synthetic broken stdout")
            }
        }
        try {
            val failure = PrintStream(broken, false, UTF_8).use { console ->
                withConsole(console) { assertFailsWith<IOException> { HotSpotObservedExample.main(args) } }
            }
            assertTrue(Thread.currentThread().isInterrupted)
            Thread.interrupted()
            val outcome = json(output)
            assertEquals("failed", outcome.getValue("outcome").jsonPrimitive.content)
            assertEquals(listOf("primary"), stages(outcome))
            val retained = outcome
                .getValue("failures")
                .jsonArray
                .single()
                .jsonObject
            assertEquals(failure.javaClass.name, retained.getValue("type").jsonPrimitive.content)
            assertEquals(failure.message, retained.getValue("message").jsonPrimitive.content)
            assertTrue(Files.readString(output.resolve("diagnostics.txt")).contains(checkNotNull(failure.message)))
            assertEquals(JsonPrimitive(true), outcome["original_pending_ownership"])
            assertEquals(JsonPrimitive(true), outcome["close_attempted"])
            assertEquals(JsonPrimitive(true), outcome["close_succeeded"])
            assertEquals(JsonPrimitive(false), outcome["final_pending_ownership"])
            assertEquals(JsonPrimitive(false), outcome["evidence_available"])
            assertFalse(Files.exists(output.resolve("evidence.json")))
        } finally {
            Thread.interrupted()
        }
    }

    @Test
    @Timeout(15)
    fun `interruption during readiness flush skips collection and survives owned cleanup`() {
        val args = fixtureArgs("success").also { it[7] = "30000" }
        val output = Path.of(args[1])
        val bytes = object : ByteArrayOutputStream() {
            override fun flush() {
                Thread.currentThread().interrupt()
            }
        }
        try {
            PrintStream(bytes, false, UTF_8).use { console ->
                withConsole(console) {
                    assertFailsWith<InterruptedException> { HotSpotObservedExample.main(args) }
                    assertTrue(Thread.currentThread().isInterrupted)
                }
            }
            Thread.interrupted()
            assertEquals("MAZEWALL_OBSERVATION_READY\n", bytes.toString(UTF_8))
            val outcome = json(output)
            assertEquals("failed", outcome.getValue("outcome").jsonPrimitive.content)
            assertEquals(listOf("primary"), stages(outcome))
            assertEquals(JsonPrimitive(true), outcome["close_succeeded"])
            assertEquals(JsonPrimitive(false), outcome["final_pending_ownership"])
            assertEquals(JsonPrimitive(false), outcome["evidence_available"])
            assertFalse(Files.exists(output.resolve("evidence.json")))
        } finally {
            Thread.interrupted()
        }
    }

    @Test
    fun `interrupted admission attempt restores interrupt status after cleanup`() {
        val args = fixtureArgs("success")
        val output = Files.createDirectory(Path.of(args[1]))
        val target = target()
        val options = options(args)
        val bytes = ByteArrayOutputStream()
        try {
            PrintStream(bytes, false, UTF_8).use { console ->
                withConsole(console) {
                    Thread.currentThread().interrupt()
                    assertFailsWith<Exception> { HotSpotObservedExample.collect(output, target, options, 1) }
                }
            }
            assertTrue(Thread.currentThread().isInterrupted)
            Thread.interrupted()
            assertEquals("", bytes.toString(UTF_8))
            assertEquals("failed", json(output).getValue("outcome").jsonPrimitive.content)
        } finally {
            Thread.interrupted()
        }
    }

    private fun <T> withConsole(
        console: PrintStream,
        action: () -> T,
    ): T {
        val original = System.out
        try {
            System.setOut(console)
            return action()
        } finally {
            System.setOut(original)
        }
    }

    private fun validationArgs(): Array<String> {
        val assets = Files.createDirectory(directory.resolve("assets"))
        val prefix = Files.writeString(directory.resolve("prefix.json"), "[]")
        return arrayOf(assets.toString(), directory.resolve("output").toString(), prefix.toString(), "123", "456", "789", "42", "1")
    }

    private fun target(): HotSpotObservationTarget {
        val pid = Pid(Math.toIntExact(ProcessHandle.current().pid()))
        return HotSpotObservationTarget(pid, ProcHotSpotTargetIdentity.startTicks(pid), 789uL, 42uL)
    }

    private fun fixtureArgs(
        mode: String,
        observed: Boolean = true,
        target: HotSpotObservationTarget = target(),
    ): Array<String> {
        HotSpotAssetsTest().also { it.directory = directory }.fixture(observed = observed)
        val childDirectory = Files.createDirectory(directory.resolve("literal argv with spaces"))
        val prefix = HotSpotChildFixture.argv(mode, childDirectory, observedTarget = target)
        val prefixFile = Files.writeString(directory.resolve("prefix.json"), JsonArray(prefix.map(::JsonPrimitive)).toString())
        return arrayOf(
            directory.toString(),
            directory.resolve("output").toString(),
            prefixFile.toString(),
            target.pid.value.toString(),
            target.processStartTicks.toString(),
            target.cgroupId.toString(),
            target.mountNamespace.toString(),
            "1",
        )
    }

    private fun options(args: Array<String>): HotSpotOptions =
        HotSpotOptions(
            Path.of(args[0]),
            Json.parseToJsonElement(Files.readString(Path.of(args[2]))).jsonArray.map { it.jsonPrimitive.content },
        )

    private fun json(
        output: Path,
        name: String = "outcome.json",
    ): JsonObject = Json.parseToJsonElement(Files.readString(output.resolve(name))).jsonObject

    private fun stages(outcome: JsonObject): List<String> =
        outcome
            .getValue("failures")
            .jsonArray
            .map {
                it.jsonObject
                    .getValue("stage")
                    .jsonPrimitive.content
            }
}
