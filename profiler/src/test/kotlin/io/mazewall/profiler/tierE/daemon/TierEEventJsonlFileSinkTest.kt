package io.mazewall.profiler.tierE.daemon

import io.mazewall.enforcer.api.EnabledIfLinuxAndSupported
import io.mazewall.profiler.internal.attribution.AttributionStatus
import io.mazewall.profiler.internal.attribution.InvocationId
import io.mazewall.profiler.internal.attribution.ManagedFrame
import io.mazewall.profiler.internal.attribution.ManagedFrameKind
import io.mazewall.profiler.internal.attribution.ResolutionStatus
import io.mazewall.profiler.internal.attribution.ResolvedSyscall
import io.mazewall.profiler.internal.attribution.SessionIntegrity
import io.mazewall.profiler.internal.attribution.StackCaptureQuality
import io.mazewall.profiler.internal.attribution.StackDefinition
import io.mazewall.profiler.internal.attribution.StackTraceId
import io.mazewall.profiler.internal.attribution.SyscallAttribution
import io.mazewall.profiler.internal.attribution.TaskIdentity
import io.mazewall.profiler.internal.attribution.TierEProfile
import io.mazewall.profiler.internal.attribution.TierETerminalReason
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

@EnabledIfLinuxAndSupported
class TierEEventJsonlFileSinkTest {
    private val target = TierERegisteredTarget("orders", 123, 0x4d5a, 99, Path.of("/agent.so"), 10, "orders.mzse")

    @Test
    fun `default and selected access are applied to complete event bytes`() {
        for (selected in listOf(false, true)) {
            val root = Files.createTempDirectory("tier-e-event-access-")
            try {
                val gid = Files.getAttribute(root, "unix:gid") as Int
                val selectedGid = selectedEvidenceTestGid(root)
                val access = TierEEvidenceFileAccess(if (selected) selectedGid else null)
                val file = TierEEventJsonlFileSink(root, access = access, prepareTemporary = { temporary ->
                    assertTrue(Files.readAllLines(temporary).last().contains("\"type\":\"terminal\""))
                    access.apply(temporary)
                }).publish(target, TierEProfile(emptyList(), SessionIntegrity()), TierETerminalReason.COMPLETE)
                assertEquals(
                    if (selected) "rw-r-----" else "rw-------",
                    java.nio.file.attribute.PosixFilePermissions
                    .toString(Files.getPosixFilePermissions(file)),
                )
                assertEquals(if (selected) selectedGid else gid, Files.getAttribute(file, "unix:gid"))
            } finally {
                root.toFile().deleteRecursively()
            }
        }
    }

    @Test
    fun `racing file or symlink destination is never overwritten`() {
        for (symlink in listOf(false, true)) {
            val root = Files.createTempDirectory("tier-e-event-race-")
            try {
                val final = root.resolve("orders-19802.events.jsonl")
                val sentinel = Files.writeString(root.resolve("sentinel"), "untouched")
                val access = TierEEvidenceFileAccess()
                val sink = TierEEventJsonlFileSink(root, prepareTemporary = { temporary ->
                    access.apply(temporary)
                    val racer = Thread {
                        if (symlink) Files.createSymbolicLink(final, sentinel) else Files.writeString(final, "racer")
                    }
                    racer.start()
                    racer.join()
                })
                assertThrows(java.nio.file.FileAlreadyExistsException::class.java) {
                    sink.publish(target, TierEProfile(emptyList(), SessionIntegrity()), TierETerminalReason.COMPLETE)
                }
                assertEquals(if (symlink) "untouched" else "racer", Files.readString(final))
                assertEquals("untouched", Files.readString(sentinel))
                assertEquals(symlink, Files.isSymbolicLink(final))
            } finally {
                root.toFile().deleteRecursively()
            }
        }
    }

    @Test
    fun `attribute and publication faults leave no final and owner only temporary`() {
        for (fault in listOf("group", "mode", "publication")) {
            val root = Files.createTempDirectory("tier-e-event-fault-")
            try {
                val access = TierEEvidenceFileAccess(Files.getAttribute(root, "unix:gid") as Int)
                val failure = java.io.IOException(fault)
                val sink = TierEEventJsonlFileSink(root, prepareTemporary = { file ->
                    when (fault) {
                        "group" -> access.apply(file, setGroup = { _, _ -> throw failure })
                        "mode" -> access.apply(file, setMode = { _, _ -> throw failure })
                        else -> access.apply(file)
                    }
                }, publishTemporary = { _, _ -> throw failure })
                assertEquals(
                    failure,
                    assertThrows(java.io.IOException::class.java) {
                    sink.publish(target, TierEProfile(emptyList(), SessionIntegrity()), TierETerminalReason.COMPLETE)
                },
                )
                assertFalse(Files.exists(root.resolve("orders-19802.events.jsonl")))
                Files.list(root).use { files ->
                    files.forEach {
                        assertEquals(
                            "rw-------",
                            java.nio.file.attribute.PosixFilePermissions
                        .toString(Files.getPosixFilePermissions(it)),
                        )
                    }
                }
            } finally {
                root.toFile().deleteRecursively()
            }
        }
    }

    @Test
    fun `completed artifact retains repeated syscall rows and unknown attribution independently of transport`() {
        val output = Files.createTempDirectory("tier-e-event-jsonl-")
        try {
            val events = listOf(observed(1uL), observed(2uL), observed(3uL, unknown = true))
            val profile = TierEProfile(events, SessionIntegrity(attributionFailures = 1, allTargetAdmittedCount = 3, allTargetSubmittedCount = 3))

            val file = TierEEventJsonlFileSink(output).publish(target, profile, TierETerminalReason.COMPLETE)
            val rows = Files.readAllLines(file).map { Json.parseToJsonElement(it).jsonObject }

            assertEquals(5, rows.size)
            assertEquals(
                "header",
                rows
                .first()
                .getValue("type")
                .jsonPrimitive.content,
            )
            assertEquals(listOf("1", "2", "3"), rows.slice(1..3).map { it.getValue("ordinal").jsonPrimitive.content })
            assertEquals("NO_ACTIVE_INVOCATION", rows[3].getValue("resolution_status").jsonPrimitive.content)
            assertEquals(
                "3",
                rows
                .last()
                .getValue("event_count")
                .jsonPrimitive.content,
            )
            assertEquals(
                "1",
                rows
                .last()
                .getValue("unattributed_count")
                .jsonPrimitive.content,
            )
            assertEquals(
                "true",
                rows
                .last()
                .getValue("export_complete")
                .jsonPrimitive.content,
            )
            assertEquals(
                "false",
                rows
                .last()
                .getValue("attribution_complete")
                .jsonPrimitive.content,
            )
            assertEquals(
                "true",
                rows
                .last()
                .getValue("capture_complete")
                .jsonPrimitive.content,
            )
            assertEquals(
                "false",
                rows
                .last()
                .getValue("policy_eligibility")
                .jsonPrimitive.content,
            )
            assertThrows(IllegalArgumentException::class.java) {
                TierEEventJsonlFileSink(output).publish(target, profile, TierETerminalReason.COMPLETE)
            }
        } finally {
            output.toFile().deleteRecursively()
        }
    }

    @Test
    fun `structurally complete artifact discloses missing kernel submissions`() {
        val root = Files.createTempDirectory("tier-e-unreconciled-capture-")
        try {
            val profile = TierEProfile(listOf(observed(1uL)), SessionIntegrity(allTargetAdmittedCount = 2, allTargetSubmittedCount = 1))
            val file = TierEEventJsonlFileSink(root).publish(target, profile, TierETerminalReason.COMPLETE)
            val terminal = Json.parseToJsonElement(Files.readAllLines(file).last()).jsonObject
            assertEquals("true", terminal.getValue("export_complete").jsonPrimitive.content)
            assertEquals("false", terminal.getValue("capture_complete").jsonPrimitive.content)
            assertEquals("2", terminal.getValue("capture_admitted_count").jsonPrimitive.content)
            assertEquals("1", terminal.getValue("capture_submitted_count").jsonPrimitive.content)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `output byte and record exhaustion never publishes a complete file`() {
        val output = Files.createTempDirectory("tier-e-event-limit-")
        try {
            val profile = TierEProfile(listOf(observed(1uL), observed(2uL)), SessionIntegrity())
            assertThrows(IllegalStateException::class.java) {
                TierEEventJsonlFileSink(output, maxBytes = 256, maxRecords = 2).publish(target, profile, TierETerminalReason.COMPLETE)
            }
            assertFalse(Files.exists(output.resolve("orders-19802.events.jsonl")))
            assertThrows(IllegalStateException::class.java) {
                TierEEventJsonlFileSink(output, maxBytes = 1_000_000, maxRecords = 1).publish(target, profile, TierETerminalReason.COMPLETE)
            }
            assertFalse(Files.exists(output.resolve("orders-19802.events.jsonl")))
            assertTrue(Files.list(output).use { it.anyMatch { file -> file.fileName.toString().endsWith(".tmp") } })
        } finally {
            output.toFile().deleteRecursively()
        }
    }

    private fun observed(
        timestamp: ULong,
        unknown: Boolean = false,
    ): ResolvedSyscall {
        val observation = SyscallAttribution(
            TaskIdentity(99, 12, 0),
            timestamp.toLong(),
            257,
            List(6) { 0L },
            if (unknown) null else InvocationId(1),
            if (unknown) AttributionStatus.NO_ACTIVE_INVOCATION else AttributionStatus.PENDING_DICTIONARY,
            kernelTimestampNanos = timestamp,
            hostTgid = 123u,
        )
        val stack = if (unknown) {
            null
        } else {
            StackDefinition(
            StackTraceId(1),
            listOf(ManagedFrame("loader", "demo.Read", "readConfiguration", "()V", 1, ManagedFrameKind.JAVA)),
            StackCaptureQuality.COMPLETE,
        )
        }
        return ResolvedSyscall(observation, stack, if (unknown) ResolutionStatus.NO_ACTIVE_INVOCATION else ResolutionStatus.RESOLVED)
    }
}
