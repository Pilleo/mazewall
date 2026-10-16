package demo.vulnapp.config

import demo.vulnapp.service.*
import io.mazewall.enforcer.api.Policy
import io.mazewall.enforcer.api.RuntimeProfile
import io.mazewall.enforcer.api.WorkerPolicyBuilder
import io.mazewall.profiler.api.Profiler
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Primary
import org.springframework.web.multipart.MultipartFile
import java.util.concurrent.Executors

/** Packaged profiling configuration; unlike a test fixture this is available to compose runs. */
@Configuration
@ConditionalOnProperty(name = ["mazewall.profile"], havingValue = "true")
internal class MazewallProfileConfig(
    private val profileManager: MazewallProfileManager,
    private val resources: ServiceResourceBindings,
) {
    private fun profiler(): ProfiledServiceExecutor {
        val raw = Executors.newFixedThreadPool(4)
        return profileManager.register(raw) {
            Profiler.wrap(
                raw,
                WorkerPolicyBuilder
                    .denyList(RuntimeProfile.HOTSPOT_JIT)
                    .base(Policy.PURE_COMPUTE_UNSAFE)
                    .allowMmapExec()
                    .build(),
            )
        }
    }

    @Bean @Primary
    fun adminServiceProfiling(): AdminService =
        DefaultAdminService().let { real ->
        val executor = profiler()
        object : AdminService {
            override fun logMessage(apiVersion: String) = executor.call { real.logMessage(apiVersion) }
        }
    }

    @Bean @Primary
    fun proxyServiceProfiling(): ProxyService =
        DefaultProxyService().let { real ->
        val executor = profiler()
        object : ProxyService {
            override fun fetchUrl(url: String) = executor.call { real.fetchUrl(url) }
        }
    }

    @Bean @Primary
    fun xmlImportServiceProfiling(): XmlImportService =
        DefaultXmlImportService().let { real ->
        val executor = profiler()
        object : XmlImportService {
            override fun importXStream(xmlContent: String) = executor.call { real.importXStream(xmlContent) }

            override fun importXXE(xmlContent: String) = executor.call { real.importXXE(xmlContent) }
        }
    }

    @Bean @Primary
    fun yamlImportServiceProfiling(): YamlImportService =
        DefaultYamlImportService().let { real ->
        val executor = profiler()
        object : YamlImportService {
            override fun importYaml(yamlContent: String) = executor.call { real.importYaml(yamlContent) }
        }
    }

    @Bean @Primary
    fun fileServiceProfiling(): FileService =
        DefaultFileService(resources.uploads.toFile()).let { real ->
        val executor = profiler()
        object : FileService {
            override fun extractZip(file: MultipartFile) = executor.call { real.extractZip(file) }

            override fun runCommand(cmd: String) = executor.call { real.runCommand(cmd) }
        }
    }

    @Bean @Primary
    fun deserializationServiceProfiling(): DeserializationService =
        DefaultDeserializationService().let { real ->
        val executor = profiler()
        object : DeserializationService {
            override fun importJackson(jsonContent: String) = executor.call { real.importJackson(jsonContent) }

            override fun importJava(bytes: ByteArray) = executor.call { real.importJava(bytes) }
        }
    }
}
