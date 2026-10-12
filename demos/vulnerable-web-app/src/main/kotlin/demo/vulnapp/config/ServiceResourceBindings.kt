package demo.vulnapp.config

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.nio.file.Path

/** Trusted deployment/fixture resources, shared by the service and its exact policy grants. */
internal data class ServiceResourceBindings(
    val data: Path = Path.of("/app/data"),
    val uploads: Path = Path.of("/app/uploads"),
)

@Configuration
internal class ServiceResourceConfig {
    @Bean
    @ConditionalOnMissingBean(ServiceResourceBindings::class)
    fun serviceResourceBindings(): ServiceResourceBindings = ServiceResourceBindings()
}
