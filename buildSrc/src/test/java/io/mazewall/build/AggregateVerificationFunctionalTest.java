package io.mazewall.build;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.gradle.testkit.runner.BuildResult;
import org.gradle.testkit.runner.GradleRunner;
import org.gradle.testkit.runner.TaskOutcome;

/** Executes the production aggregate declarations against real grouping and child projects. */
final class AggregateVerificationFunctionalTest {
    private AggregateVerificationFunctionalTest() {}

    static void verify(Path buildScript) {
        try {
            String source = Files.readString(buildScript, StandardCharsets.UTF_8);
            Path fixture = Files.createTempDirectory("aggregate-verification-");
            write(fixture, "settings.gradle.kts", """
                rootProject.name = "aggregate-fixture"
                include(":group:leaf", ":plain", ":custom", ":demos:ignored")
                """);
            write(fixture, "build.gradle.kts",
                declaration(source, "unitCheck") + "\n" + declaration(source, "kernelCheck"));
            String javaModule = """
                plugins { java }
                tasks.register("unitCheck") {
                    doLast { println("unit gate executed") }
                }
                tasks.register("ktlintCheck") { doLast { println("style gate executed") } }
                tasks.register("detektMain") { doLast { println("analysis gate executed") } }
                tasks.register("spotbugsMain") { doLast { println("Java analysis gate executed") } }
                tasks.register("kernelCheck") {
                    doLast { println("kernel gate executed") }
                }
                """;
            write(fixture, "group/leaf/build.gradle.kts", javaModule);
            write(fixture, "plain/build.gradle.kts", javaModule);
            write(fixture, "demos/ignored/build.gradle.kts", javaModule);
            write(fixture, "custom/build.gradle.kts", """
                tasks.register("unitCheck") {
                    doLast { println("custom unit gate executed") }
                }
                """);

            BuildResult first = run(fixture);
            assertSelectedGates(first);
            BuildResult cached = run(fixture);
            assertSelectedGates(cached);
            if (!cached.getOutput().contains("Reusing configuration cache.")) {
                throw new AssertionError("Aggregate task selection must reuse the configuration cache");
            }
        } catch (Exception failure) {
            throw new AssertionError("Production aggregate verification wiring failed", failure);
        }
    }

    private static void assertSelectedGates(BuildResult result) {
        for (String selected : List.of(
            ":group:leaf:unitCheck", ":group:leaf:kernelCheck",
            ":plain:unitCheck", ":plain:kernelCheck", ":custom:unitCheck",
            ":group:leaf:ktlintCheck", ":group:leaf:detektMain", ":group:leaf:spotbugsMain",
            ":plain:ktlintCheck", ":plain:detektMain", ":plain:spotbugsMain"
        )) {
            var task = result.task(selected);
            if (task == null || task.getOutcome() != TaskOutcome.SUCCESS) {
                throw new AssertionError("Required child verification did not execute: " + selected);
            }
        }
        for (String excluded : List.of(
            ":group:unitCheck", ":group:kernelCheck", ":custom:kernelCheck",
            ":demos:ignored:unitCheck", ":demos:ignored:kernelCheck"
        )) {
            if (result.task(excluded) != null) {
                throw new AssertionError("Aggregate selected an absent or excluded gate: " + excluded);
            }
        }
    }

    private static BuildResult run(Path fixture) {
        return GradleRunner.create()
            .withProjectDir(fixture.toFile())
            // Exact paths exercise the aggregates; bare names also select child tasks independently.
            .withArguments(":unitCheck", ":kernelCheck", "--configuration-cache")
            .build();
    }

    private static String declaration(String source, String task) {
        String registration = "\ntasks.register(\"" + task + "\") {";
        int start = source.lastIndexOf(registration);
        if (start < 0) throw new AssertionError("Missing root aggregate declaration: " + task);
        int end = source.indexOf("\n}", start);
        if (end < 0) throw new AssertionError("Unterminated root aggregate declaration: " + task);
        return source.substring(start + 1, end + 2) + "\n";
    }

    private static void write(Path root, String name, String content) throws Exception {
        Path target = root.resolve(name);
        Files.createDirectories(target.getParent());
        Files.writeString(target, content, StandardCharsets.UTF_8);
    }
}
