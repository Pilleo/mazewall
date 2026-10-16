package io.mazewall.spring.diagnostics;

import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Optional observation adapters never bootstrap, install, admit or retire execution owners. */
@AutoConfiguration
@ConditionalOnProperty(prefix = "mazewall.diagnostics", name = "enabled", havingValue = "true")
public class MazewallDiagnosticsAutoConfiguration {
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = "org.springframework.boot.health.contributor.HealthIndicator")
    static class HealthConfiguration {
        @Bean(name = "mazewallHealthIndicator")
        @ConditionalOnMissingBean(name = "mazewallHealthIndicator")
        HealthIndicator health(ConfigurableListableBeanFactory beans) {
            return new MazewallDiagnosticsHealthIndicator(beans);
        }
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = "org.springframework.boot.actuate.endpoint.annotation.Endpoint")
    static class EndpointConfiguration {
        @Bean
        @ConditionalOnMissingBean(MazewallDiagnosticsEndpoint.class)
        MazewallDiagnosticsEndpoint mazewallDiagnosticsEndpoint(ConfigurableListableBeanFactory beans) {
            return new MazewallDiagnosticsEndpoint(beans);
        }
    }
}
