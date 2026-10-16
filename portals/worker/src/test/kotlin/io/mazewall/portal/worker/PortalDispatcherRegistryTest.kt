package io.mazewall.portal.worker

import io.mazewall.platform.fd.*
import io.mazewall.platform.fd.FdOwnership
import io.mazewall.platform.fd.FdState
import io.mazewall.platform.fd.FileDescriptor
import io.mazewall.platform.fd.FileDescriptorRole
import io.mazewall.portal.Capability
import io.mazewall.portal.PortalMethod
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/**
 * Startup bootstrap contract for generated dispatchers: malformed strict entries fail,
 * tolerant startup skips malformed entries, and valid entries publish into a table.
 */
class PortalDispatcherRegistryTest {
    /** Mimics the shape KotlinPoet emits for `<Service>PortalDispatcher`. */
    private object EchoServicePortalDispatcher {
        val METHOD_IDS: IntArray = intArrayOf(1000)

        fun handle(
            impl: EchoService,
            method: PortalMethod.Generated,
            payload: ByteArray,
            granted: List<*>,
        ): ByteArray =
            when (method.wire) {
            1000 -> ((impl as EchoServiceImpl).tag + ":" + payload.decodeToString()).toByteArray()
            else -> error("unknown portal method ${method.wire}")
        }
    }

    private object ReplacementEchoServicePortalDispatcher {
        val METHOD_IDS: IntArray = intArrayOf(1000)

        fun handle(
            impl: EchoService,
            method: PortalMethod.Generated,
            payload: ByteArray,
            granted: List<*>,
        ): ByteArray = "replacement".toByteArray()
    }

    interface EchoService

    private class EchoServiceImpl(
        tag: String,
    ) : EchoService {
        val tag: String = tag
        constructor() : this("impl")
    }

    private fun grantedFds(): List<Capability.ReadFd> = emptyList()

    @Test
    fun `strict bootstrap rejects malformed entries before a valid dispatcher is published`() {
        val builder = PortalDispatcherTable.Builder()
        assertFailsWith<IllegalArgumentException> { builder.bootstrapStrict("malformed") }

        val count = builder.bootstrapStrict(
            "${EchoService::class.java.name}=${EchoServiceImpl::class.java.name};" +
                EchoServicePortalDispatcher::class.java.name,
        )
        assertEquals(1, count)
        val table = builder.build()

        val out = table.dispatchOrNull(
            PortalMethod.Generated(1000),
            "hello".toByteArray(),
            grantedFds(),
        )
        assertEquals("impl:hello", out?.decodeToString())
        assertNull(table.dispatchOrNull(PortalMethod.Generated(1001), ByteArray(0), grantedFds()))
    }

    @Test
    fun `unknown generated ids are not claimed and builtin ids cannot be generated`() {
        assertFailsWith<IllegalArgumentException> { PortalMethod.Generated(1) }
    }

    @Test
    fun `malformed bootstrap entries are skipped without poisoning later ones`() {
        val builder = PortalDispatcherTable.Builder()
        val count = builder.bootstrapFromProperty(
            "garbage-without-equals,${EchoService::class.java.name}=no.such.Impl," +
                "${EchoService::class.java.name}=${EchoServiceImpl::class.java.name};" +
                EchoServicePortalDispatcher::class.java.name,
        )
        assertEquals(1, count)
        assertEquals(
            "impl:hello",
            builder
                .build()
                .dispatchOrNull(PortalMethod.Generated(1000), "hello".toByteArray(), grantedFds())
                ?.decodeToString(),
        )
    }

    @Test
    fun `compatibility bootstrap rejects duplicate method ids without replacing the first handler`() {
        val builder = PortalDispatcherTable.Builder()
        val service = EchoService::class.java.name
        val implementation = EchoServiceImpl::class.java.name
        val count = builder.bootstrapFromProperty(
            "$service=$implementation;${EchoServicePortalDispatcher::class.java.name}," +
                "$service=$implementation;${ReplacementEchoServicePortalDispatcher::class.java.name}",
        )

        assertEquals(1, count)
        assertEquals(
            "impl:hello",
            builder
                .build()
                .dispatchOrNull(PortalMethod.Generated(1000), "hello".toByteArray(), grantedFds())
                ?.decodeToString(),
        )
    }

    @Test
    fun `compatibility bootstrap resolves the legacy nested dispatcher name`() {
        val builder = PortalDispatcherTable.Builder()

        assertEquals(
            1,
            builder.bootstrapStrict("${LegacyOuter.Service::class.java.name}=${LegacyOuterServiceImpl::class.java.name}"),
        )
        assertEquals(
            "legacy:hello",
            builder
                .build()
                .dispatchOrNull(PortalMethod.Generated(1200), "hello".toByteArray(), grantedFds())
                ?.decodeToString(),
        )
    }

    @Test
    fun `implicit nested dispatcher rejects ambiguous canonical and legacy artifacts`() {
        val failure = assertFailsWith<IllegalArgumentException> {
            PortalDispatcherTable.Builder().bootstrapStrict(
                "${AmbiguousOuter.Service::class.java.name}=${AmbiguousOuterServiceImpl::class.java.name}",
            )
        }

        assertEquals(true, failure.message!!.contains("multiple portal dispatchers"), failure.message)
        assertEquals(true, failure.message!!.contains("specify one explicitly"), failure.message)
    }
}

public class LegacyOuter {
    public interface Service
}

public class LegacyOuterServiceImpl : LegacyOuter.Service

/** Binary name emitted for LegacyOuter.Service before canonical nested names were introduced. */
public object LegacyOuter_ServicePortalDispatcher {
    public val METHOD_IDS: IntArray = intArrayOf(1200)

    public fun handle(
        impl: LegacyOuter.Service,
        method: PortalMethod.Generated,
        payload: ByteArray,
        granted: List<*>,
    ): ByteArray = "legacy:${payload.decodeToString()}".toByteArray()
}

public class AmbiguousOuter {
    public interface Service
}

public class AmbiguousOuterServiceImpl : AmbiguousOuter.Service

public object AmbiguousOuter_ServicePortalDispatcher {
    public val METHOD_IDS: IntArray = intArrayOf(1201)

    public fun handle(
        impl: AmbiguousOuter.Service,
        method: PortalMethod.Generated,
        payload: ByteArray,
        granted: List<*>,
    ): ByteArray = payload
}

public object Nested_14_AmbiguousOuter_7_ServicePortalDispatcher {
    public val METHOD_IDS: IntArray = intArrayOf(1202)

    public fun handle(
        impl: AmbiguousOuter.Service,
        method: PortalMethod.Generated,
        payload: ByteArray,
        granted: List<*>,
    ): ByteArray = payload
}
