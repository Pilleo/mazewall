package io.mazewall.build;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.gradle.testkit.runner.BuildResult;
import org.gradle.testkit.runner.GradleRunner;
import org.gradle.testkit.runner.TaskOutcome;

/** Real TestKit cache contract across two independent Gradle project roots. */
final class TierEPhaseReplayTaskFunctionalTest {
    private TierEPhaseReplayTaskFunctionalTest() {}

    static void verify() {
        try {
            Path cache = Files.createTempDirectory("tier-e-phase-test-build-cache-");
            Path first = Files.createTempDirectory("tier-e-replay-first-");
            Path second = Files.createTempDirectory("tier-e-replay-second-");
            fixture(first, cache);
            fixture(second, cache);
            outcome(TaskOutcome.SUCCESS, run(first).task(":phaseReplay").getOutcome());
            outcome(TaskOutcome.UP_TO_DATE, run(first).task(":phaseReplay").getOutcome());
            outcome(TaskOutcome.FROM_CACHE, run(second).task(":phaseReplay").getOutcome());
            Files.writeString(second.resolve("samples/unique-1.log"), "broken\n");
            GradleRunner.create().withProjectDir(second.toFile()).withPluginClasspath(pluginClasspath())
                .withArguments("phaseReplay", "--build-cache").buildAndFail();
            if (Files.readString(second.resolve("build/phase-report/terminal.json")).contains("\"success\":true")) {
                throw new AssertionError("Invalid phase input retained cached COMPLETE receipt");
            }
            Files.writeString(second.resolve("samples/unique-1.log"), "unique-1\n");
            outcome(TaskOutcome.FROM_CACHE, run(second).task(":phaseReplay").getOutcome());
            Files.writeString(second.resolve("build.gradle"),
                Files.readString(second.resolve("build.gradle")).replace("contractVersion.set('2')", "contractVersion.set('3')"));
            GradleRunner.create().withProjectDir(second.toFile()).withPluginClasspath(pluginClasspath())
                .withArguments("phaseReplay", "--build-cache").buildAndFail();
            if (Files.readString(second.resolve("build/phase-report/terminal.json")).contains("\"success\":true")) {
                throw new AssertionError("Changed phase contract retained cached COMPLETE receipt");
            }
            Files.writeString(second.resolve("build.gradle"),
                Files.readString(second.resolve("build.gradle")).replace("contractVersion.set('3')", "contractVersion.set('2')"));
            outcome(TaskOutcome.FROM_CACHE, run(second).task(":phaseReplay").getOutcome());
            Files.delete(second.resolve("samples/full-2.log"));
            GradleRunner.create().withProjectDir(second.toFile()).withPluginClasspath(pluginClasspath())
                .withArguments("phaseReplay", "--build-cache").buildAndFail();
            if (Files.readString(second.resolve("build/phase-report/terminal.json")).contains("\"success\":true")) {
                throw new AssertionError("Missing phase input retained cached COMPLETE receipt");
            }
            Files.writeString(second.resolve("samples/full-2.log"), "full-2\n");
            outcome(TaskOutcome.FROM_CACHE, run(second).task(":phaseReplay").getOutcome());
            Files.writeString(second.resolve("baseline.env"), "different historical bound\n");
            outcome(TaskOutcome.SUCCESS, run(second).task(":phaseReplay").getOutcome());
            Files.writeString(second.resolve("baseline.env"), "unchanged\n");
            outcome(TaskOutcome.FROM_CACHE, run(second).task(":phaseReplay").getOutcome());
            Files.writeString(second.resolve("samples/full-1.log"), "unique-1\n");
            Files.writeString(second.resolve("samples/unique-1.log"), "full-1\n");
            outcome(TaskOutcome.SUCCESS, run(second).task(":phaseReplay").getOutcome());
            Files.writeString(second.resolve("samples/full-1.log"), "full-1\n");
            Files.writeString(second.resolve("samples/unique-1.log"), "unique-1\n");
            outcome(TaskOutcome.FROM_CACHE, run(second).task(":phaseReplay").getOutcome());
            Path verifier = second.resolve("src/main/java/fixture/Replay.java");
            Files.writeString(verifier, Files.readString(verifier).replace("contains(\"broken\")", "contains(\"broken-v2\")"));
            outcome(TaskOutcome.SUCCESS, run(second).task(":phaseReplay").getOutcome());
        } catch (Exception error) {
            throw new AssertionError("Portable Tier E phase replay Gradle cache contract failed", error);
        }
    }

    private static void fixture(Path root, Path cache) throws Exception {
        Files.writeString(root.resolve("settings.gradle"), "rootProject.name='tier-e-replay-fixture'\n"
            + "buildCache { local { directory = file('" + cache + "') } }\n");
        Files.writeString(root.resolve("build.gradle"), """
            import io.mazewall.build.TierEPhaseReplayTask
            plugins { id 'java'; id 'io.mazewall.test-health' }
            tasks.register('phaseReplay', TierEPhaseReplayTask) {
                sampleDirectory.fileValue(file('samples'))
                for (mode in ['full', 'unique']) {
                    for (index in 1..3) samples.from(file("samples/${mode}-${index}.log"))
                }
                baseline.fileValue(file('baseline.env'))
                verifierClasspath.from(sourceSets.main.runtimeClasspath)
                dependsOn classes
                verifierMainClass.set('fixture.Replay')
                contractVersion.set('2')
                reportDirectory.fileValue(file('build/phase-report'))
            }
            """);
        Files.writeString(root.resolve("baseline.env"), "unchanged\n");
        Files.createDirectories(root.resolve("samples"));
        for (String mode : List.of("full", "unique")) {
            for (int sample = 1; sample <= 3; sample++) {
                Files.writeString(root.resolve("samples/" + mode + "-" + sample + ".log"), mode + "-" + sample + "\n");
            }
        }
        Path java = root.resolve("src/main/java/fixture/Replay.java");
        Files.createDirectories(java.getParent());
        Files.writeString(java, """
            package fixture;
            import java.nio.file.*;
            public class Replay {
                public static void main(String[] args) throws Exception {
                    Path samples = Path.of(args[1]);
                    Path output = Path.of(args[3]);
                    for (String mode : new String[]{"full", "unique"}) {
                        for (int i = 1; i <= 3; i++) {
                            if (Files.readString(samples.resolve(mode + "-" + i + ".log")).contains("broken"))
                                throw new IllegalArgumentException("broken phase input");
                        }
                    }
                    Files.createDirectories(output);
                    Files.writeString(output.resolve("terminal.json"), "{\\"success\\":true,\\"reason\\":\\"COMPLETE\\",\\"policy_eligibility\\":false}");
                }
            }
            """);
    }

    private static BuildResult run(Path root) {
        return GradleRunner.create().withProjectDir(root.toFile()).withPluginClasspath(pluginClasspath())
            .withArguments("phaseReplay", "--build-cache").build();
    }

    private static List<java.io.File> pluginClasspath() {
        var entries = new ArrayList<java.io.File>();
        for (String item : System.getProperty("java.class.path").split(java.io.File.pathSeparator)) {
            if (item.contains("buildSrc/build/classes/java/main") || item.contains("buildSrc/build/resources/main")) {
                entries.add(new java.io.File(item));
            }
        }
        if (entries.isEmpty()) {
            entries.add(Path.of(TierEPhaseReplayTaskFunctionalTest.class.getProtectionDomain().getCodeSource().getLocation().getPath()).toFile());
        }
        return List.copyOf(entries);
    }

    private static void outcome(TaskOutcome expected, TaskOutcome actual) {
        if (expected != actual) throw new AssertionError("Expected " + expected + ", got " + actual);
    }
}
