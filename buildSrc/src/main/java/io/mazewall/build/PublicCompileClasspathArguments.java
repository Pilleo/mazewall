package io.mazewall.build;

import java.util.List;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.tasks.Classpath;
import org.gradle.process.CommandLineArgumentProvider;

/** Lazy public-consumer classpath without capturing a Test task or Project in an action. */
public abstract class PublicCompileClasspathArguments implements CommandLineArgumentProvider {
    @Classpath
    public abstract ConfigurableFileCollection getClasspath();

    @Override
    public Iterable<String> asArguments() {
        return List.of("-Dmazewall.spring.publicCompileClasspath=" + getClasspath().getAsPath());
    }
}
