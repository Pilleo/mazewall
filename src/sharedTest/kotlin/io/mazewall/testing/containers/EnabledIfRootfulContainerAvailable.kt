package io.mazewall.testing.containers

import org.junit.jupiter.api.extension.ConditionEvaluationResult
import org.junit.jupiter.api.extension.ExecutionCondition
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.api.extension.ExtensionContext
import org.junit.jupiter.api.extension.ParameterContext
import org.junit.jupiter.api.extension.ParameterResolver

@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
@ExtendWith(RootfulContainerExtension::class)
public annotation class EnabledIfRootfulContainerAvailable

public class RootfulContainerExtension :
    ExecutionCondition,
    ParameterResolver {
    private val key: String = RootfulContainerExtension::class.java.name

    private fun selection(context: ExtensionContext): EndpointSelection =
        context.root
            .getStore(ExtensionContext.Namespace.create(RootfulContainerExtension::class.java))
            .getOrComputeIfAbsent(key, { ContainerEndpointDiscovery().discover() }, EndpointSelection::class.java)

    override fun evaluateExecutionCondition(context: ExtensionContext): ConditionEvaluationResult =
        when (val selected = selection(context)) {
            is EndpointSelection.Ready -> ConditionEvaluationResult.enabled("Rootful ${selected.endpoint.engine} endpoint selected")
            is EndpointSelection.Skipped -> ConditionEvaluationResult.disabled(selected.reason)
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
