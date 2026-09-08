package io.mazewall.portal

/** Immutable portal bytes: copies at both ownership boundaries. */
public class PortalPayload(
    bytes: ByteArray,
) {
    private val storage: ByteArray = bytes.copyOf()

    public val size: Int get() = storage.size

    public fun isEmpty(): Boolean = storage.isEmpty()

    public fun copyToByteArray(): ByteArray = storage.copyOf()

    override fun equals(other: Any?): Boolean = other is PortalPayload && storage.contentEquals(other.storage)

    override fun hashCode(): Int = storage.contentHashCode()

    override fun toString(): String = "PortalPayload(size=$size)"
}
