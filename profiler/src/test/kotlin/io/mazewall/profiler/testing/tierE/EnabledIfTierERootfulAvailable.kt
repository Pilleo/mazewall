package io.mazewall.profiler.testing.tierE

import io.mazewall.profiler.tierE.tools.TierERunConfiguration
import io.mazewall.testing.containers.ContainerEndpoint
import io.mazewall.testing.containers.ContainerEndpointDiscovery
import io.mazewall.testing.containers.ContainerEngine
import io.mazewall.testing.containers.EndpointConfig
import io.mazewall.testing.containers.EndpointSelection
import org.junit.jupiter.api.extension.ConditionEvaluationResult
import org.junit.jupiter.api.extension.ExecutionCondition
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.api.extension.ExtensionContext
import org.junit.jupiter.api.extension.ParameterContext
import org.junit.jupiter.api.extension.ParameterResolver
import java.nio.file.Files
import java.nio.file.Path

@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
@ExtendWith(TierERootfulExtension::class)
public annotation class EnabledIfTierERootfulAvailable

/** A Tier E profile resolves legacy names once; generic discovery receives explicit typed settings. */
public class TierERootfulExtension :
    ExecutionCondition,
    ParameterResolver {
    private fun selection(context: ExtensionContext): EndpointSelection =
        context.root
        .getStore(ExtensionContext.Namespace.create(TierERootfulExtension::class.java))
        .getOrComputeIfAbsent(TierERootfulExtension::class.java.name, {
            try {
                val root = generateSequence(Path.of("").toAbsolutePath()) { it.parent }
                    .first { Files.exists(it.resolve("config/testing/tier-e/smoke.json")) }
                val settings = TierERunConfiguration.load(root.resolve("config/testing/tier-e/smoke.json")).container
                ContainerEndpointDiscovery().discover(EndpointConfig(ContainerEngine.valueOf(settings.engine.uppercase()), settings.uri))
            } catch (error: Exception) {
                EndpointSelection.Skipped("invalid Tier E profile/endpoint: ${error.message}")
            }
        }, EndpointSelection::class.java)

    override fun evaluateExecutionCondition(context: ExtensionContext): ConditionEvaluationResult =
        when (val selected = selection(context)) {
            is EndpointSelection.Ready -> ConditionEvaluationResult.enabled("Rootful Tier E ${selected.endpoint.engine} endpoint selected")
            is EndpointSelection.Skipped -> {
                System.err.println("Tier E rootful container tests skipped: ${selected.reason}")
                ConditionEvaluationResult.disabled(selected.reason)
            }
        }

    override fun supportsParameter(
        parameterContext: ParameterContext,
        extensionContext: ExtensionContext,
    ): Boolean = parameterContext.parameter.type == ContainerEndpoint::class.java

    override fun resolveParameter(
        parameterContext: ParameterContext,
        extensionContext: ExtensionContext,
    ): Any = (selection(extensionContext) as EndpointSelection.Ready).endpoint
}
