package io.mazewall.build;

import java.util.List;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.OutputFile;
import org.gradle.process.CommandLineArgumentProvider;

/** Managed arguments shared by production triage and its Java-only TestKit fixture. */
public abstract class TriageArguments implements CommandLineArgumentProvider {
    @Input
    public abstract Property<String> getFailedTaskPath();

    @OutputFile
    public abstract RegularFileProperty getReportFile();

    @Override
    public Iterable<String> asArguments() {
        return List.of("--failed-task", getFailedTaskPath().get(),
            "--output", getReportFile().get().getAsFile().getAbsolutePath());
    }
}
