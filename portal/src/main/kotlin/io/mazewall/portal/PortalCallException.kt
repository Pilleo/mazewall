package io.mazewall.portal

/** Whether the broker can establish that a failed invocation reached a terminal outcome. */
public enum class PortalInvocationEvidence {
    NOT_STARTED,
    COMPLETED,
    UNKNOWN_COMPLETION,
}

/** Fail-closed RPC failure. The broker never falls back to in-process guest code. */
public open class PortalCallException(
    message: String,
    public val evidence: PortalInvocationEvidence = PortalInvocationEvidence.UNKNOWN_COMPLETION,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)

/** A worker completed the call by throwing an exception; it is not a transport or containment failure. */
public class PortalRemoteCallException(
    public val remoteType: String,
    public val remoteMessage: String,
) : PortalCallException(
        "portal worker threw $remoteType: $remoteMessage",
        PortalInvocationEvidence.COMPLETED,
    )

/** Bounded, non-serializing representation of a worker-side exception. */
public data class PortalRemoteFailure(
    val type: String,
    val message: String,
)

/** Wire format for completed worker exceptions. It deliberately does not serialize arbitrary Throwable graphs. */
public object PortalRemoteFailureCodec {
    private const val MAX_FIELD_LENGTH: Int = 4_096

    public fun encode(failure: PortalRemoteFailure): ByteArray {
        require(failure.type.isNotBlank() && failure.type.length <= MAX_FIELD_LENGTH) { "invalid remote exception type" }
        require(failure.message.length <= MAX_FIELD_LENGTH) { "remote exception message is too long" }
        return PortalCodec.concat(listOf(PortalCodec.encodeString(failure.type), PortalCodec.encodeString(failure.message)))
    }

    public fun decode(payload: ByteArray): PortalRemoteFailure {
        val reader = PortalCodec.Reader(payload)
        val type = reader.string().also { require(it.isNotBlank() && it.length <= MAX_FIELD_LENGTH) { "invalid remote exception type" } }
        val message = reader.string().also { require(it.length <= MAX_FIELD_LENGTH) { "remote exception message is too long" } }
        require(reader.exhausted()) { "remote exception payload has trailing bytes" }
        return PortalRemoteFailure(type, message)
    }
}
