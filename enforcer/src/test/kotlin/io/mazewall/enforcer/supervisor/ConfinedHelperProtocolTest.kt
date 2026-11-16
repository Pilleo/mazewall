package io.mazewall.enforcer.internal.supervisor

import io.mazewall.enforcer.internal.execution.HelperBinding
import io.mazewall.enforcer.internal.supervisor.ConfinedHelperTestData.ancestry
import io.mazewall.enforcer.internal.supervisor.ConfinedHelperTestData.capabilities
import io.mazewall.enforcer.internal.supervisor.ConfinedHelperTestData.deadline
import io.mazewall.enforcer.internal.supervisor.ConfinedHelperTestData.manifest
import io.mazewall.enforcer.internal.supervisor.ConfinedHelperTestData.owner
import io.mazewall.enforcer.internal.supervisor.ConfinedHelperTestData.zero
import io.mazewall.ffi.networking.ConfinedDeadline
import io.mazewall.platform.lifecycle.Pid
import org.junit.jupiter.api.Test
import java.nio.ByteBuffer
import java.util.HexFormat
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertNotEquals

internal object ConfinedHelperTestData {
    val owner = HelperBinding(Pid(7), 11, 13, "11".repeat(32), "22".repeat(32))
    val deadline = ConfinedDeadline(19)
    val zero = "0".repeat(64)
    val ancestry = "uid=1,1,1,1\ngid=2,2,2,2\ngroups=2,3\n" +
        "capInh=${"0".repeat(16)}\ncapPrm=${"0".repeat(16)}\ncapEff=${"0".repeat(16)}\ncapBnd=${"0".repeat(16)}\ncapAmb=${"0".repeat(16)}\n" +
        "pidNs=(dev=4,ino=5)\nmountNs=(dev=4,ino=6)\nuserNs=(dev=4,ino=7)\ntimeNs=(dev=4,ino=8)\ntimeChildNs=(dev=4,ino=8)\n" +
        "monotonicOffsetSec=0\nmonotonicOffsetNsec=0\nuidMap=$zero\ngidMap=$zero\nrootKey=(dev=1,ino=2)\nmountViewDigest=$zero\n" +
        "noNewPrivs=0\nseccomp=0\nseccompFilters=0\nlineage=bootstrap-v1\n"
    val capabilities = listOf(
        HelperCapability(0, HelperPurpose.Application, HelperDescriptorIdentity("(dev=1,ino=2)", 3, HelperObjectKind.Directory), zero),
        HelperCapability(1, HelperPurpose.RuntimeSupport, HelperDescriptorIdentity("(dev=1,ino=4)", 3, HelperObjectKind.Regular), "33".repeat(32)),
    )

    fun manifest(): ConfinedManifest = ConfinedManifest.create(owner, HelperAncestry.parse(ancestry), capabilities)
}

class ConfinedHelperProtocolTest {
    @Test
    fun `manifest preimage has one canonical encoding and excludes frame deadline`() {
        val manifest = manifest()
        val preimage = ConfinedHelperProtocol.manifestPreimage(owner, manifest.ancestry, capabilities)
        assertEquals(823, preimage.size)
        assertEquals("4a09678a0e75a9f89baa447c133495f8e3e18c3020faad7ca93b3c3b43407688", manifest.binding.manifestDigest)
        val expectedPrefix = HexFormat.of().parseHex("4d5742345231000001" + "00000007000000000000000b000000000000000d" + "11".repeat(32) + "22".repeat(32))
        assertContentEquals(expectedPrefix, preimage.copyOfRange(0, 93))
        val tail = ByteBuffer.wrap(preimage, 93, preimage.size - 93)
        assertEquals(ancestry.toByteArray().size, tail.short.toInt())
        val actualAncestry = ByteArray(ancestry.toByteArray().size)
        tail.get(actualAncestry)
        assertContentEquals(ancestry.toByteArray(), actualAncestry)
        assertEquals(2, tail.short.toInt())
        val expectedEntries = HexFormat.of().parseHex(
            "0000010100000003000d286465763d312c696e6f3d3229" +
                zero +
                "0001020200000003000d286465763d312c696e6f3d3429" +
                "33".repeat(32),
        )
        val entries = ByteArray(tail.remaining()).also { tail.get(it) }
        assertContentEquals(expectedEntries, entries)
        val setup = ConfinedHelperProtocol.encode(ConfinedHelperProtocol.Frame.Setup(manifest, deadline))
        val anotherDeadline = ConfinedHelperProtocol.encode(ConfinedHelperProtocol.Frame.Setup(manifest, ConfinedDeadline(23)))
        assertContentEquals(setup.copyOfRange(15, setup.size), anotherDeadline.copyOfRange(15, anotherDeadline.size))
        assertNotEquals(manifest.binding.manifestDigest, ConfinedManifest.create(owner.copy(domainGeneration = 14), manifest.ancestry, capabilities).binding.manifestDigest)
    }

    @Test
    fun `every finite wire row has exact tag payload and echo identity`() {
        val m = manifest()
        val b = m.binding
        val frames = listOf(
            Triple(0x40, 120 + ancestry.toByteArray().size + 2 * (42 + 13), ConfinedHelperProtocol.Frame.Setup(m, deadline)),
            Triple(0x41, 132, ConfinedHelperProtocol.Frame.Read(ConfinedReadRequest(b, 17, HelperPurpose.Application, 0, "a/b"), deadline)),
            Triple(0x53, 128, ConfinedHelperProtocol.Frame.Denied(b, 17, 13, deadline)),
            Triple(0x51, 122, ConfinedHelperProtocol.Frame.Rejected(b, ConfinedRejectReason.Permission, 1, deadline)),
            Triple(0x5b, 129, ConfinedHelperProtocol.Frame.Retired(b, StopReason.Cancel, Pid(29), 31, deadline)),
        ) +
            ConfinedHelperProtocol.ControlTag.entries.map { Triple(it.wire, 116, ConfinedHelperProtocol.Frame.Control(it, b, deadline)) } +
            ConfinedHelperProtocol.RootTag.entries.map { Triple(it.wire, 118, ConfinedHelperProtocol.Frame.Root(it, b, if (it == ConfinedHelperProtocol.RootTag.Complete) 2 else 0, deadline)) } +
            ConfinedHelperProtocol.ResultTag.entries.map { Triple(it.wire, 124, ConfinedHelperProtocol.Frame.Result(it, b, 17, deadline)) } +
            ConfinedHelperProtocol.StopTag.entries.map { Triple(it.wire, 117, ConfinedHelperProtocol.Frame.Stop(it, b, StopReason.Normal, deadline)) }
        frames.forEach { (tag, size, frame) ->
            val bytes = ConfinedHelperProtocol.encode(frame)
            assertEquals(15 + size, bytes.size)
            assertEquals(tag, bytes[0].toInt() and 255)
            assertContentEquals(byteArrayOf(0, 1), bytes.copyOfRange(1, 3))
            assertEquals(size, ByteBuffer.wrap(bytes).getInt(3))
            assertEquals(19L, ByteBuffer.wrap(bytes).getLong(7))
            val decoded = ConfinedHelperProtocol.decode(bytes)
            assertEquals(b, decoded.binding)
            assertEquals(deadline, decoded.deadline)
            assertContentEquals(bytes, ConfinedHelperProtocol.encode(decoded))
            assertFails { ConfinedHelperProtocol.decode(bytes + 0.toByte()) }
            assertFails { ConfinedHelperProtocol.decode(bytes.copyOf(bytes.size - 1)) }
        }
    }

    @Test
    fun `malformed version tag binding digest UTF8 counts and retired reasons refuse`() {
        val valid = ConfinedHelperProtocol.encode(ConfinedHelperProtocol.Frame.Setup(manifest(), deadline))
        for (offset in listOf(0, 1, 2, 3, 15, 35, 67, 99, 131, 133)) {
            val bad = valid.copyOf()
            bad[offset] = (bad[offset].toInt() xor 0x7f).toByte()
            assertFails("mutated offset $offset") { ConfinedHelperProtocol.decode(bad) }
        }
        val b = manifest().binding
        val retired = ConfinedHelperProtocol.encode(ConfinedHelperProtocol.Frame.Retired(b, StopReason.Normal, Pid(29), 31, deadline))
        for (reason in listOf(4, 255)) {
            assertFails { ConfinedHelperProtocol.decode(retired.copyOf().also { it[131] = reason.toByte() }) }
        }
        for (suffix in listOf("", "/file", ".", "..", "a//b", "a/./b", "a/../b", "bad\u0000", "x".repeat(4096))) {
            assertFails { ConfinedHelperProtocol.encode(ConfinedHelperProtocol.Frame.Read(ConfinedReadRequest(b, 1, HelperPurpose.Application, 0, suffix), deadline)) }
        }
        assertFails { ConfinedHelperProtocol.encode(ConfinedHelperProtocol.Frame.Read(ConfinedReadRequest(b, 1, HelperPurpose.RuntimeSupport, 1, "other"), deadline)) }
    }

    @Test
    fun `ancestry rejects old missing reordered duplicate and noncanonical fields`() {
        for (text in listOf(
            ancestry.replace("timeNs=(dev=4,ino=8)\n", ""),
            ancestry.replace("monotonicOffsetSec=0", "monotonicOffsetSec=1"),
            ancestry.replace("groups=2,3", "groups=3,2"),
            ancestry.replace("uid=1,1,1,1", "uid=01,1,1,1"),
            ancestry.replace("pidNs=(dev=4,ino=5)", "pidNs=pid:[5]"),
            ancestry.replace("seccomp=0\n", "seccomp=0\nseccomp=0\n"),
            ancestry.replace("uid=1,1,1,1\ngid=2,2,2,2", "gid=2,2,2,2\nuid=1,1,1,1"),
            ancestry.dropLast(1),
                ancestry + "extra=0\n",
        )) {
            assertFails(text) { HelperAncestry.parse(text) }
        }
    }

    @Test
    fun `unsigned credentials remain lossless and mutable capability input cannot rewrite manifest`() {
        val unsigned = ancestry
            .replace("uid=1,1,1,1", "uid=4294967295,4294967295,4294967295,4294967295")
            .replace("groups=2,3", "groups=2,4294967295")
        assertEquals(unsigned, HelperAncestry.parse(unsigned).canonical)
        for (text in listOf(unsigned.replace("4294967295", "4294967296"), ancestry.replace("groups=2,3", "groups=2,2"))) {
            assertFails { HelperAncestry.parse(text) }
        }
        val mutable = capabilities.toMutableList()
        val manifest = ConfinedManifest.create(owner, HelperAncestry.parse(ancestry), mutable)
        mutable.clear()
        assertEquals(capabilities, manifest.capabilities)
        assertFails { (manifest.capabilities as MutableList).clear() }
        val duplicate = capabilities.toMutableList().also { it[1] = it[1].copy(identity = it[1].identity.copy(key = it[0].identity.key)) }
        assertFails { ConfinedManifest.create(owner, manifest.ancestry, duplicate) }
        assertFails { ConfinedManifest.create(owner, manifest.ancestry, capabilities.reversed()) }
    }

    @Test
    fun `invalid UTF8 and oversized allocation lengths cannot escape frame bounds`() {
        val b = manifest().binding
        val valid = ConfinedHelperProtocol.encode(ConfinedHelperProtocol.Frame.Read(ConfinedReadRequest(b, 1, HelperPurpose.Application, 0, "ok"), deadline))
        assertFails { ConfinedHelperProtocol.decode(valid.copyOf().also { it[it.lastIndex] = 0xff.toByte() }) }
        for (length in listOf(-1, 0, 65537, Int.MAX_VALUE)) {
            assertFails { ConfinedHelperProtocol.decode(valid.copyOf().also { ByteBuffer.wrap(it).putInt(3, length) }) }
        }
        for (id in listOf(0L, Long.MIN_VALUE)) {
            assertFails { ConfinedHelperProtocol.decode(valid.copyOf().also { ByteBuffer.wrap(it).putLong(131, id) }) }
        }
        assertFails { ConfinedReadRequest(b, 1, HelperPurpose.Application, 0, "\ud800") }
        for (errno in listOf(0, -1, 4096)) {
            assertFails { ConfinedHelperProtocol.encode(ConfinedHelperProtocol.Frame.Denied(b, 1, errno, deadline)) }
        }
    }
}
