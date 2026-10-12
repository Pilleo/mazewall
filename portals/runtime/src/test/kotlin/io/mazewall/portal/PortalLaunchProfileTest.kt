package io.mazewall.portal

import org.junit.jupiter.api.Test
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class PortalLaunchProfileTest {
    @Test
    fun `actual stdio must be the selected merged JDK pipes`() {
        val contract = PortalLaunchProfile().contract()
        for (merged in listOf(false, true)) {
            val child = io.mazewall.platform.process.RealProcessLauncher
                .startProcess(
                    listOf("/bin/cat"),
                    merged,
                    io.mazewall.platform.process
                    .JvmLaunchPreparation {},
                )
            try {
                if (merged) contract.checkStdio(child.pid()) else assertFailsWith<IllegalArgumentException> { contract.checkStdio(child.pid()) }
            } finally {
                child.destroyForcibly()
                assertEquals(true, child.waitFor(5, java.util.concurrent.TimeUnit.SECONDS))
            }
        }
    }

    @Test
    fun `profile snapshots declared metadata and rejects unsupported conflicting and invalid markers`() {
        val selected = mutableMapOf(PortalLaunchMarker.APPROVED to "approved")
        val absent = mutableSetOf(PortalLaunchMarker.HOST_ONLY)
        val profile = PortalLaunchProfile(selected, absent)
        val captured = profile.contract().argument()
        selected.clear()
        absent.clear()
        assertEquals(mapOf(PortalLaunchMarker.APPROVED to "approved"), profile.markers)
        assertEquals(captured, profile.contract().argument())
        assertEquals(profile.contract().identity, PortalLaunchContract.fromArgument(captured).identity)
        assertFailsWith<IllegalArgumentException> { ProcessBroker(launchProfile = PortalLaunchProfile(version = 99)) }
        assertFailsWith<IllegalArgumentException> { PortalLaunchProfile(mapOf(PortalLaunchMarker.APPROVED to "x"), setOf(PortalLaunchMarker.APPROVED)).contract() }
        assertFailsWith<IllegalArgumentException> { PortalLaunchProfile(mapOf(PortalLaunchMarker.APPROVED to "bad\nmarker")).contract() }
        assertFailsWith<IllegalArgumentException> { PortalLaunchProfile(mapOf(PortalLaunchMarker.APPROVED to "x".repeat(PortalLaunchContract.MAX_MARKER_LENGTH + 1))).contract() }
    }

    @Test
    fun `missing named launch authority refuses configuration without launching`() {
        val manifest =
            PortalServiceManifest(PortalServiceId("launch"), "fixture.Contract", "fixture.Guest", "fixture.Dispatcher", "schema", "group", PortalInstanceMode.STATELESS, "policy", "artifact")
        val config = PortalRuntimeConfig(listOf(manifest), workerProfiles = mapOf("policy" to PortalWorkerProfile()), launchProfiles = emptyMap(), requiredLaunchProfiles = setOf("policy"))
        assertFailsWith<PortalLaunchProfileUnavailableException> { config.validateProfiles() }
        assertEquals(null, PortalRuntimeConfig(listOf(manifest), workerProfiles = mapOf("policy" to PortalWorkerProfile())).launchProfile(manifest).preparation)
    }

    @Test
    fun `resource identity rejects an actually acquired open canary and accepts after closure`() {
        val path = Files.createTempFile("launch-canary-", ".txt")
        try {
            val contract = FileChannel.open(path, StandardOpenOption.READ).use {
                val actual = PortalDescriptorIdentity.measure(path)
                val expected = PortalLaunchProfile(forbiddenDescriptors = setOf(actual)).contract()
                assertFailsWith<IllegalArgumentException> { expected.checkDescriptors(ProcessHandle.current().pid()) }
                expected
            }
            contract.checkDescriptors(ProcessHandle.current().pid())
        } finally {
            Files.delete(path)
        }
    }

    @Test
    fun `parent permit binds independently measured launch identity before guest entry`() {
        val session = "d".repeat(32)
        val actual = PortalLaunchProfile(mapOf(PortalLaunchMarker.APPROVED to "approved")).contract().identity
        assertFailsWith<IllegalArgumentException> { PortalParentAuthorization.accept(PortalParentAuthorization(session, 123).frame(), session, 123, actual) }
        assertEquals(actual, PortalParentAuthorization.accept(PortalParentAuthorization(session, 123, actual).frame(), session, 123, actual).launch)
        assertFailsWith<IllegalArgumentException> { PortalParentAuthorization.accept(PortalParentAuthorization(session, 123, actual, "a".repeat(64)).frame(), session, 123, actual) }
        assertEquals("none", PortalParentResources.measure(ProcessHandle.current().pid(), false))
    }

    @Test
    fun `launch parser rejects malformed duplicate conflicting noncanonical and overflowing records`() {
        fun encode(text: String): String =
            java.util.Base64
            .getUrlEncoder()
            .withoutPadding()
            .encodeToString(text.toByteArray(Charsets.UTF_8))
        val header = "format=2\npreparation=inherited\nstdio=jdk-pipes\nfds=no-startup-transfer\n"
        val record = header + "e=APPROVED:approved\na=HOST_ONLY\nf=1:2\n"
        val paddingRecord = if (record.length % 3 == 0) record.replace("approved", "approvedx") else record
        val cases = linkedMapOf(
            "base64" to "!not-base64",
            "oversized" to "A".repeat(PortalLaunchContract.MAX_ARGUMENT_BYTES + 1),
            "exact argument bound reaches schema guard" to "A".repeat(PortalLaunchContract.MAX_ARGUMENT_BYTES),
            "old schema" to encode(record.replace("format=2", "format=1")),
            "unsupported preparation" to encode(record.replace("preparation=inherited", "preparation=other")),
            "unknown field" to encode(record + "unknown=1\n"),
            "undeclared synthetic name" to encode(record + "a=MAZEWALL_UNDECLARED_MARKER\n"),
            "duplicate present" to encode(record + "e=APPROVED:approved\n"),
            "duplicate absent" to encode(record + "a=HOST_ONLY\n"),
            "conflict" to encode(record + "a=APPROVED\n"),
            "duplicate resource" to encode(record + "f=1:2\n"),
            "invalid device" to encode(record.replace("f=1:2", "f=bad:2")),
            "overflow inode" to encode(record.replace("f=1:2", "f=1:9223372036854775808")),
            "missing resource field" to encode(record.replace("f=1:2", "f=1")),
            "extra resource field" to encode(record.replace("f=1:2", "f=1:2:3")),
            "noncanonical number" to encode(record.replace("f=1:2", "f=01:2")),
            "noncanonical order" to encode(header + "a=HOST_ONLY\ne=APPROVED:approved\nf=1:2\n"),
            "padding" to java.util.Base64
                .getUrlEncoder()
                .encodeToString(paddingRecord.toByteArray()),
            "CRLF" to encode(record.replace("\n", "\r\n")),
            "missing LF" to encode(record.removeSuffix("\n")),
            "extra LF" to encode(record + "\n"),
            "non ASCII metadata" to encode(record.replace("approved", "approuvé")),
            "marker value bound" to encode(header + "e=APPROVED:${"x".repeat(PortalLaunchContract.MAX_MARKER_LENGTH + 1)}\n"),
            "resource count overflow" to encode(header + (0..PortalLaunchContract.MAX_RESOURCES).joinToString("") { "f=1:$it\n" }),
        )
        cases.forEach { (label, argument) ->
            val failure = assertFailsWith<IllegalArgumentException>(label) { PortalLaunchContract.fromArgument(argument) }
            if (label == "oversized") assertEquals("oversized required launch contract", failure.message)
            if (label == "exact argument bound reaches schema guard") assertEquals("unsupported required launch contract", failure.message)
        }
    }

    @Test
    fun `maximum supported marker and resource record round trips without weakening bounds`() {
        val markers = PortalLaunchMarker.entries.associateWith { "x".repeat(PortalLaunchContract.MAX_MARKER_LENGTH) }
        val resources = (0 until PortalLaunchContract.MAX_RESOURCES).map { PortalDescriptorIdentity(Long.MIN_VALUE + it, Long.MAX_VALUE - it) }.toSet()
        val contract = PortalLaunchProfile(markers, forbiddenDescriptors = resources).contract()
        assertEquals(contract.identity, PortalLaunchContract.fromArgument(contract.argument()).identity)
        assertEquals(contract.argument(), PortalLaunchContract.fromArgument(contract.argument()).argument())
    }

    @Test
    fun `old readiness without independently checked launch evidence rejects`() {
        val generation = PortalWorkerGeneration("a".repeat(64), "b".repeat(64), "c".repeat(64))
        val old = "format=1\nsession=${"d".repeat(
            32,
        )}\nchildPid=123\ninputs=${generation.inputs}\nmanifests=${generation.manifests}\nruntime=${generation.runtime}\npolicy=${PortalWorkerReady.BASELINE}\nprocessWide=1\nseccomp=1\nfilesystem=1\n"
        assertFailsWith<IllegalArgumentException> {
            PortalWorkerReady.accept(PortalFrame(PortalKind.Response, 0, PortalWorkerReady.METHOD, old.toByteArray(), 0), PortalWorkerReady("d".repeat(32), 123, generation))
        }
    }
}
