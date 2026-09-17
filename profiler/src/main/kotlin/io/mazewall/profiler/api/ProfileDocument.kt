package io.mazewall.profiler.api

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Versioned, operator-portable profile evidence. It carries the observed SBoB and
 * the coverage measured for that observation; it is not a signed artifact.
 */
public class ProfileDocument private constructor(
    public val formatVersion: Int,
    public val behavior: BillOfBehavior,
    public val coverage: ProfilingCoverage,
) {
    public fun toJson(): String = serializer.encodeToString(JsonObject.serializer(), encoded())

    private fun encoded(): JsonObject =
        buildJsonObject {
            put("formatVersion", formatVersion)
            put("behavior", serializer.parseToJsonElement(behavior.toJson()))
            put("coverage", coverage.encode())
        }

    public companion object {
        public const val CURRENT_FORMAT_VERSION: Int = 1

        public fun from(result: ProfilingResult<*>): ProfileDocument = ProfileDocument(CURRENT_FORMAT_VERSION, result.behavior, result.coverage)

        @JvmStatic
        public fun fromJson(json: String): ProfileDocument {
            val root = serializer.parseToJsonElement(json).jsonObject
            val version = root.requiredInt("formatVersion")
            require(version == CURRENT_FORMAT_VERSION) { "Unsupported profile document format version: $version" }
            val behavior = BillOfBehavior.fromJson(root.required("behavior").toString())
            val coverage = root.required("coverage").jsonObject.decodeCoverage()
            return ProfileDocument(version, behavior, coverage)
        }
    }
}

private val serializer = Json {
    prettyPrint = true
    ignoreUnknownKeys = false
}

private fun ProfilingCoverage.encode(): JsonObject =
    buildJsonObject {
        put("strategy", strategy.name)
        put("strategyReason", strategyReason)
        put("processWide", processWide)
        put("ioUring", ioUring.name)
        put("pathResolution", pathResolution.name)
        put("stacks", stacks.name)
        put("droppedEvents", droppedEvents)
        put("drainComplete", drainComplete)
        put(
            "environment",
            buildJsonObject {
                put("kernelRelease", environment.kernelRelease)
                put("ebpfLoad", environment.ebpfLoad.encode())
                environment.landlockAbi?.let { put("landlockAbi", it) }
                environment.ioUringDisabled?.let { put("ioUringDisabled", it) }
            },
        )
        put(
            "integrity",
            buildJsonObject {
                put("complete", complete)
                put("warnings", buildJsonArray { warnings.forEach { add(JsonPrimitive(it)) } })
            },
        )
        put(
            "pathFailures",
            buildJsonArray {
                pathFailures.forEach { failure ->
                    add(
                        buildJsonObject {
                            put("operandIndex", failure.operandIndex)
                            put("reason", failure.reason::class.simpleName ?: error("unnamed path failure reason"))
                            put("detail", failure.detail)
                            failure.cause?.let { cause ->
                                put(
                                    "cause",
                                    buildJsonObject {
                                        put("type", cause.type)
                                        cause.message?.let { put("message", it) }
                                    },
                                )
                            }
                        },
                    )
                }
            },
        )
    }

private fun EbpfLoad.encode(): JsonObject =
    buildJsonObject {
        when (this@encode) {
            EbpfLoad.Available -> put("kind", "AVAILABLE")
            EbpfLoad.UserNamespaceRoot -> put("kind", "USER_NAMESPACE_ROOT")
            is EbpfLoad.Denied -> {
                put("kind", "DENIED")
                put("reason", reason)
            }
        }
    }

private fun JsonObject.decodeCoverage(): ProfilingCoverage {
    val environment = required("environment").jsonObject
    val integrity = required("integrity").jsonObject
    val decoded =
        ProfilingCoverage(
            strategy = enumValueOf(requiredString("strategy")),
            strategyReason = requiredString("strategyReason"),
            processWide = requiredBoolean("processWide"),
            ioUring = enumValueOf(requiredString("ioUring")),
            pathResolution = enumValueOf(requiredString("pathResolution")),
            stacks = enumValueOf(requiredString("stacks")),
            droppedEvents = requiredInt("droppedEvents"),
            drainComplete = requiredBoolean("drainComplete"),
            environment = ProfileEnvironment(
                kernelRelease = environment.requiredString("kernelRelease"),
                ebpfLoad = environment.required("ebpfLoad").jsonObject.decodeEbpfLoad(),
                landlockAbi = environment["landlockAbi"]?.jsonPrimitive?.int,
                ioUringDisabled = environment["ioUringDisabled"]?.jsonPrimitive?.boolean,
            ),
            integrity = ProfileIntegrity(
                complete = integrity.requiredBoolean("complete"),
                warnings = integrity.required("warnings").jsonArray.map { it.jsonPrimitive.content },
            ),
        )
    return decoded.withPathFailures(required("pathFailures").jsonArray.map(JsonElement::decodePathFailure))
}

private fun JsonObject.decodeEbpfLoad(): EbpfLoad =
    when (requiredString("kind")) {
        "AVAILABLE" -> EbpfLoad.Available
        "USER_NAMESPACE_ROOT" -> EbpfLoad.UserNamespaceRoot
        "DENIED" -> EbpfLoad.Denied(requiredString("reason"))
        else -> error("Unknown eBPF load status")
    }

private fun JsonElement.decodePathFailure(): PathResolutionFailure {
    val raw = jsonObject
    val cause = raw["cause"]?.jsonObject?.let { PathResolutionCause(it.requiredString("type"), it["message"]?.jsonPrimitive?.content) }
    return PathResolutionFailure(
        operandIndex = raw.requiredInt("operandIndex"),
        reason =
            when (raw.requiredString("reason")) {
                "VmReadDenied" -> PathResolutionFailure.Reason.VmReadDenied
                "Missing" -> PathResolutionFailure.Reason.Missing
                "DirFdUnresolved" -> PathResolutionFailure.Reason.DirFdUnresolved
                "Truncated" -> PathResolutionFailure.Reason.Truncated
                else -> error("Unknown path failure reason")
            },
        detail = raw.requiredString("detail"),
        cause = cause,
    )
}

private fun JsonObject.required(name: String): JsonElement = requireNotNull(this[name]) { "Profile document is missing '$name'" }

private fun JsonObject.requiredString(name: String): String = required(name).jsonPrimitive.content

private fun JsonObject.requiredInt(name: String): Int = required(name).jsonPrimitive.int

private fun JsonObject.requiredBoolean(name: String): Boolean = required(name).jsonPrimitive.boolean
