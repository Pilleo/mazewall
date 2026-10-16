package io.mazewall.portal

/** Socket control evidence, never a stdout sentinel or an echo of parent protection flags. */
internal data class PortalWorkerReady(
    val session: String,
    val childPid: Long,
    val generation: PortalWorkerGeneration,
    val launch: String = PortalLaunchProfile().contract().identity,
    val resources: String = "none",
) {
    init {
        require(session.matches(Regex("[0-9a-f]{32}")) && childPid > 0) { "invalid ready session or child PID" }
    }

    fun frame(): PortalFrame = PortalFrame(PortalKind.Response, 0, METHOD, record().toByteArray(Charsets.US_ASCII), 0)

    private fun record(): String =
        "format=2\nsession=$session\nchildPid=$childPid\ninputs=${generation.inputs}\nmanifests=${generation.manifests}\nruntime=${generation.runtime}\nlaunch=$launch\nresources=$resources\npolicy=$BASELINE\nprocessWide=1\nseccomp=1\nfilesystem=1\n"

    companion object {
        const val BASELINE = "hotspot-jit-filesystem-no-process-no-network-v1"
        val METHOD = PortalMethod.Generated(Int.MIN_VALUE + 1)
        private const val FIELD_COUNT = 12
        private const val ASCII_LF = 10
        private const val ASCII_PRINTABLE_MIN = 32
        private const val ASCII_PRINTABLE_MAX = 126
        private val HEX_DIGEST = Regex("[0-9a-f]{64}")

        fun accept(
            frame: PortalFrame,
            expected: PortalWorkerReady,
        ): PortalWorkerReady {
            require(frame.kind == PortalKind.Response && frame.method == METHOD && frame.requestId == 0 && frame.fdCount == 0) { "expected worker ready control frame" }
            require(frame.payload.size <= PortalChannel.MAX_CONTROL_BYTES) { "oversized worker ready record" }
            val bytes = frame.payload.copyToByteArray()
            require(bytes.all { it == ASCII_LF.toByte() || it in ASCII_PRINTABLE_MIN..ASCII_PRINTABLE_MAX }) { "worker ready record must be ASCII with LF" }
            val text = bytes.toString(Charsets.US_ASCII)
            val fields = text.removeSuffix("\n").split('\n')
            require(fields.size == FIELD_COUNT && text.endsWith('\n')) { "invalid worker ready record fields" }
            require(
                listOf(expected.generation.inputs, expected.generation.manifests, expected.generation.runtime, expected.launch).all {
                it.matches(HEX_DIGEST)
            },
            ) { "invalid expected generation or launch identity" }
            require(text == expected.record()) { "worker ready record does not match required session, PID, protocol, generation or protection" }
            return expected
        }
    }
}

internal enum class PortalReadyPhase { WAITING, READY, REJECTED }

/** Pure publication decision: cancellation/deadline/identity never reopen a terminal handshake. */
internal object PortalReadyGate {
    fun evaluate(
        phase: PortalReadyPhase,
        matches: Boolean,
        active: Boolean,
        withinDeadline: Boolean,
    ): PortalReadyPhase =
        when (phase) {
            PortalReadyPhase.WAITING -> if (matches && active && withinDeadline) PortalReadyPhase.READY else PortalReadyPhase.REJECTED
            PortalReadyPhase.READY -> error("worker ready record already consumed")
            PortalReadyPhase.REJECTED -> PortalReadyPhase.REJECTED
        }
}
