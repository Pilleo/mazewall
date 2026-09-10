package io.mazewall.build;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.gradle.testkit.runner.BuildResult;
import org.gradle.testkit.runner.GradleRunner;
import org.gradle.testkit.runner.TaskOutcome;

/** TestKit contract for the real test-health convention used by the root build. */
final class TestHealthPluginFunctionalTest {
    private TestHealthPluginFunctionalTest() {}

    static void verify() {
        try {
            Path root = Files.createTempDirectory("test-health-plugin-");
            Files.writeString(root.resolve("settings.gradle"), "rootProject.name = 'fixture'\n");
            Files.writeString(root.resolve("build.gradle"), """
                plugins {
                    id 'java'
                    id 'io.mazewall.test-health'
                }
                repositories { mavenCentral() }
                dependencies {
                    testImplementation 'org.junit.jupiter:junit-jupiter:5.10.2'
                    testRuntimeOnly 'org.junit.platform:junit-platform-launcher:1.10.2'
                }
                tasks.withType(Test).configureEach {
                    useJUnitPlatform()
                    forkEvery = 1
                }
                tasks.register('secondTest', Test) {
                    testClassesDirs = sourceSets.test.output.classesDirs
                    classpath = sourceSets.test.runtimeClasspath
                    useJUnitPlatform()
                }
                """, StandardCharsets.UTF_8);
            write(root, "src/test/java/fixture/FirstTest.java", testSource("FirstTest"));
            write(root, "src/test/java/fixture/SecondTest.java", testSource("SecondTest"));
            Path fragments = Files.createDirectories(root.resolve("build/reports/test-health/fragments"));
            Files.writeString(fragments.resolve("stale.json"), "{\"taskPath\":\":stale\"}");

            BuildResult first = run(root, "test", "secondTest", "aggregateTestHealth");
            equal(TaskOutcome.SUCCESS, first.task(":aggregateTestHealth").getOutcome());
            String aggregate = Files.readString(root.resolve("build/reports/test-health/test-tier-health.json"));
            equal(false, aggregate.contains(":stale"));
            equal(true, aggregate.contains("\"taskPath\":\":test\""));
            equal(true, aggregate.contains("\"taskPath\":\":secondTest\""));
            long fragmentCount;
            try (var paths = Files.list(fragments)) {
                fragmentCount = paths.filter(path -> path.toString().endsWith(".json")).count();
            }
            equal(true, fragmentCount >= 4L);

            BuildResult second = run(root, "test", "secondTest", "aggregateTestHealth");
            equal(TaskOutcome.UP_TO_DATE, second.task(":test").getOutcome());
            equal(TaskOutcome.UP_TO_DATE, second.task(":secondTest").getOutcome());
            aggregate = Files.readString(root.resolve("build/reports/test-health/test-tier-health.json"));
            equal(true, aggregate.contains("\"freshForks\": [\n    \n  ]"));
            equal(true, aggregate.contains("\":test\""));
            equal(true, aggregate.contains("\":secondTest\""));
        } catch (Exception failure) {
            throw new AssertionError("Test-health Gradle wiring contract failed", failure);
        }
    }

    private static BuildResult run(Path root, String... arguments) {
        return GradleRunner.create()
            .withProjectDir(root.toFile())
            .withPluginClasspath(pluginClasspath())
            .withArguments(arguments)
            .forwardOutput()
            .build();
    }

    private static List<java.io.File> pluginClasspath() {
        var entries = new ArrayList<java.io.File>();
        for (String entry : System.getProperty("java.class.path").split(java.io.File.pathSeparator)) {
            if (entry.contains("buildSrc/build/classes/java/main") || entry.contains("buildSrc/build/resources/main")) {
                entries.add(new java.io.File(entry));
            }
        }
        if (entries.isEmpty()) {
            try {
                entries.add(Path.of(TestHealthPlugin.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toFile());
            } catch (Exception failure) {
                throw new AssertionError("Cannot locate test-health plugin classpath", failure);
            }
        }
        return List.copyOf(entries);
    }

    private static String testSource(String name) {
        return """
            package fixture;
            import java.nio.file.*;
            import org.junit.jupiter.api.Test;
            public class %s {
                @Test void writesForkEvidence() throws Exception {
                    String directory = System.getProperty("io.mazewall.testHealth.fragmentDir");
                    String taskPath = System.getProperty("io.mazewall.testHealth.taskPath");
                    String task = System.getProperty("io.mazewall.testHealth.task");
                    Path target = Path.of(directory, task + "-" + ProcessHandle.current().pid() + "-%s.json");
                    Files.createDirectories(target.getParent());
                    Files.writeString(target, "{\\\"module\\\":\\\":\\\",\\\"task\\\":\\\"" + task
                        + "\\\",\\\"taskPath\\\":\\\"" + taskPath + "\\\",\\\"fork\\\":\\\""
                        + ProcessHandle.current().pid() + "\\\"}");
                }
            }
            """.formatted(name, name);
    }

    private static void write(Path root, String relative, String value) throws Exception {
        Path target = root.resolve(relative);
        Files.createDirectories(target.getParent());
        Files.writeString(target, value, StandardCharsets.UTF_8);
    }

    private static void equal(Object expected, Object actual) {
        if (!expected.equals(actual)) throw new AssertionError("Expected " + expected + ", got " + actual);
    }
}
