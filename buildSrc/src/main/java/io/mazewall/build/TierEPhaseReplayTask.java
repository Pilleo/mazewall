package io.mazewall.build;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import javax.inject.Inject;
import org.gradle.api.DefaultTask;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.CacheableTask;
import org.gradle.api.tasks.Classpath;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.InputFiles;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.OutputDirectory;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;
import org.gradle.process.ExecOperations;

/** Cache only deterministic replay, never CTest acquisition or kernel evidence. */
@CacheableTask
public abstract class TierEPhaseReplayTask extends DefaultTask {
    private final ExecOperations execution;

    @Inject
    public TierEPhaseReplayTask(ExecOperations execution) {
        this.execution = execution;
    }

    @Internal
    public abstract DirectoryProperty getSampleDirectory();

    @InputFiles
    @PathSensitive(PathSensitivity.NAME_ONLY)
    public abstract ConfigurableFileCollection getSamples();

    @InputFile
    @PathSensitive(PathSensitivity.NONE)
    public abstract RegularFileProperty getBaseline();

    @Classpath
    public abstract ConfigurableFileCollection getVerifierClasspath();

    @Input
    public abstract Property<String> getVerifierMainClass();

    @Input
    public abstract Property<String> getContractVersion();

    @OutputDirectory
    public abstract DirectoryProperty getReportDirectory();

    @TaskAction
    public final void replay() throws IOException {
        Path input = getSampleDirectory().get().getAsFile().toPath();
        Path report = getReportDirectory().get().getAsFile().toPath();
        if (Files.isSymbolicLink(report)) {
            throw new IllegalArgumentException("Native phase replay requires an unsymlinked output directory");
        }
        Files.createDirectories(report);
        Set<String> owned = Set.of("terminal.json", "phase-full-verdict.txt", "phase-unique-verdict.txt");
        try (var files = Files.list(report)) {
            if (files.anyMatch(file -> !owned.contains(file.getFileName().toString()))) {
                throw new IllegalArgumentException("Phase replay output contains externally owned evidence");
            }
        }
        for (String name : owned) Files.deleteIfExists(report.resolve(name));
        Files.writeString(report.resolve("terminal.json"),
            "{\"schema_version\":1,\"success\":false,\"reason\":\"INCOMPLETE\",\"policy_eligibility\":false}\n");
        if (!"2".equals(getContractVersion().get())) {
            throw new IllegalArgumentException("Only version-two native phase evidence is comparable");
        }
        if (!Files.isDirectory(input)) {
            throw new IllegalArgumentException("Native phase replay requires a real input directory");
        }
        if (getSamples().getFiles().size() != 6 || getSamples().getFiles().stream().anyMatch(file -> !file.isFile())) {
            throw new IllegalArgumentException("Native phase replay requires exactly six named CTest samples");
        }
        execution.javaexec(spec -> {
            spec.setClasspath(getVerifierClasspath());
            spec.getMainClass().set(getVerifierMainClass());
            spec.args("phase-replay-dir", input.toString(), getBaseline().get().getAsFile().toString(), report.toString());
        }).assertNormalExitValue();
        String terminal = Files.readString(report.resolve("terminal.json"));
        if (!terminal.contains("\"success\":true") || !terminal.contains("\"reason\":\"COMPLETE\"")
            || !terminal.contains("\"policy_eligibility\":false")) {
            throw new IllegalStateException("Verifier did not publish a complete policy-ineligible replay terminal");
        }
    }
}
