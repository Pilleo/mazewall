package io.mazewall.profiler.tierE.daemon

import io.mazewall.profiler.internal.attribution.MarkerVersion

/** Agent-authored startup selection, scoped to the registered diagnostic session. */
internal data class TierEMarkerVersionProof(
    val sessionTag: Int,
    val processStartTime: Long,
    val markerVersion: MarkerVersion,
) {
    fun requireSelectedV2(target: TierERegisteredTarget) {
        require(sessionTag == target.sessionTag && processStartTime == target.processIncarnation && markerVersion == MarkerVersion.V2) {
            "agent marker-version proof does not match registered v2 session and process incarnation"
        }
    }

    companion object {
        fun decode(text: String): TierEMarkerVersionProof {
            val lines = text.trimEnd('\n').split('\n')
            require(lines.size == PROOF_FIELDS) { "marker-version proof must have four fields" }
            val fields = lines.associate { line ->
                val separator = line.indexOf('=')
                require(separator > 0 && separator == line.lastIndexOf('=')) { "invalid marker-version proof field" }
                line.substring(0, separator) to line.substring(separator + 1)
            }
            require(fields.size == PROOF_FIELDS && fields.keys == EXPECTED_KEYS) { "invalid or duplicate marker-version proof fields" }
            require(fields.getValue("version") == "1") { "unsupported marker-version proof format" }
            val tag = requireNotNull(fields.getValue("session_tag").toIntOrNull(16)?.takeIf { it > 0 }) {
                "invalid marker-version proof session tag"
            }
            val startTime = requireNotNull(fields.getValue("process_starttime").toLongOrNull()?.takeIf { it > 0 }) {
                "invalid marker-version proof process incarnation"
            }
            val selected = fields.getValue("marker_version")
            require(selected == "v1" || selected == "v2") { "unsupported selected agent marker version" }
            val version = if (selected == "v2") MarkerVersion.V2 else MarkerVersion.V1
            return TierEMarkerVersionProof(tag, startTime, version)
        }

        private const val PROOF_FIELDS = 4
        private val EXPECTED_KEYS = setOf("version", "session_tag", "process_starttime", "marker_version")
    }
}
