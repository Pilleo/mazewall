package demo.vulnapp.config

import demo.vulnapp.service.*
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
@ConditionalOnProperty(name = [MazewallProtectionBootstrap.RESOLVED_PROPERTY], havingValue = "false", matchIfMissing = true)
internal class MazewallDisabledConfig(
    private val resources: ServiceResourceBindings,
) {
    @Bean
    fun adminService(): AdminService = DefaultAdminService()

    @Bean
    fun proxyService(): ProxyService = DefaultProxyService()

    @Bean
    fun xmlImportService(): XmlImportService = DefaultXmlImportService()

    @Bean
    fun yamlImportService(): YamlImportService = DefaultYamlImportService()

    @Bean
    fun fileService(): FileService = DefaultFileService(resources.uploads.toFile())

    @Bean
    fun deserializationService(): DeserializationService = DefaultDeserializationService()
}
