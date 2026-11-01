package io.mazewall.build;

import java.io.File;
import java.util.List;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.Internal;
import org.gradle.process.CommandLineArgumentProvider;

/** Gradle interop: preserve nested-build state without making its host/container location a test input. */
public final class TestKitCacheArguments implements CommandLineArgumentProvider {
    private final File gradleUserHome;

    public TestKitCacheArguments(File gradleUserHome) {
        this.gradleUserHome = gradleUserHome;
    }

    @Internal
    public File getGradleUserHome() { return gradleUserHome; }

    @Input
    public String getCacheLayout() { return "caches/mazewall-test-kit"; }

    @Override
    public Iterable<String> asArguments() {
        return List.of(
            "-Dorg.gradle.testkit.dir=" + new File(gradleUserHome, getCacheLayout()).getAbsolutePath(),
            "-Dmazewall.test.gradleUserHome=" + gradleUserHome.getAbsolutePath()
        );
    }
}
