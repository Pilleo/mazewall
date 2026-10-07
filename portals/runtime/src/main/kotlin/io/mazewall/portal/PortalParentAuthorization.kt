package io.mazewall.portal

/** Parent sends this only after required parent-side resource setup has succeeded. */
internal data class PortalParentAuthorization(
    val session: String,
    val childPid: Long,
) {
    init {
        require(session.matches(Regex("[0-9a-f]{32}"))) { "invalid parent startup session" }
        require(childPid > 0) { "invalid parent-authorized child PID" }
    }

    fun frame(): PortalFrame = PortalFrame(PortalKind.Request, 0, METHOD, "format=1\nsession=$session\nchildPid=$childPid\n".toByteArray(Charsets.UTF_8), 0)

    fun acknowledgement(): PortalFrame = PortalFrame(PortalKind.Response, 0, METHOD, frame().payload, 0)

    companion object {
        private const val RECORD_FIELDS = 3
        val METHOD = PortalMethod.Generated(Int.MIN_VALUE)

        fun accept(
            frame: PortalFrame,
            expectedSession: String,
            actualPid: Long,
        ): PortalParentAuthorization = decode(frame, expectedSession, actualPid, PortalKind.Request)

        fun acceptAcknowledgement(
            frame: PortalFrame,
            expectedSession: String,
            actualPid: Long,
        ): PortalParentAuthorization = decode(frame, expectedSession, actualPid, PortalKind.Response)

        private fun decode(
            frame: PortalFrame,
            expectedSession: String,
            actualPid: Long,
            expectedKind: PortalKind,
        ): PortalParentAuthorization {
            require(frame.kind == expectedKind && frame.requestId == 0 && frame.method == METHOD && frame.fdCount == 0) { "expected parent startup control frame" }
            val lines = frame.payload
                .copyToByteArray()
                .toString(Charsets.UTF_8)
                .removeSuffix("\n")
                .split('\n')
            require(lines.size == RECORD_FIELDS && lines[0] == "format=1" && lines[1].startsWith("session=") && lines[2].startsWith("childPid=")) { "invalid parent startup authorization record" }
            val rawPid = lines[2].removePrefix("childPid=")
            val pid = requireNotNull(rawPid.toLongOrNull()) { "invalid parent-authorized PID" }
            require(rawPid == pid.toString()) { "noncanonical parent-authorized PID" }
            val permit = PortalParentAuthorization(lines[1].removePrefix("session="), pid)
            require(PortalParentGate.evaluate(PortalParentGatePhase.WAITING, permit.session == expectedSession && permit.childPid == actualPid) == PortalParentGatePhase.AUTHORIZED) {
                "parent startup authorization does not match this session and child"
            }
            return permit
        }
    }
}

internal enum class PortalParentGatePhase { WAITING, AUTHORIZED, REJECTED }

internal object PortalParentGate {
    fun evaluate(
        phase: PortalParentGatePhase,
        matches: Boolean,
    ): PortalParentGatePhase =
        when (phase) {
        PortalParentGatePhase.WAITING -> if (matches) PortalParentGatePhase.AUTHORIZED else PortalParentGatePhase.REJECTED
        PortalParentGatePhase.AUTHORIZED -> error("parent startup authorization already consumed")
        PortalParentGatePhase.REJECTED -> PortalParentGatePhase.REJECTED
    }
}
