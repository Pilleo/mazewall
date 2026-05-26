package demo.vulnapp.config

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

@Configuration
@ConditionalOnProperty("mazewall.enabled", havingValue = "false", matchIfMissing = true)
class MazewallDisabledConfig {

    init {
        // Still ensure the paths exist so functional tests work the same way
        File("/app/data").mkdirs()
        File("/app/uploads").mkdirs()
    }

    @Bean
    fun loggingExecutor(): ExecutorService = Executors.newFixedThreadPool(4)

    @Bean
    fun proxyExecutor(): ExecutorService = Executors.newFixedThreadPool(4)

    @Bean
    fun importExecutor(): ExecutorService = Executors.newFixedThreadPool(4)

    @Bean
    fun uploadExecutor(): ExecutorService = Executors.newFixedThreadPool(4)
}
