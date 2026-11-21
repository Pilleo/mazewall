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
}
