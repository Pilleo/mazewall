package io.mazewall.build;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.stream.Collectors;
import org.gradle.testkit.runner.GradleRunner;
import org.gradle.testkit.runner.TaskOutcome;

/** Applies the production JavaExec registration without recursively building the repository. */
public final class VerificationWorkflowInputFunctionalTest {
    private VerificationWorkflowInputFunctionalTest() {}

    public static void main(String[] args) throws Exception {
        CiCachePolicyTest.run();
        Path marker = Path.of(args[0]);
        Files.createDirectories(marker.getParent());
        Files.writeString(marker, "verified\n");
    }

    static void verify(Path repository) {
        try {
            Path fixture = Files.createTempDirectory(
                Files.isDirectory(Path.of("/tmp/opencode")) ? Path.of("/tmp/opencode") : Path.of(System.getProperty("java.io.tmpdir")),
                "verification-workflow-input-");
            Path project = Files.createDirectory(fixture.resolve("buildSrc"));
            Files.copy(repository.resolve("buildSrc/settings.gradle.kts"), project.resolve("settings.gradle.kts"));
            Files.createDirectories(fixture.resolve("gradle"));
            Files.copy(repository.resolve("gradle/libs.versions.toml"), fixture.resolve("gradle/libs.versions.toml"));
            String production = Files.readString(repository.resolve("buildSrc/build.gradle.kts"));
            int start = production.indexOf("val verificationActionsTest = tasks.register<JavaExec>");
            int end = production.indexOf("\ntasks.withType<JavaExec>()", start);
            if (start < 0 || end < 0) throw new AssertionError("Cannot locate production verifier registration");
            String classpath = Arrays.stream(System.getProperty("java.class.path").split(File.pathSeparator))
                .map(entry -> "uri(\"" + Path.of(entry).toAbsolutePath().toUri() + "\")")
                .collect(Collectors.joining(", "));
            Files.writeString(project.resolve("build.gradle.kts"), """
                val verificationActionsMarker = layout.buildDirectory.file("verification/verification-actions.success")
                """ + production.substring(start, end) + """

                tasks.named<JavaExec>("verificationActionsTest") {
                    mainClass.set("io.mazewall.build.VerificationWorkflowInputFunctionalTest")
                    classpath = files(%s)
                }
                """.formatted(classpath));
            Path workflow = fixture.resolve(".github/workflows/ci.yml");
            Files.createDirectories(workflow.getParent());
            String valid = Files.readString(repository.resolve(".github/workflows/ci.yml"));
            Files.writeString(workflow, valid);
            Path helper = fixture.resolve("scripts/ci_cache_key.sh");
            Files.createDirectories(helper.getParent());
            Files.copy(repository.resolve("scripts/ci_cache_key.sh"), helper);
            Path marker = project.resolve("build/verification/verification-actions.success");
            outcome(TaskOutcome.SUCCESS, runner(project).build().task(":verificationActionsTest").getOutcome());
            if (!Files.isRegularFile(marker)) throw new AssertionError("Prime verifier did not publish its marker");
            outcome(TaskOutcome.UP_TO_DATE, runner(project).build().task(":verificationActionsTest").getOutcome());
            Files.writeString(workflow, valid + "\n# Workflow-only input invalidation control\n");
            outcome(TaskOutcome.SUCCESS, runner(project).build().task(":verificationActionsTest").getOutcome());
            Files.writeString(workflow, valid.replace("timeout-minutes: 180", "timeout-minutes: 90"));
            var failed = runner(project).buildAndFail();
            outcome(TaskOutcome.FAILED, failed.task(":verificationActionsTest").getOutcome());
            if (!failed.getOutput().contains("Actual workflow must satisfy the completed-data policy") || Files.exists(marker)) {
                throw new AssertionError("Invalid actual workflow must fail policy checks and remove the old success marker: " + failed.getOutput());
            }
            System.out.println("Actual verifier DSL: prime SUCCESS, unchanged UP-TO-DATE, workflow-only edit SUCCESS, invalid workflow FAILED without stale marker: " + fixture);
        } catch (Exception failure) {
            throw new AssertionError("Actual verification workflow-input contract failed", failure);
        }
    }

    private static GradleRunner runner(Path project) {
        return GradleRunner.create().withProjectDir(project.toFile())
            .withArguments("verificationActionsTest", "--offline", "--no-configuration-cache", "--max-workers=1");
    }

    private static void outcome(TaskOutcome expected, TaskOutcome actual) {
        if (expected != actual) throw new AssertionError("Expected verifier " + expected + ", got " + actual);
    }
}
