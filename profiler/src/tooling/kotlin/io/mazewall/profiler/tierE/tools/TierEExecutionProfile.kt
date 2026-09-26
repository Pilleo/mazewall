package io.mazewall.profiler.tierE.tools

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import java.nio.file.Path

/** Remaining fixture and ownership settings; mutable run IDs/resources are allocated separately. */
public data class TierEExecutionProfile(
    val markerVersion: String,
    val markerBackend: String,
    val stackTransport: String,
    val socketAccess: String,
    val faultSyscall: Int?,
    val negativeArtifact: String?,
    val identityCase: String?,
    val liveSnapshots: Boolean,
    val baseline: Boolean,
    val benchmark: Boolean,
    val allowIncomplete: Boolean,
    val delayedArtifact: Boolean = false,
) {
    public companion object {
        private val fields = setOf(
            "marker_version",
            "marker_backend",
            "stack_transport",
            "socket_access",
            "fault_syscall",
            "negative_artifact",
            "identity_case",
            "live_snapshots",
            "baseline",
            "benchmark",
            "allow_incomplete",
            "delayed_artifact",
        )

        public fun parse(
            section: JsonObject?,
            recovery: String,
        ): TierEExecutionProfile {
            if (section == null) {
                return TierEExecutionProfile(
                    if (recovery == "disabled") "v1" else "v2",
                    "uprobe",
                "dictionary",
                    "group",
                    null,
                    null,
                    null,
                    false,
                    false,
                    false,
                    false,
                    false,
                )
            }
            require(section.keys == fields || section.keys == fields - "delayed_artifact") {
                "Unexpected or missing Tier E scenario fields: ${section.keys - fields}, ${(fields - "delayed_artifact") - section.keys}"
            }

            fun text(key: String): String {
                val value = section[key] as? JsonPrimitive
                require(value != null && value.isString && value.content.isNotBlank()) { "Invalid Tier E scenario field: $key" }
                return value.content
            }

            fun optional(key: String): String? {
                val value = section[key]
                if (value == JsonNull) return null
                val text = value as? JsonPrimitive
                require(text != null && text.isString && text.content.isNotBlank()) { "Invalid Tier E scenario field: $key" }
                return text.content
            }

            fun flag(key: String): Boolean =
                when (section[key]?.toString()) {
                "true" -> true
                "false" -> false
                else -> throw IllegalArgumentException("Invalid Tier E scenario flag: $key")
            }
            val fault = section["fault_syscall"]?.let { if (it == JsonNull) null else (it as? JsonPrimitive)?.takeUnless(JsonPrimitive::isString)?.intOrNull }
            require(section["fault_syscall"] == JsonNull || fault != null) { "Invalid fault syscall" }
            return TierEExecutionProfile(
                text("marker_version"),
                text("marker_backend"),
                text("stack_transport"),
                text("socket_access"),
                fault,
                optional("negative_artifact"),
                optional("identity_case"),
                flag("live_snapshots"),
                flag("baseline"),
                flag("benchmark"),
                flag("allow_incomplete"),
                if ("delayed_artifact" in section) flag("delayed_artifact") else false,
            )
        }
    }
}

public data class TierEBuildProfile(
    val nativeBuildDir: Path?,
    val reuse: Boolean,
) {
    public companion object {
        public fun parse(section: JsonObject?): TierEBuildProfile {
            if (section == null) return TierEBuildProfile(null, false)
            require(section.keys == setOf("native_build_dir", "reuse")) { "Invalid Tier E build profile fields" }
            val raw = section["native_build_dir"]
            require(raw == JsonNull || (raw is JsonPrimitive && raw.isString && raw.content.isNotBlank()))
            val reuse = section["reuse"]?.toString()
            require(reuse in setOf("true", "false"))
            return TierEBuildProfile((raw as? JsonPrimitive)?.takeUnless { it == JsonNull }?.content?.let(Path::of), reuse == "true")
        }
    }
}

public data class TierEDeadlines(
    val armSeconds: Int,
    val terminalSeconds: Int,
    val stopSeconds: Int,
) {
    public companion object {
        public fun parse(section: JsonObject?): TierEDeadlines {
            if (section == null) return TierEDeadlines(600, 90, 10)
            require(section.keys == setOf("arm_seconds", "terminal_seconds", "stop_seconds")) { "Invalid Tier E deadline fields" }

            fun positive(name: String): Int =
                (section[name] as? JsonPrimitive)
                    ?.takeUnless(JsonPrimitive::isString)
                    ?.intOrNull
                ?.takeIf { it > 0 } ?: throw IllegalArgumentException("Invalid Tier E deadline: $name")
            return TierEDeadlines(positive("arm_seconds"), positive("terminal_seconds"), positive("stop_seconds"))
        }
    }
}

public data class TierEArtifactPolicy(
    val outputRoot: Path?,
    val fresh: Boolean,
    val retainOnFailure: Boolean,
) {
    public companion object {
        public fun parse(section: JsonObject?): TierEArtifactPolicy {
            if (section == null) return TierEArtifactPolicy(null, true, true)
            require(section.keys == setOf("output_root", "fresh", "retain_on_failure")) { "Invalid Tier E artifact policy fields" }
            val path = section["output_root"]
            require(path == JsonNull || (path is JsonPrimitive && path.isString && path.content.isNotBlank()))

            fun flag(name: String): Boolean =
                when (section[name]?.toString()) {
                "true" -> true
                "false" -> false
                else -> throw IllegalArgumentException("Invalid Tier E artifact flag: $name")
            }
            return TierEArtifactPolicy((path as? JsonPrimitive)?.takeUnless { it == JsonNull }?.content?.let(Path::of), flag("fresh"), flag("retain_on_failure"))
        }
    }
}
