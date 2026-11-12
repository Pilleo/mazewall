package io.mazewall.enforcer.internal.supervisor

import io.mazewall.enforcer.internal.execution.HelperBinding
import io.mazewall.ffi.networking.ConfinedDeadline
import io.mazewall.platform.lifecycle.Pid
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.HexFormat

private const val MAX_ANCESTRY_LENGTH = 8192
private const val MAX_OBJECT_KEY_LENGTH = 512
private const val MAX_READ_SUFFIX_BYTES = 4095
private const val MAX_RUNTIME_CAPABILITIES = 64
private const val MAX_CAPABILITIES = MAX_RUNTIME_CAPABILITIES + 1
private const val TIME_NAMESPACE_INDEX = 11
private const val CHILD_TIME_NAMESPACE_INDEX = 12
private const val UNSIGNED_INT_MAX = 0xffff_ffffL
private const val UNSIGNED_BYTE_MASK = 0xff
private const val UNSIGNED_SHORT_MASK = 0xffff
private const val SHA256_BYTES = 32

internal enum class HelperPurpose(
    val wire: Int,
) {
    Application(1),
    RuntimeSupport(2),
}

internal enum class HelperObjectKind(
    val wire: Int,
) {
    Directory(1),
    Regular(2),
}

internal enum class StopReason(
    val wire: Int,
) {
    Normal(0),
    Cancel(1),
    Timeout(2),
    Failure(3),
}

internal enum class ConfinedRejectReason(
    val wire: Int,
) {
    Malformed(1),
    Identity(2),
    Ancestry(3),
    Capacity(4),
    Permission(5),
    RuntimeClosure(6),
    Stopped(7),
    Timeout(8),
}

internal data class HelperDescriptorIdentity(
    val key: String,
    val mountId: Int,
    val kind: HelperObjectKind,
)

internal data class HelperCapability(
    val index: Int,
    val purpose: HelperPurpose,
    val identity: HelperDescriptorIdentity,
    val contentDigest: String,
)

internal data class ConfinedBinding(
    val owner: HelperBinding,
    val manifestDigest: String,
)

/** Canonical wire expectations only; independently observed peer ancestry is still required. */
internal class HelperAncestry private constructor(
    val canonical: String,
) {
    companion object {
        private const val PROCESS_ID_FIELDS = 4
        private val keys = listOf(
            "uid",
            "gid",
            "groups",
            "capInh",
            "capPrm",
            "capEff",
            "capBnd",
            "capAmb",
            "pidNs",
            "mountNs",
            "userNs",
            "timeNs",
            "timeChildNs",
            "monotonicOffsetSec",
            "monotonicOffsetNsec",
            "uidMap",
            "gidMap",
            "rootKey",
            "mountViewDigest",
            "noNewPrivs",
            "seccomp",
            "seccompFilters",
            "lineage",
        )
        private val unsigned = Regex("0|[1-9][0-9]*")
        private val capability = Regex("[0-9a-f]{16}")
        private val digest = Regex("[0-9a-f]{64}")
        private val objectKey = Regex("\\(dev=[0-9a-f]+,ino=[1-9][0-9]*\\)")

        fun parse(text: String): HelperAncestry {
            require(text.length in 1..MAX_ANCESTRY_LENGTH && text.endsWith('\n') && '\u0000' !in text) { "Invalid helper ancestry envelope" }
            val rows = text.dropLast(1).split('\n')
            require(rows.size == keys.size) { "Incomplete helper ancestry" }
            val values = rows.mapIndexed { index, row ->
                val pair = row.split('=', limit = 2)
                require(pair.size == 2 && pair[0] == keys[index]) { "Noncanonical helper ancestry field" }
                val value = pair[1]
                validateField(pair[0], value)
                value
            }
            require(values[TIME_NAMESPACE_INDEX] == values[CHILD_TIME_NAMESPACE_INDEX]) { "Pending child time namespace switch" }
            return HelperAncestry(text)
        }

        private fun validateField(
            key: String,
            value: String,
        ) {
            when (key) {
                "uid", "gid" -> require(value.split(',').size == PROCESS_ID_FIELDS && value.split(',').all(::isUnsigned))
                "groups" -> validateGroups(value)
                "capInh", "capPrm", "capEff", "capBnd", "capAmb" -> require(capability.matches(value))
                "pidNs", "mountNs", "userNs", "timeNs", "timeChildNs", "rootKey" -> requireObjectKey(value)
                "monotonicOffsetSec", "monotonicOffsetNsec" -> require(value == "0")
                "uidMap", "gidMap", "mountViewDigest" -> require(digest.matches(value))
                "noNewPrivs" -> require(value in setOf("0", "1"))
                "seccomp" -> require(value in setOf("0", "1", "2"))
                "seccompFilters" -> require(isUnsigned(value))
                "lineage" -> require(value in setOf("bootstrap-v1", "tier1-v1"))
                else -> error("Unknown canonical ancestry field")
            }
        }

        private fun validateGroups(value: String) {
            val groups = if (value.isEmpty()) emptyList() else value.split(',')
            require(groups.all(::isUnsigned))
            require(groups.map(String::toLong).zipWithNext().all { (left, right) -> left < right })
        }

        fun requireObjectKey(key: String) {
            require(key.length in 1..MAX_OBJECT_KEY_LENGTH && objectKey.matches(key)) { "Unqualified helper object key" }
        }

        private fun isUnsigned(text: String): Boolean = unsigned.matches(text) && text.toLongOrNull()?.let { it in 0L..UNSIGNED_INT_MAX } == true
    }
}

internal class ConfinedManifest private constructor(
    val binding: ConfinedBinding,
    val ancestry: HelperAncestry,
    val capabilities: List<HelperCapability>,
) {
    companion object {
        fun create(
            binding: HelperBinding,
            ancestry: HelperAncestry,
            capabilities: List<HelperCapability>,
        ): ConfinedManifest {
            val captured = java.util.List.copyOf(capabilities)
            val preimage = ConfinedHelperProtocol.manifestPreimage(binding, ancestry, captured)
            val digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(preimage))
            return ConfinedManifest(ConfinedBinding(binding, digest), ancestry, captured)
        }
    }
}

internal data class ConfinedReadRequest(
    val binding: ConfinedBinding,
    val id: Long,
    val purpose: HelperPurpose,
    val index: Int,
    val suffix: String,
) {
    init {
        require(id > 0 && suffix.encodeToByteArray(throwOnInvalidSequence = true).size in 1..MAX_READ_SUFFIX_BYTES)
        when (purpose) {
            HelperPurpose.Application -> require(index == 0 && suffix.split('/').none { it.isEmpty() || it == "." || it == ".." || '\u0000' in it })
            HelperPurpose.RuntimeSupport -> require(index in 1..MAX_RUNTIME_CAPABILITIES && suffix == ".")
        }
    }
}

internal object ConfinedHelperProtocol {
    const val HEADER_BYTES = 15
    const val MAX_PAYLOAD_BYTES = 65536
    private const val ZERO_DIGEST = "0000000000000000000000000000000000000000000000000000000000000000"
    private val digest = Regex("[0-9a-f]{64}")

    enum class ControlTag(
        val wire: Int,
    ) {
        SetupAccepted(0x54),
        Ready(0x50),
        Retire(0x46),
    }

    enum class RootTag(
        val wire: Int,
    ) {
        Offer(0x44),
        Expected(0x55),
        Received(0x56),
        Complete(0x43),
    }

    enum class ResultTag(
        val wire: Int,
    ) {
        Opened(0x52),
        Expected(0x57),
        Received(0x58),
        Done(0x59),
    }

    enum class StopTag(
        val wire: Int,
    ) {
        Stop(0x42),
        LocalStopped(0x5a),
    }

    data class Header(
        val payloadBytes: Int,
        val deadline: ConfinedDeadline,
    )

    fun header(bytes: ByteArray): Header {
        require(bytes.size == HEADER_BYTES)
        val input = ByteBuffer.wrap(bytes)
        val tag = input.u8()
        require(input.u16() == 1) { "Unsupported confined wire version" }
        val length = input.int
        val permitted = when (tag) {
            0x40 -> 120..MAX_PAYLOAD_BYTES
            0x41 -> 130..4224
            0x54, 0x50, 0x46 -> 116..116
            0x44, 0x55, 0x56, 0x43 -> 118..118
            0x52, 0x57, 0x58, 0x59 -> 124..124
            0x53 -> 128..128
            0x51 -> 122..122
            0x42, 0x5a -> 117..117
            0x5b -> 129..129
            else -> throw IllegalArgumentException("Unknown confined frame tag")
        }
        require(length in permitted) { "Invalid confined payload length" }
        return Header(length, ConfinedDeadline(input.long))
    }

    sealed interface Frame {
        val binding: ConfinedBinding
        val deadline: ConfinedDeadline

        data class Setup(
            val manifest: ConfinedManifest,
            override val deadline: ConfinedDeadline,
        ) : Frame {
            override val binding: ConfinedBinding get() = manifest.binding
        }

        data class Control(
            val tag: ControlTag,
            override val binding: ConfinedBinding,
            override val deadline: ConfinedDeadline,
        ) : Frame

        data class Root(
            val tag: RootTag,
            override val binding: ConfinedBinding,
            val index: Int,
            override val deadline: ConfinedDeadline,
        ) : Frame

        data class Read(
            val request: ConfinedReadRequest,
            override val deadline: ConfinedDeadline,
        ) : Frame {
            override val binding: ConfinedBinding get() = request.binding
        }

        data class Result(
            val tag: ResultTag,
            override val binding: ConfinedBinding,
            val id: Long,
            override val deadline: ConfinedDeadline,
        ) : Frame

        data class Denied(
            override val binding: ConfinedBinding,
            val id: Long,
            val errno: Int,
            override val deadline: ConfinedDeadline,
        ) : Frame

        data class Rejected(
            override val binding: ConfinedBinding,
            val reason: ConfinedRejectReason,
            val errno: Int?,
            override val deadline: ConfinedDeadline,
        ) : Frame

        data class Stop(
            val tag: StopTag,
            override val binding: ConfinedBinding,
            val reason: StopReason,
            override val deadline: ConfinedDeadline,
        ) : Frame

        data class Retired(
            override val binding: ConfinedBinding,
            val reason: StopReason,
            val helperPid: Pid,
            val helperStartTicks: Long,
            override val deadline: ConfinedDeadline,
        ) : Frame
    }

    fun encode(frame: Frame): ByteArray {
        val payload = ByteArrayOutputStream()
        val output = DataOutputStream(payload)
        output.owner(frame.binding.owner)
        output.digest(frame.binding.manifestDigest)
        val tag = when (frame) {
            is Frame.Setup -> {
                output.metadata(frame.manifest.ancestry, frame.manifest.capabilities)
                0x40
            }
            is Frame.Control -> frame.tag.wire
            is Frame.Root -> {
                require(frame.index in if (frame.tag == RootTag.Complete) 2..65 else 0..64)
                output.writeShort(frame.index)
                frame.tag.wire
            }
            is Frame.Read -> {
                output.requestId(frame.request.id)
                output.writeByte(frame.request.purpose.wire)
                output.writeShort(frame.request.index)
                output.text(frame.request.suffix, 4095)
                0x41
            }
            is Frame.Result -> {
                output.requestId(frame.id)
                frame.tag.wire
            }
            is Frame.Denied -> {
                output.requestId(frame.id)
                require(frame.errno in 1..4095)
                output.writeInt(frame.errno)
                0x53
            }
            is Frame.Rejected -> {
                require(frame.errno == null || frame.errno in 1..4095)
                output.writeShort(frame.reason.wire)
                output.writeInt(frame.errno ?: 0)
                0x51
            }
            is Frame.Stop -> {
                output.writeByte(frame.reason.wire)
                frame.tag.wire
            }
            is Frame.Retired -> {
                require(frame.helperPid.value > 0 && frame.helperStartTicks > 0)
                output.writeByte(frame.reason.wire)
                output.writeInt(frame.helperPid.value)
                output.writeLong(frame.helperStartTicks)
                0x5b
            }
        }
        require(payload.size() <= MAX_PAYLOAD_BYTES)
        return ByteBuffer
            .allocate(HEADER_BYTES + payload.size())
            .put(tag.toByte())
            .putShort(1)
            .putInt(payload.size())
            .putLong(frame.deadline.nanos)
            .put(payload.toByteArray())
            .array()
    }

    fun decode(bytes: ByteArray): Frame {
        require(bytes.size in HEADER_BYTES..HEADER_BYTES + MAX_PAYLOAD_BYTES)
        val input = ByteBuffer.wrap(bytes)
        val header = header(bytes.copyOfRange(0, HEADER_BYTES))
        require(header.payloadBytes == bytes.size - HEADER_BYTES) { "Incomplete or trailing confined payload" }
        val deadline = header.deadline
        val tag = bytes[0].toInt() and 0xff
        input.position(HEADER_BYTES)
        val binding = ConfinedBinding(
            HelperBinding(Pid(input.int), input.long, input.long, input.digest(), input.digest()),
            input.digest(),
        )
        val frame = when (tag) {
            0x40 -> {
                val ancestry = HelperAncestry.parse(input.text(MAX_ANCESTRY_LENGTH))
                val count = input.u16()
                require(count in 2..MAX_CAPABILITIES)
                val capabilities = List(count) { _ ->
                    val index = input.u16()
                    val purpose = purpose(input.u8())
                    val kindWire = input.u8()
                    val kind = requireNotNull(HelperObjectKind.entries.find { it.wire == kindWire })
                    val mount = input.int
                    HelperCapability(index, purpose, HelperDescriptorIdentity(input.text(MAX_OBJECT_KEY_LENGTH), mount, kind), input.digest())
                }
                val manifest = ConfinedManifest.create(binding.owner, ancestry, capabilities)
                require(manifest.binding == binding) { "Confined manifest digest mismatch" }
                Frame.Setup(manifest, deadline)
            }
            0x54, 0x50, 0x46 -> Frame.Control(ControlTag.entries.single { it.wire == tag }, binding, deadline)
            0x44, 0x55, 0x56, 0x43 -> Frame.Root(RootTag.entries.single { it.wire == tag }, binding, input.u16(), deadline)
            0x41 -> Frame.Read(ConfinedReadRequest(binding, input.long, purpose(input.u8()), input.u16(), input.text(4095)), deadline)
            0x52, 0x57, 0x58, 0x59 -> Frame.Result(ResultTag.entries.single { it.wire == tag }, binding, input.long, deadline)
            0x53 -> Frame.Denied(binding, input.long, input.int, deadline)
            0x51 -> {
                val reason = input.u16()
                Frame.Rejected(binding, requireNotNull(ConfinedRejectReason.entries.find { it.wire == reason }), input.int.takeUnless { it == 0 }, deadline)
            }
            0x42, 0x5a -> Frame.Stop(StopTag.entries.single { it.wire == tag }, binding, stopReason(input.u8()), deadline)
            0x5b -> Frame.Retired(binding, stopReason(input.u8()), Pid(input.int), input.long, deadline)
            else -> throw IllegalArgumentException("Unknown confined frame tag")
        }
        require(!input.hasRemaining()) { "Trailing confined payload" }
        // One serializer validates all locally constructed and received rows identically.
        require(encode(frame).contentEquals(bytes)) { "Noncanonical confined frame" }
        return frame
    }

    fun manifestPreimage(
        binding: HelperBinding,
        ancestry: HelperAncestry,
        capabilities: List<HelperCapability>,
    ): ByteArray {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).apply {
            write("MWB4R1\u0000".toByteArray(Charsets.US_ASCII))
            writeShort(1)
            owner(binding)
            metadata(ancestry, capabilities)
        }
        return bytes.toByteArray()
    }

    private fun DataOutputStream.metadata(
        ancestry: HelperAncestry,
        capabilities: List<HelperCapability>,
    ) {
        text(ancestry.canonical, MAX_ANCESTRY_LENGTH)
        require(capabilities.size in 2..MAX_CAPABILITIES && capabilities.map { it.identity.key }.distinct().size == capabilities.size)
        writeShort(capabilities.size)
        capabilities.forEachIndexed { index, capability ->
            require(capability.index == index && capability.identity.mountId > 0)
            HelperAncestry.requireObjectKey(capability.identity.key)
            when (capability.purpose) {
                HelperPurpose.Application -> require(index == 0 && capability.identity.kind == HelperObjectKind.Directory && capability.contentDigest == ZERO_DIGEST)
                HelperPurpose.RuntimeSupport -> require(index > 0 && capability.identity.kind == HelperObjectKind.Regular)
            }
            writeShort(index)
            writeByte(capability.purpose.wire)
            writeByte(capability.identity.kind.wire)
            writeInt(capability.identity.mountId)
            text(capability.identity.key, MAX_OBJECT_KEY_LENGTH)
            digest(capability.contentDigest)
        }
    }

    private fun DataOutputStream.owner(binding: HelperBinding) {
        require(binding.ownerPid.value > 0 && binding.ownerStartTicks > 0 && binding.domainGeneration > 0)
        writeInt(binding.ownerPid.value)
        writeLong(binding.ownerStartTicks)
        writeLong(binding.domainGeneration)
        digest(binding.resourceGeneration)
        digest(binding.policyDigest)
    }

    private fun DataOutputStream.requestId(id: Long) {
        require(id > 0)
        writeLong(id)
    }

    private fun DataOutputStream.digest(value: String) {
        require(digest.matches(value)) { "Invalid helper digest" }
        write(HexFormat.of().parseHex(value))
    }

    private fun DataOutputStream.text(
        value: String,
        limit: Int,
    ) {
        val bytes = value.encodeToByteArray(throwOnInvalidSequence = true)
        require(bytes.size in 1..limit)
        writeShort(bytes.size)
        write(bytes)
    }

    private fun ByteBuffer.u8(): Int = get().toInt() and UNSIGNED_BYTE_MASK

    private fun ByteBuffer.u16(): Int = short.toInt() and UNSIGNED_SHORT_MASK

    private fun ByteBuffer.digest(): String = HexFormat.of().formatHex(ByteArray(SHA256_BYTES).also(::get))

    private fun ByteBuffer.text(limit: Int): String {
        val length = u16()
        require(length in 1..limit && length <= remaining())
        return ByteArray(length).also(::get).decodeToString(throwOnInvalidSequence = true)
    }

    private fun purpose(wire: Int): HelperPurpose = requireNotNull(HelperPurpose.entries.find { it.wire == wire }) { "Unknown helper purpose" }

    private fun stopReason(wire: Int): StopReason = requireNotNull(StopReason.entries.find { it.wire == wire }) { "Unknown helper stop reason" }
}
