package io.mazewall.portal

/** Parent sends this only after required parent-side resource setup has succeeded. */
internal data class PortalParentAuthorization(
    val session: String,
    val childPid: Long,
    val launch: String,
    val resources: String = "none",
) {
    constructor(session: String, childPid: Long) : this(session, childPid, PortalLaunchProfile().contract().identity)

    init {
        require(session.matches(Regex("[0-9a-f]{32}"))) { "invalid parent startup session" }
        require(childPid > 0) { "invalid parent-authorized child PID" }
        require(launch.matches(Regex("[0-9a-f]{64}"))) { "invalid parent-authorized launch identity" }
        require(resources == "none" || resources.matches(Regex("[0-9a-f]{64}"))) { "invalid parent resource identity" }
    }

    fun frame(): PortalFrame = PortalFrame(PortalKind.Request, 0, METHOD, "format=2\nsession=$session\nchildPid=$childPid\nlaunch=$launch\nresources=$resources\n".toByteArray(Charsets.UTF_8), 0)

    fun acknowledgement(): PortalFrame = PortalFrame(PortalKind.Response, 0, METHOD, frame().payload, 0)

    companion object {
        private const val RECORD_FIELDS = 5
        private const val LAUNCH_FIELD = 3
        private const val RESOURCES_FIELD = 4
        val METHOD = PortalMethod.Generated(Int.MIN_VALUE)

        fun accept(
            frame: PortalFrame,
            expectedSession: String,
            actualPid: Long,
            expectedLaunch: String = PortalLaunchProfile().contract().identity,
            expectedResources: String = "none",
        ): PortalParentAuthorization = decode(frame, expectedSession, actualPid, expectedLaunch, expectedResources, PortalKind.Request)

        fun acceptAcknowledgement(
            frame: PortalFrame,
            expectedSession: String,
            actualPid: Long,
            expectedLaunch: String = PortalLaunchProfile().contract().identity,
            expectedResources: String = "none",
        ): PortalParentAuthorization = decode(frame, expectedSession, actualPid, expectedLaunch, expectedResources, PortalKind.Response)

        private fun decode(
            frame: PortalFrame,
            expectedSession: String,
            actualPid: Long,
            expectedLaunch: String,
            expectedResources: String,
            expectedKind: PortalKind,
        ): PortalParentAuthorization {
            require(frame.kind == expectedKind && frame.requestId == 0 && frame.method == METHOD && frame.fdCount == 0) { "expected parent startup control frame" }
            val lines = frame.payload
                .copyToByteArray()
                .toString(Charsets.UTF_8)
                .removeSuffix("\n")
                .split('\n')
            require(
                lines.size == RECORD_FIELDS &&
                    lines[0] == "format=2" &&
                    lines[1].startsWith("session=") &&
                    lines[2].startsWith("childPid=") &&
                    lines[LAUNCH_FIELD].startsWith("launch=") &&
                    lines[RESOURCES_FIELD].startsWith("resources="),
            ) {
                "invalid parent startup authorization record"
            }
            val rawPid = lines[2].removePrefix("childPid=")
            val pid = requireNotNull(rawPid.toLongOrNull()) { "invalid parent-authorized PID" }
            require(rawPid == pid.toString()) { "noncanonical parent-authorized PID" }
            val permit = PortalParentAuthorization(lines[1].removePrefix("session="), pid, lines[LAUNCH_FIELD].removePrefix("launch="), lines[RESOURCES_FIELD].removePrefix("resources="))
            require(frame.payload.copyToByteArray().contentEquals(permit.frame().payload.copyToByteArray())) { "noncanonical parent authorization record" }
            require(
                PortalParentGate.evaluate(
                    PortalParentGatePhase.WAITING,
                    permit.session == expectedSession && permit.childPid == actualPid && permit.launch == expectedLaunch && permit.resources == expectedResources,
                ) ==
                    PortalParentGatePhase.AUTHORIZED,
            ) {
                "parent startup authorization does not match this session and child"
            }
            return permit
        }
    }
}

internal enum class PortalParentGatePhase { WAITING, AUTHORIZED, REJECTED }

/** Required membership is observed in procfs on both sides after genuine parent setup. */
internal object PortalParentResources {
    fun measure(
        pid: Long,
        required: Boolean,
    ): String {
        if (!required) return "none"
        val actual = java.nio.file.Files
            .readString(
                java.nio.file.Path
                .of("/proc/$pid/cgroup"),
            )
        require(actual.lineSequence().any { it.startsWith("0::/") && it != "0::/" }) { "required worker cgroup v2 membership is absent" }
        return launchDigest(actual)
    }
}

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
