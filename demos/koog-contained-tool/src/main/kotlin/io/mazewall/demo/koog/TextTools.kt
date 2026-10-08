package io.mazewall.demo.koog

import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.serialization.typeToken
import io.mazewall.koog.PortalToolAdapter
import io.mazewall.portal.PortalOperationCall
import io.mazewall.portal.PortalRuntime
import io.mazewall.portal.PortalRuntimeConfig
import io.mazewall.portal.PortalRuntimeLimits
import io.mazewall.portal.PortalServiceId
import io.mazewall.portal.PortalWorkerProfile
import kotlinx.serialization.Serializable
import java.nio.file.Path

@Serializable
public data class TextArguments(public val text: String)

/** Host-owned bindings. Creating a registry does not transfer policy authority to the model. */
public object TextTools {
    public const val WIRE_BYTES: Int = 128 * 1024

    public fun configuration(cache: Path): PortalRuntimeConfig = PortalRuntimeConfig.load(
        workerBundleCache = cache,
        workerProfiles = mapOf("static-baseline" to PortalWorkerProfile()),
        limits = PortalRuntimeLimits(
            workers = 1, maxAdmissions = 8, callTimeoutMs = 10_000,
            startupTimeoutMs = 30_000, closeTimeoutMs = 10_000,
            maxRequestBytes = WIRE_BYTES, maxResponseBytes = WIRE_BYTES, maxCapabilities = 0,
        ),
    )

    public fun transformer(runtime: PortalRuntime): PortalToolAdapter<TextArguments, String> = PortalToolAdapter(
        operation = runtime.prepare(
            TextTransformerService::class.java, PortalServiceId("bounded-text"),
            PortalOperationCall<TextTransformerService, TextArguments, String> { service, arguments ->
                // This mapping and generated host codec run outside worker protection.
                service.uppercaseAscii(arguments.text)
            },
        ),
        argsType = typeToken<TextArguments>(), resultType = typeToken<String>(),
        name = "uppercase_ascii", description = "Uppercase ASCII letters in at most 64 KiB of UTF-8 text; preserve other characters.",
        validateArguments = { TextBounds.requireBounded(it.text) },
        validateResult = TextBounds::requireBounded,
    )

    /** The advertised registry deliberately contains only the reviewed text operation. */
    public fun registry(runtime: PortalRuntime): ToolRegistry = ToolRegistry { tool(transformer(runtime)) }
}
