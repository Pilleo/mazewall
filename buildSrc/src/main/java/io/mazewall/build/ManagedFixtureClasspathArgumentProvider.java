package io.mazewall.build;

import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.InputFiles;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.process.CommandLineArgumentProvider;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;

/** Isolates the packaged fixture's ordered classpath from Gradle project state. */
public abstract class ManagedFixtureClasspathArgumentProvider implements CommandLineArgumentProvider {
    @InputFile @PathSensitive(PathSensitivity.ABSOLUTE)
    public abstract RegularFileProperty getPlatformJar();

    @InputFile @PathSensitive(PathSensitivity.ABSOLUTE)
    public abstract RegularFileProperty getEnforcerJar();

    @InputFile @PathSensitive(PathSensitivity.ABSOLUTE)
    public abstract RegularFileProperty getFixtureJar();

    @InputFiles @PathSensitive(PathSensitivity.ABSOLUTE)
    public abstract ConfigurableFileCollection getRuntimeFiles();

    @Override
    public Iterable<String> asArguments() {
        try {
            var paths = List.of(
                getPlatformJar().get().getAsFile().getCanonicalPath(),
                getEnforcerJar().get().getAsFile().getCanonicalPath(),
                dependency("kotlin-stdlib-2.4.21.jar"),
                dependency("kotlinx-serialization-core-jvm-1.9.0.jar"),
                dependency("kotlinx-serialization-json-jvm-1.9.0.jar"),
                dependency("annotations-13.0.jar"),
                getFixtureJar().get().getAsFile().getCanonicalPath()
            );
            return List.of("-Dmazewall.managed.fixture.classpath=" + String.join(File.pathSeparator, paths));
        } catch (IOException failure) {
            throw new UncheckedIOException("Cannot resolve managed fixture classpath", failure);
        }
    }

    private String dependency(String name) throws IOException {
        var matches = getRuntimeFiles().getFiles().stream().filter(file -> file.getName().equals(name)).toList();
        if (matches.size() != 1) {
            throw new IllegalStateException("Expected exactly one runtime dependency named " + name);
        }
        return matches.getFirst().getCanonicalPath();
    }
}
