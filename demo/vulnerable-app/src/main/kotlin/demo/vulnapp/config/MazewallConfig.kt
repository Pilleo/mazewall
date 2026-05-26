package demo.vulnapp.config

import io.mazewall.Policy
import io.mazewall.enforcer.ContainedExecutors
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

@Configuration
@ConditionalOnProperty("mazewall.enabled", havingValue = "true")
class MazewallConfig {

    init {
        // Ensure the paths exist on filesystem before Landlock is initialized
        File("/app/data").mkdirs()
        File("/app/uploads").mkdirs()

        // Tier 1: Process-wide baseline (blocks execve/fork process-wide but allows dynamic memory mmap for JIT compiler)
        val globalPolicy = Policy.builder()
            .base(Policy.NO_EXEC)
            .allowMmapExec()
            .build()
        ContainedExecutors.installOnProcess(globalPolicy)
    }

    @Bean
    fun loggingExecutor(): ExecutorService =
        ContainedExecutors.wrap(
            Executors.newFixedThreadPool(4),
            Policy.builder().base(Policy.NO_NETWORK).allowMmapExec().build()
        )

    @Bean
    fun proxyExecutor(): ExecutorService =
        ContainedExecutors.wrap(
            Executors.newFixedThreadPool(4),
            Policy.builder().base(Policy.NO_NETWORK).allowMmapExec().build()
        )

    @Bean
    fun importExecutor(): ExecutorService =
        ContainedExecutors.wrap(
            Executors.newFixedThreadPool(4),
            Policy.builder()
                .base(Policy.NO_NETWORK)
                .allowMmapExec()
                .allowFsRead("/app/data")
                .allowJvmClasspath()
                .build()
        )

    @Bean
    fun uploadExecutor(): ExecutorService =
        ContainedExecutors.wrap(
            Executors.newFixedThreadPool(4),
            Policy.builder()
                .base(Policy.NO_EXEC)
                .allowMmapExec()
                .allowFsRead("/app/uploads")
                .allowFsWrite("/app/uploads")
                .allowJvmClasspath()
                .build()
        )
}
