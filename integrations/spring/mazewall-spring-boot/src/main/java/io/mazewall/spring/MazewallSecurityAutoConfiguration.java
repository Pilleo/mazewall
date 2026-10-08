package io.mazewall.spring;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

/** Explicit optional metadata delegation only; never creates a runtime, policy, lifecycle or process baseline. */
@AutoConfiguration
@ConditionalOnClass(name = "org.springframework.security.concurrent.DelegatingSecurityContextCallable")
@ConditionalOnProperty(prefix = "mazewall.security", name = "delegate-context", havingValue = "true")
public class MazewallSecurityAutoConfiguration {
    @Bean @ConditionalOnMissingBean(InvocationContextBridge.class)
    public InvocationContextBridge mazewallSecurityContextBridge() {
        return new SpringSecurityContextBridge();
    }
}
