package demo.vulnapp.config

import io.mazewall.Policy
import io.mazewall.enforcer.api.ContainedExecutors
import org.springframework.boot.context.event.ApplicationEnvironmentPreparedEvent
import org.springframework.context.ApplicationListener
import org.springframework.core.env.ConfigurableEnvironment
import org.springframework.core.env.MapPropertySource
import org.springframework.core.env.PropertyResolver
import java.util.concurrent.atomic.AtomicBoolean

internal data class MazewallProtectionDecision(val enabled: Boolean) {
    companion object {
        fun resolve(properties: PropertyResolver): MazewallProtectionDecision =
            MazewallProtectionDecision(properties.getProperty("mazewall.enabled", Boolean::class.java, false))
    }
}

/** Resolves and installs the process baseline before Spring creates application workers. */
internal class MazewallProtectionBootstrap(
    private val installBaseline: () -> Unit = {
        ContainedExecutors.installOnProcess(
            Policy.builder().base(Policy.NO_EXEC).allowMmapExec().build(),
        )
    },
) : ApplicationListener<ApplicationEnvironmentPreparedEvent> {
    private val prepared = AtomicBoolean()

    override fun onApplicationEvent(event: ApplicationEnvironmentPreparedEvent) {
        prepare(event.environment)
    }

    internal fun prepare(environment: ConfigurableEnvironment): MazewallProtectionDecision {
        check(prepared.compareAndSet(false, true)) { "Mazewall protection was already resolved" }
        val decision = MazewallProtectionDecision.resolve(environment)
        environment.propertySources.addFirst(
            MapPropertySource(
                "mazewallResolvedProtection",
                mapOf(RESOLVED_PROPERTY to decision.enabled.toString()),
            ),
        )
        if (decision.enabled) installBaseline()
        return decision
    }

    companion object {
        const val RESOLVED_PROPERTY = "mazewall.resolved-enabled"
    }
}
