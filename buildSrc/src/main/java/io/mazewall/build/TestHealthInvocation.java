package io.mazewall.build;

import java.util.UUID;
import org.gradle.api.services.BuildService;
import org.gradle.api.services.BuildServiceParameters;

/** Per-build identity created even when Gradle reuses its configuration cache. */
public abstract class TestHealthInvocation implements BuildService<BuildServiceParameters.None> {
    private final String token = UUID.randomUUID().toString();

    final String token() {
        return token;
    }
}
