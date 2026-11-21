package io.mazewall.portal

import io.mazewall.enforcer.api.*
import io.mazewall.platform.fd.*
import io.mazewall.platform.lifecycle.*
import io.mazewall.platform.native.*
import io.mazewall.platform.process.*
import io.mazewall.platform.socket.*
import io.mazewall.platform.syscall.*
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

private enum class CodecSampleKind { FIRST, SECOND }

class PortalCodecTest {
    @Test
    fun `string and int round trip`() {
        val payload =
            PortalCodec.concat(
                listOf(
                    PortalCodec.encodeInt(42),
                    PortalCodec.encodeString("portal"),
                    PortalCodec.encodeBytes(byteArrayOf(1, 2)),
                ),
            )
        val reader = PortalCodec.Reader(payload)
        assertEquals(42, reader.int())
        assertEquals("portal", reader.string())
        assertArrayEquals(byteArrayOf(1, 2), reader.bytes())
    }

    @Test
    fun `remote failures retain type and bounded message without serializing a throwable`() {
        val encoded =
            PortalRemoteFailureCodec.encode(
                PortalRemoteFailure("fixture.InvalidDocument", "unsupported page header"),
            )

        assertEquals(
            PortalRemoteFailure("fixture.InvalidDocument", "unsupported page header"),
            PortalRemoteFailureCodec.decode(encoded),
        )
    }

    @Test
    fun `remote failure payload rejects trailing bytes`() {
        val encoded = PortalRemoteFailureCodec.encode(PortalRemoteFailure("fixture.Invalid", "no")) + byteArrayOf(1)

        assertThrows(IllegalArgumentException::class.java) { PortalRemoteFailureCodec.decode(encoded) }
    }

    @Test
    fun `reader accepts only canonical boolean bytes`() {
        assertFalse(PortalCodec.Reader(PortalCodec.encodeBoolean(false)).boolean())
        assertTrue(PortalCodec.Reader(PortalCodec.encodeBoolean(true)).boolean())

        listOf<Byte>(2, -1, 127).forEach { invalidByte ->
            val failure =
                assertThrows(PortalMalformedPayloadException::class.java) {
                    PortalCodec.Reader(byteArrayOf(invalidByte), "Service.flag.return").boolean()
                }
            assertEquals("Service.flag.return", failure.context)
            assertEquals(PortalInvocationEvidence.UNKNOWN_COMPLETION, failure.evidence)
        }
    }

    @Test
    fun `reader rejects truncated primitives and bytes`() {
        listOf(
            "byte" to { r: PortalCodec.Reader -> r.byte() },
            "short" to { r: PortalCodec.Reader -> r.short() },
            "int" to { r: PortalCodec.Reader -> r.int() },
            "long" to { r: PortalCodec.Reader -> r.long() },
            "bytes" to { r: PortalCodec.Reader -> r.bytes() },
            "string" to { r: PortalCodec.Reader -> r.string() },
        ).forEach { (label, read) ->
            assertThrows(PortalMalformedPayloadException::class.java, { read(PortalCodec.Reader(ByteArray(0), label)) }, label)
        }
        val shortOneByte = PortalCodec.Reader(byteArrayOf(1))
        assertThrows(PortalMalformedPayloadException::class.java) { shortOneByte.short() }
        val intTwoBytes = PortalCodec.Reader(byteArrayOf(0, 1))
        assertThrows(PortalMalformedPayloadException::class.java) { intTwoBytes.int() }
        val badBytesHeader = PortalCodec.Reader(PortalCodec.encodeInt(4) + byteArrayOf(1, 2))
        assertThrows(PortalMalformedPayloadException::class.java) { badBytesHeader.bytes() }
    }

    @Test
    fun `reader rejects malformed UTF-8 and over-limit collection counts`() {
        val malformedUtf8 = PortalCodec.encodeInt(2) + byteArrayOf(0xC3.toByte(), 0x28)
        assertThrows(PortalMalformedPayloadException::class.java) {
            PortalCodec.Reader(malformedUtf8, "Service.echo.text").string()
        }

        val overLimit = PortalCodec.encodeInt(16_385)
        assertThrows(PortalMalformedPayloadException::class.java) {
            PortalCodec.Reader(overLimit).collectionCount()
        }
        val negativeCount = PortalCodec.encodeInt(-1)
        assertThrows(PortalMalformedPayloadException::class.java) {
            PortalCodec.Reader(negativeCount).collectionCount()
        }
    }

    @Test
    fun `reader rejects duplicate map keys and unconsumed trailing bytes`() {
        val duplicateMapBytes =
            PortalCodec.concat(
                listOf(
                    PortalCodec.encodeInt(2),
                    PortalCodec.encodeString("dup"),
                    PortalCodec.encodeInt(1),
                    PortalCodec.encodeString("dup"),
                    PortalCodec.encodeInt(2),
                ),
            )
        val duplicateFailure =
            assertThrows(PortalMalformedPayloadException::class.java) {
                val r = PortalCodec.Reader(duplicateMapBytes, "Service.mapCall.entries")
                r.map({ r.string() }, { r.int() })
            }
        assertEquals("Service.mapCall.entries", duplicateFailure.context)
        assertThrows(PortalMalformedPayloadException::class.java) {
            val r = PortalCodec.Reader(duplicateMapBytes)
            r.mutableMap({ r.string() }, { r.int() })
        }

        val trailingReader = PortalCodec.Reader(PortalCodec.encodeInt(7) + byteArrayOf(9), "Service.count.return")
        assertEquals(7, trailingReader.int())
        val trailing = assertThrows(PortalMalformedPayloadException::class.java) { trailingReader.requireExhausted() }
        assertEquals("Service.count.return", trailing.context)

        val exactReader = PortalCodec.Reader(PortalCodec.encodeInt(7), "Service.count.return")
        assertEquals(7, exactReader.int())
        exactReader.requireExhausted()
    }

    @Test
    fun `reader rejects invalid typed scalars without echoing raw values and checks capabilities`() {
        val secretInput = "secret-token-value"
        val badUuid = PortalCodec.Reader(PortalCodec.encodeString(secretInput), "Service.id")
        val uuidFailure = assertThrows(PortalMalformedPayloadException::class.java) { badUuid.uuid() }
        assertFalse(uuidFailure.message!!.contains(secretInput), uuidFailure.message)

        val badInstant = PortalCodec.Reader(PortalCodec.encodeString(secretInput), "Service.at")
        val instantFailure = assertThrows(PortalMalformedPayloadException::class.java) { badInstant.instant() }
        assertFalse(instantFailure.message!!.contains(secretInput), instantFailure.message)

        val badDecimal = PortalCodec.Reader(PortalCodec.encodeString(secretInput), "Service.amount")
        val decimalFailure = assertThrows(PortalMalformedPayloadException::class.java) { badDecimal.bigDecimal() }
        assertFalse(decimalFailure.message!!.contains(secretInput), decimalFailure.message)

        val badEnum = PortalCodec.Reader(PortalCodec.encodeString(secretInput), "Service.kind")
        val enumFailure = assertThrows(PortalMalformedPayloadException::class.java) { badEnum.enumValue(CodecSampleKind::class.java) }
        assertFalse(enumFailure.message!!.contains(secretInput), enumFailure.message)

        assertThrows(PortalMalformedPayloadException::class.java) {
            PortalCodec.requireCapabilityCount(actual = 0, expected = 1, context = "Service.read")
        }
        PortalCodec.requireCapabilityCount(actual = 1, expected = 1, context = "Service.read")
    }

    @Test
    fun `inContext chains segments and preserves non-wire application IllegalArgumentException`() {
        val reader = PortalCodec.Reader(PortalCodec.encodeString("ok"), "Service.dto")
        val appError = IllegalArgumentException("domain validation failed")
        val thrown =
            assertThrows(IllegalArgumentException::class.java) {
                reader.inContext("value") {
                    reader.string()
                    throw appError
                }
            }
        assertSame(appError, thrown)

        val wireFailure =
            assertThrows(PortalMalformedPayloadException::class.java) {
                reader.inContext("nested") {
                    reader.inContext("flag") {
                        PortalCodec.Reader(byteArrayOf(5), "Service.dto.nested.flag").boolean()
                    }
                    reader.requireExhausted()
                }
            }
        assertEquals("Service.dto.nested.flag", wireFailure.context)
    }

    @Test
    fun `stateful invocation gate loses state on malformed payload but not completed business or host exception`() {
        val stateful =
            PortalServiceManifest(
                serviceId = PortalServiceId("svc"),
                contractName = "fixture.Service",
                implementationName = "fixture.Impl",
                dispatcherName = "fixture.ServicePortalDispatcher",
                schemaId = "sha256:" + "0".repeat(64),
                isolationGroup = "group",
                instanceMode = PortalInstanceMode.STATEFUL_SINGLETON,
                policyProfile = "baseline",
                workerArtifact = "worker",
            )
        val businessGate = PortalStatefulInvocationGate()
        assertThrows(PortalRemoteCallException::class.java) {
            businessGate.invoke(stateful) { throw PortalRemoteCallException("fixture.Invalid", "bad input") }
        }
        assertEquals(PortalServiceHealthState.AVAILABLE, businessGate.health(stateful).state)

        assertThrows(IllegalArgumentException::class.java) {
            businessGate.invoke(stateful) { throw IllegalArgumentException("trusted dto constructor check") }
        }
        assertEquals(PortalServiceHealthState.AVAILABLE, businessGate.health(stateful).state)

        assertThrows(PortalMalformedPayloadException::class.java) {
            businessGate.invoke(stateful) { throw PortalMalformedPayloadException("trailing payload", "fixture.Service.op.return") }
        }
        assertEquals(PortalServiceHealthState.STATE_LOST, businessGate.health(stateful).state)
    }

    @Test
    fun `request writer matches legacy PortalCodec wire bytes across scalars collections and snapshots`() {
        val writer = PortalRequestWriter()
        val utf8Samples = listOf(
            "",
            "ascii-value",
            "café-€-🚀",
            "\uD800lone-high",
            "lone-low\uDC00",
        )
        writer.boolean(true)
        writer.boolean(false)
        writer.byte(-7)
        writer.short(12_345)
        writer.int(-987_654)
        writer.long(123_456_789_012L)
        writer.float(3.5f)
        writer.double(-6.25)
        writer.char('Ż')
        writer.bytes(ByteArray(0))
        writer.bytes(byteArrayOf(1, 2, 3))
        utf8Samples.forEach(writer::string)
        writer.nullable<String>(null) { writer.string(it) }
        writer.nullable("present") { writer.string(it) }
        writer.list(listOf(10, 20, 30), writer::int)
        writer.map(linkedMapOf("a" to listOf("x", "y"), "b" to emptyList()), writer::string) { items ->
            writer.list(items, writer::string)
        }
        writer.inputSnapshot("snapshot-body".byteInputStream(), 64)

        val expectedParts = buildList {
            add(PortalCodec.encodeBoolean(true))
            add(PortalCodec.encodeBoolean(false))
            add(PortalCodec.encodeByte(-7))
            add(PortalCodec.encodeShort(12_345))
            add(PortalCodec.encodeInt(-987_654))
            add(PortalCodec.encodeLong(123_456_789_012L))
            add(PortalCodec.encodeFloat(3.5f))
            add(PortalCodec.encodeDouble(-6.25))
            add(PortalCodec.encodeChar('Ż'))
            add(PortalCodec.encodeBytes(ByteArray(0)))
            add(PortalCodec.encodeBytes(byteArrayOf(1, 2, 3)))
            utf8Samples.forEach { add(PortalCodec.encodeString(it)) }
            add(PortalCodec.encodeBoolean(false))
            add(PortalCodec.encodeBoolean(true))
            add(PortalCodec.encodeString("present"))
            add(PortalCodec.encodeInt(3))
            add(PortalCodec.encodeInt(10))
            add(PortalCodec.encodeInt(20))
            add(PortalCodec.encodeInt(30))
            add(PortalCodec.encodeInt(2))
            add(PortalCodec.encodeString("a"))
            add(PortalCodec.encodeInt(2))
            add(PortalCodec.encodeString("x"))
            add(PortalCodec.encodeString("y"))
            add(PortalCodec.encodeString("b"))
            add(PortalCodec.encodeInt(0))
            add(PortalCodec.encodeBytes("snapshot-body".toByteArray(Charsets.UTF_8)))
        }
        assertArrayEquals(PortalCodec.concat(expectedParts), writer.toByteArray())
        assertThrows(IllegalStateException::class.java) { writer.int(1) }
    }

    @Test
    fun `request writer enforces collection byte and traversal budgets before allocation and iteration`() {
        // Exact 16,384 element list succeeds; 16,385 fails before iterating or allocating.
        val exactWriter = PortalRequestWriter()
        exactWriter.list(List(PortalCodec.MAX_COLLECTION_ELEMENTS) { 1 }, exactWriter::byte)
        val reader = PortalCodec.Reader(exactWriter.toByteArray())
        assertEquals(PortalCodec.MAX_COLLECTION_ELEMENTS, reader.collectionCount())

        var iteratorCalls = 0
        var reads = 0
        val overCountList = object : java.util.AbstractList<Int>() {
            override val size: Int = PortalCodec.MAX_COLLECTION_ELEMENTS + 1

            override fun get(index: Int): Int {
                reads++
                return index
            }

            override fun iterator(): MutableIterator<Int> {
                iteratorCalls++
                return super.iterator()
            }
        }
        val countFailWriter = PortalRequestWriter()
        val countFailure = assertThrows(PortalCallException::class.java) { countFailWriter.list(overCountList, countFailWriter::int) }
        assertEquals(PortalInvocationEvidence.NOT_STARTED, countFailure.evidence)
        assertEquals(0, iteratorCalls)
        assertEquals(0, reads)
        assertEquals(0, countFailWriter.growthCount)
        assertEquals(0L, countFailWriter.peakAllocatedBytes)

        // Over-8-MiB string rejects before scanning/allocating.
        val hugeString = "a".repeat(PortalFrame.MAX_PAYLOAD - 3)
        val over8MibWriter = PortalRequestWriter()
        val stringFailure = assertThrows(PortalCallException::class.java) { over8MibWriter.string(hugeString) }
        assertEquals(PortalInvocationEvidence.NOT_STARTED, stringFailure.evidence)
        assertEquals(0, over8MibWriter.growthCount)
        assertEquals(0L, over8MibWriter.peakAllocatedBytes)

        // Multi-byte UTF-8 exact limit vs +1 byte (2-byte, 3-byte, 4-byte code points).
        val exactEuro = PortalRequestWriter(maxBytes = 7)
        exactEuro.string("€") // 4 len + 3 UTF-8 = 7
        assertEquals(7, exactEuro.toByteArray().size)
        val overEuro = PortalRequestWriter(maxBytes = 6)
        assertThrows(PortalCallException::class.java) { overEuro.string("€") }
        assertEquals(0L, overEuro.peakAllocatedBytes)
        val exactRocket = PortalRequestWriter(maxBytes = 8)
        exactRocket.string("🚀") // 4 len + 4 UTF-8 = 8
        assertEquals(8, exactRocket.toByteArray().size)
        assertEquals(8L, exactRocket.peakAllocatedBytes)
        val overRocket = PortalRequestWriter(maxBytes = 7)
        assertThrows(PortalCallException::class.java) { overRocket.string("🚀") }
        assertEquals(0L, overRocket.peakAllocatedBytes)

        // Small budget stops nested traversal mid-stream without growing past configured maxBytes.
        var nestedReads = 0
        val countingNested = List(5) { idx ->
            object : java.util.AbstractList<Int>() {
                override val size: Int = 5

                override fun get(index: Int): Int {
                    nestedReads++
                    return idx * 10 + index
                }
            }
        }
        val smallWriter = PortalRequestWriter(maxBytes = 64)
        val smallFailure = assertThrows(PortalCallException::class.java) {
            smallWriter.list(countingNested) { inner -> smallWriter.list(inner, smallWriter::int) }
        }
        assertEquals(PortalInvocationEvidence.NOT_STARTED, smallFailure.evidence)
        assertEquals(13, nestedReads, "expected early mid-stream abort before remaining 12 elements")
        assertTrue(smallWriter.bufferCapacity <= 64)
        assertTrue(smallWriter.peakAllocatedBytes <= 64L)

        // Aggregate element budget rejects deeply nested collections even within byte budget.
        val aggWriter = PortalRequestWriter(maxBytes = 4_096, maxTotalElements = 6)
        assertThrows(PortalCallException::class.java) {
            aggWriter.list(listOf(listOf(1, 2), listOf(3, 4), listOf(5))) { inner -> aggWriter.list(inner, aggWriter::int) }
        }
        assertEquals(6, aggWriter.visitedElements)

        // Numeric overflow list size (Int.MAX_VALUE) rejects cleanly before allocation/iteration.
        val overflowList = object : java.util.AbstractList<Int>() {
            override val size: Int = Int.MAX_VALUE

            override fun get(index: Int): Int = error("must not iterate")
        }
        val overflowWriter = PortalRequestWriter()
        assertThrows(PortalCallException::class.java) { overflowWriter.list(overflowList, overflowWriter::int) }
        assertEquals(0L, overflowWriter.peakAllocatedBytes)

        // User getter/element failure preserves exact exception identity.
        val boom = IllegalStateException("user getter threw")
        val getterWriter = PortalRequestWriter(maxBytes = 128)
        val caughtBoom = assertThrows(IllegalStateException::class.java) {
            getterWriter.list(listOf("ok", "boom")) { s ->
                if (s == "boom") throw boom else getterWriter.string(s)
            }
        }
        assertSame(boom, caughtBoom)

        // Mutating collection lying about size rejects cleanly.
        val shrinkingList = object : java.util.AbstractList<Int>() {
            override val size: Int = 3

            override fun get(index: Int): Int = index

            override fun iterator(): MutableIterator<Int> = mutableListOf(1).iterator()
        }
        assertThrows(PortalCallException::class.java) { PortalRequestWriter().list(shrinkingList, {}) }

        // EncodeBytes overflow guard on PortalCodec and invalid PortalRequestWriter bounds.
        assertThrows(IllegalArgumentException::class.java) { PortalRequestWriter(maxBytes = PortalFrame.MAX_PAYLOAD + 1) }
        assertThrows(IllegalArgumentException::class.java) { PortalRequestWriter(maxBytes = -1) }
        assertThrows(IllegalArgumentException::class.java) { PortalRequestWriter(maxCollectionElements = PortalCodec.MAX_COLLECTION_ELEMENTS + 1) }
        assertThrows(IllegalArgumentException::class.java) { PortalRequestWriter(maxTotalElements = -1) }

        // InputSnapshot bounds and peak allocation include bounded scratch buffer.
        val snapWriter = PortalRequestWriter(maxBytes = 64)
        snapWriter.inputSnapshot(ByteArray(12).inputStream(), 16)
        assertTrue(snapWriter.peakAllocatedBytes >= 16L)
        assertEquals(16, snapWriter.toByteArray().size)
        // Trimming a 64-byte buffer to 16 bytes accounts for both live buffer and copy.
        assertTrue(snapWriter.peakAllocatedBytes >= 80L)
        assertThrows(IllegalArgumentException::class.java) {
            PortalRequestWriter(maxBytes = 64).inputSnapshot(ByteArray(17).inputStream(), 16)
        }
        assertThrows(PortalCallException::class.java) {
            PortalRequestWriter(maxBytes = 10).inputSnapshot(ByteArray(8).inputStream(), 64)
        }
        val zeroWriter = PortalRequestWriter(maxBytes = 0)
        assertEquals(0, zeroWriter.toByteArray().size)
        assertEquals(0L, zeroWriter.peakAllocatedBytes)
    }
}
