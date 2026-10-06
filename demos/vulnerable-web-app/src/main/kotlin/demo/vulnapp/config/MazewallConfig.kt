package demo.vulnapp.config

import demo.vulnapp.service.*
import io.mazewall.enforcer.api.ContainedExecutors
import io.mazewall.enforcer.api.Policy
import io.mazewall.enforcer.api.PolicyScope
import io.mazewall.enforcer.api.RuntimeProfile
import io.mazewall.enforcer.api.SbobParser
import io.mazewall.enforcer.api.Uncompiled
import io.mazewall.enforcer.api.WorkerPolicyBuilder
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.web.multipart.MultipartFile
import java.nio.file.Files
import java.nio.file.Paths
import java.util.concurrent.Executors
import java.util.logging.Logger

@Configuration
@ConditionalOnProperty(name = [MazewallProtectionBootstrap.RESOLVED_PROPERTY], havingValue = "true")
internal class MazewallConfig(
    private val executorOwner: ServiceExecutorOwner,
) {
    private val logger = Logger.getLogger(MazewallConfig::class.java.name)

    private fun wrapExecutor(basePolicy: Policy<*, Uncompiled>): OwnedServiceExecutor {
        val sbobPath = System.getProperty("mazewall.sbob.path") ?: "/app/sbob.json"
        val bobFile = Paths.get(sbobPath)
        check(Files.isRegularFile(bobFile)) {
            "Required profile evidence is missing at $sbobPath. Complete profiling and orderly shutdown before enforcement."
        }
        val evidenceFile = ProfileArtifactPublisher.resolveEvidencePath(bobFile)
        logger.info("Engaging SBoB ENFORCEMENT mode. Loading ruleset from: $sbobPath")
        val finalPolicy = SbobParser.parseToPolicy(evidenceFile, basePolicy)

        return executorOwner.own(ContainedExecutors.wrap(Executors.newFixedThreadPool(4), finalPolicy))
    }

    @Bean
    fun adminService(): AdminService {
        val realService = DefaultAdminService()
        val basePolicy = WorkerPolicyBuilder
            .denyList(RuntimeProfile.HOTSPOT_JIT)
            .base(Policy.NO_NETWORK as Policy<PolicyScope.ThreadLocalOnly, *>)
            .allowMmapExec()
            .build()
        val executor = wrapExecutor(basePolicy)
        return object : AdminService {
            override fun logMessage(apiVersion: String) = executor.call { realService.logMessage(apiVersion) }
        }
    }

    @Bean
    fun proxyService(): ProxyService {
        val realService = DefaultProxyService()
        val basePolicy = WorkerPolicyBuilder
            .denyList(RuntimeProfile.HOTSPOT_JIT)
            .base(Policy.NO_NETWORK as Policy<PolicyScope.ThreadLocalOnly, *>)
            .allowMmapExec()
            .build()
        val executor = wrapExecutor(basePolicy)
        return object : ProxyService {
            override fun fetchUrl(url: String) = executor.call { realService.fetchUrl(url) }
        }
    }

    @Bean
    fun xmlImportService(): XmlImportService {
        val realService = DefaultXmlImportService()
        val basePolicy = WorkerPolicyBuilder
            .denyList(RuntimeProfile.HOTSPOT_JIT)
            .base(Policy.NO_NETWORK as Policy<PolicyScope.ThreadLocalOnly, *>)
            .allowMmapExec()
            .allowFsRead("/app/data")
            .allowJvmClasspath()
            .build()
        val executor = wrapExecutor(basePolicy)
        return object : XmlImportService {
            override fun importXStream(xmlContent: String) = executor.call { realService.importXStream(xmlContent) }

            override fun importXXE(xmlContent: String) = executor.call { realService.importXXE(xmlContent) }
        }
    }

    @Bean
    fun yamlImportService(): YamlImportService {
        val realService = DefaultYamlImportService()
        val basePolicy = WorkerPolicyBuilder
            .denyList(RuntimeProfile.HOTSPOT_JIT)
            .base(Policy.NO_NETWORK as Policy<PolicyScope.ThreadLocalOnly, *>)
            .allowMmapExec()
            .allowFsRead("/app/data")
            .allowJvmClasspath()
            .build()
        val executor = wrapExecutor(basePolicy)
        return object : YamlImportService {
            override fun importYaml(yamlContent: String) = executor.call { realService.importYaml(yamlContent) }
        }
    }

    @Bean
    fun fileService(): FileService {
        val realService = DefaultFileService()
        val basePolicy = WorkerPolicyBuilder
            .denyList(RuntimeProfile.HOTSPOT_JIT)
            .base(Policy.NO_EXEC as Policy<PolicyScope.ThreadLocalOnly, *>)
            .allowMmapExec()
            .allowFsRead("/app/uploads")
            .allowFsWrite("/app/uploads")
            .allowJvmClasspath()
            .build()
        val executor = wrapExecutor(basePolicy)
        return object : FileService {
            override fun extractZip(file: MultipartFile) = executor.call { realService.extractZip(file) }

            override fun runCommand(cmd: String) = executor.call { realService.runCommand(cmd) }
        }
    }

    @Bean
    fun deserializationService(): DeserializationService {
        val realService = DefaultDeserializationService()
        val basePolicy = WorkerPolicyBuilder
            .denyList(RuntimeProfile.HOTSPOT_JIT)
            .base(Policy.PURE_COMPUTE as Policy<PolicyScope.ThreadLocalOnly, *>)
            .allowMmapExec()
            .allowFsRead("/app/data")
            .allowJvmClasspath()
            .build()
        val executor = wrapExecutor(basePolicy)
        return object : DeserializationService {
            override fun importJackson(jsonContent: String) = executor.call { realService.importJackson(jsonContent) }

            override fun importJava(bytes: ByteArray) = executor.call { realService.importJava(bytes) }
        }
    }
}
