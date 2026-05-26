package demo.vulnapp.config

import demo.vulnapp.service.*
import io.mazewall.Policy
import io.mazewall.enforcer.ContainedExecutors
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.web.multipart.MultipartFile
import java.util.concurrent.Executors

import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import java.util.logging.Logger

@Configuration
@ConditionalOnProperty(name = ["mazewall.enabled"], havingValue = "true")
class MazewallConfig {
    private val logger = Logger.getLogger(MazewallConfig::class.java.name)

    @EventListener(ApplicationReadyEvent::class)
    fun onApplicationReady() {
        // Tier 1 Baseline: Block shell execution process-wide.
        // Even after warmup, the JVM (JIT) may still need to commit executable memory.
        // We use a baseline that blocks execve/fork/etc. but allows mmap(PROT_EXEC).
        logger.info("Application ready. Engaging Mazewall process-wide NO_EXEC baseline (mmap-exec allowed for JIT).")
        ContainedExecutors.installOnProcess(
            Policy.builder()
                .base(Policy.NO_EXEC)
                .allowMmapExec()
                .build()
        )
    }

    @Bean
    fun adminService(): AdminService {
        val realService = DefaultAdminService()
        val executor = ContainedExecutors.wrap(
            Executors.newFixedThreadPool(4),
            Policy.builder().base(Policy.NO_NETWORK).allowMmapExec().build()
        )
        return object : AdminService {
            override fun logMessage(apiVersion: String) =
                executor.submit<String> { realService.logMessage(apiVersion) }.get()
        }
    }

    @Bean
    fun proxyService(): ProxyService {
        val realService = DefaultProxyService()
        val executor = ContainedExecutors.wrap(
            Executors.newFixedThreadPool(4),
            Policy.builder().base(Policy.NO_NETWORK).allowMmapExec().build()
        )
        return object : ProxyService {
            override fun fetchUrl(url: String) =
                executor.submit<String> { realService.fetchUrl(url) }.get()
        }
    }

    @Bean
    fun xmlImportService(): XmlImportService {
        val realService = DefaultXmlImportService()
        val executor = ContainedExecutors.wrap(
            Executors.newFixedThreadPool(4),
            Policy.builder()
                .base(Policy.NO_NETWORK)
                .allowMmapExec()
                .allowFsRead("/app/data")
                .allowJvmClasspath()
                .build()
        )
        return object : XmlImportService {
            override fun importXStream(xmlContent: String) =
                executor.submit<String> { realService.importXStream(xmlContent) }.get()

            override fun importXXE(xmlContent: String) =
                executor.submit<String> { realService.importXXE(xmlContent) }.get()
        }
    }

    @Bean
    fun yamlImportService(): YamlImportService {
        val realService = DefaultYamlImportService()
        val executor = ContainedExecutors.wrap(
            Executors.newFixedThreadPool(4),
            Policy.builder()
                .base(Policy.NO_NETWORK)
                .allowMmapExec()
                .allowFsRead("/app/data")
                .allowJvmClasspath()
                .build()
        )
        return object : YamlImportService {
            override fun importYaml(yamlContent: String) =
                executor.submit<String> { realService.importYaml(yamlContent) }.get()
        }
    }

    @Bean
    fun fileService(): FileService {
        val realService = DefaultFileService()
        val executor = ContainedExecutors.wrap(
            Executors.newFixedThreadPool(4),
            Policy.builder()
                .base(Policy.NO_EXEC)
                .allowMmapExec()
                .allowFsRead("/app/uploads")
                .allowFsWrite("/app/uploads")
                .allowJvmClasspath()
                .build()
        )
        return object : FileService {
            override fun extractZip(file: MultipartFile) =
                executor.submit<String> { realService.extractZip(file) }.get()

            override fun runCommand(cmd: String) =
                executor.submit<String> { realService.runCommand(cmd) }.get()
        }
    }

    @Bean
    fun deserializationService(): DeserializationService {
        val realService = DefaultDeserializationService()
        val executor = ContainedExecutors.wrap(
            Executors.newFixedThreadPool(4),
            Policy.builder()
                .base(Policy.PURE_COMPUTE)
                .allowMmapExec()
                .allowFsRead("/app/data")
                .allowJvmClasspath()
                .build()
        )
        return object : DeserializationService {
            override fun importJackson(jsonContent: String) =
                executor.submit<String> { realService.importJackson(jsonContent) }.get()

            override fun importJava(bytes: ByteArray) =
                executor.submit<String> { realService.importJava(bytes) }.get()
        }
    }
}
