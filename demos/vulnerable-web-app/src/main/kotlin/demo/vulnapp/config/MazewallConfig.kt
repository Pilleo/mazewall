package demo.vulnapp.config

import demo.vulnapp.service.*
import io.mazewall.Policy
import io.mazewall.PolicyScope
import io.mazewall.SbobParser
import io.mazewall.Uncompiled
import io.mazewall.enforcer.api.ContainedExecutors
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.web.multipart.MultipartFile
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.logging.Logger
import java.nio.file.Files
import java.nio.file.Paths

@Configuration
@ConditionalOnProperty(name = [MazewallProtectionBootstrap.RESOLVED_PROPERTY], havingValue = "true")
internal class MazewallConfig(private val executorOwner: ServiceExecutorOwner) {
    private val logger = Logger.getLogger(MazewallConfig::class.java.name)

    private fun wrapExecutor(delegate: ExecutorService, basePolicy: Policy<*, Uncompiled>): OwnedServiceExecutor {
        val sbobPath = System.getProperty("mazewall.sbob.path") ?: "/app/sbob.json"
        val bobFile = Paths.get(sbobPath)
        val finalPolicy = if (Files.exists(bobFile)) {
            logger.info("Engaging SBoB ENFORCEMENT mode. Loading ruleset from: $sbobPath")
            SbobParser.parseToPolicy(bobFile, basePolicy)
        } else {
            logger.warning("SBoB file not found at $sbobPath. Falling back to default static policy.")
            basePolicy
        }

        return executorOwner.own(ContainedExecutors.wrap(delegate, finalPolicy))
    }

    @Bean
    fun adminService(): AdminService {
        val realService = DefaultAdminService()
        val basePolicy = Policy.threadLocalBuilder().base(Policy.NO_NETWORK as Policy<PolicyScope.ThreadLocalOnly, *>).allowMmapExec().build()
        val executor = wrapExecutor(Executors.newFixedThreadPool(4), basePolicy)
        return object : AdminService {
            override fun logMessage(apiVersion: String) =
                executor.call { realService.logMessage(apiVersion) }
        }
    }

    @Bean
    fun proxyService(): ProxyService {
        val realService = DefaultProxyService()
        val basePolicy = Policy.threadLocalBuilder().base(Policy.NO_NETWORK as Policy<PolicyScope.ThreadLocalOnly, *>).allowMmapExec().build()
        val executor = wrapExecutor(Executors.newFixedThreadPool(4), basePolicy)
        return object : ProxyService {
            override fun fetchUrl(url: String) =
                executor.call { realService.fetchUrl(url) }
        }
    }

    @Bean
    fun xmlImportService(): XmlImportService {
        val realService = DefaultXmlImportService()
        val basePolicy = Policy.threadLocalBuilder()
            .base(Policy.NO_NETWORK as Policy<PolicyScope.ThreadLocalOnly, *>)
            .allowMmapExec()
            .allowFsRead("/app/data")
            .allowJvmClasspath()
            .build()
        val executor = wrapExecutor(Executors.newFixedThreadPool(4), basePolicy)
        return object : XmlImportService {
            override fun importXStream(xmlContent: String) =
                executor.call { realService.importXStream(xmlContent) }

            override fun importXXE(xmlContent: String) =
                executor.call { realService.importXXE(xmlContent) }
        }
    }

    @Bean
    fun yamlImportService(): YamlImportService {
        val realService = DefaultYamlImportService()
        val basePolicy = Policy.threadLocalBuilder()
            .base(Policy.NO_NETWORK as Policy<PolicyScope.ThreadLocalOnly, *>)
            .allowMmapExec()
            .allowFsRead("/app/data")
            .allowJvmClasspath()
            .build()
        val executor = wrapExecutor(Executors.newFixedThreadPool(4), basePolicy)
        return object : YamlImportService {
            override fun importYaml(yamlContent: String) =
                executor.call { realService.importYaml(yamlContent) }
        }
    }

    @Bean
    fun fileService(): FileService {
        val realService = DefaultFileService()
        val basePolicy = Policy.threadLocalBuilder()
            .base(Policy.NO_EXEC as Policy<PolicyScope.ThreadLocalOnly, *>)
            .allowMmapExec()
            .allowFsRead("/app/uploads")
            .allowFsWrite("/app/uploads")
            .allowJvmClasspath()
            .build()
        val executor = wrapExecutor(Executors.newFixedThreadPool(4), basePolicy)
        return object : FileService {
            override fun extractZip(file: MultipartFile) =
                executor.call { realService.extractZip(file) }

            override fun runCommand(cmd: String) =
                executor.call { realService.runCommand(cmd) }
        }
    }

    @Bean
    fun deserializationService(): DeserializationService {
        val realService = DefaultDeserializationService()
        val basePolicy = Policy.threadLocalBuilder()
            .base(Policy.PURE_COMPUTE as Policy<PolicyScope.ThreadLocalOnly, *>)
            .allowMmapExec()
            .allowFsRead("/app/data")
            .allowJvmClasspath()
            .build()
        val executor = wrapExecutor(Executors.newFixedThreadPool(4), basePolicy)
        return object : DeserializationService {
            override fun importJackson(jsonContent: String) =
                executor.call { realService.importJackson(jsonContent) }

            override fun importJava(bytes: ByteArray) =
                executor.call { realService.importJava(bytes) }
        }
    }
}
