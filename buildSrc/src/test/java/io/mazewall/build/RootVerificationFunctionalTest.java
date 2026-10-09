package io.mazewall.build;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.gradle.testkit.runner.BuildResult;
import org.gradle.testkit.runner.GradleRunner;
import org.gradle.testkit.runner.TaskOutcome;

/** Executes the actual root source-quality convention, including its real analysis plugins. */
final class RootVerificationFunctionalTest {
    private RootVerificationFunctionalTest() {}

    static void verify(Path rootBuild) {
        try {
            Path repository = rootBuild.getParent();
            Path fixture = Files.createTempDirectory("root-verification-");
            System.out.println("Root verification fixture: " + fixture);
            write(fixture, "settings.gradle.kts", """
                rootProject.name = "root-verification-fixture"
                include(":integrations:spring:java-leaf", ":integrations:spring:starter", ":integrations:koog", ":core", ":portals:leaf")
                """);
            write(fixture, "gradle/libs.versions.toml",
                Files.readString(repository.resolve("gradle/libs.versions.toml")));
            write(fixture, "config/spotbugs/exclude.xml", "<FindBugsFilter/>\n");
            write(fixture, "config/detekt/fixture.yml", """
                exceptions:
                  active: true
                  severity: error
                  NotImplementedDeclaration:
                    active: true
                """);
            String source = Files.readString(rootBuild);
            write(fixture, "build.gradle.kts", """
                import org.gradle.api.publish.PublishingExtension
                import java.util.jar.JarFile
                plugins {
                    java
                    alias(libs.plugins.kotlin) apply false
                    alias(libs.plugins.detekt)
                    alias(libs.plugins.spotbugs)
                }
                allprojects { repositories { mavenCentral() } }
                sourceSets { create("sharedTest") }
                """ + convention(source) + """
                // Existing cross-module build scripts query Java providers before child evaluation.
                val earlyCoreJar = project(":core").tasks.named("jar")
                subprojects {
                    plugins.withId("dev.detekt") {
                        detekt {
                            config.setFrom(rootProject.files("config/detekt/fixture.yml"))
                        }
                    }
                }
                """ + aggregate(source, "unitCheck") + aggregate(source, "kernelCheck"));
            write(fixture, "integrations/spring/java-leaf/build.gradle.kts", """
                plugins { `java-library` }
                dependencies { testImplementation("junit:junit:4.13.2") }
                tasks.register("verifyCoverageSelection") {
                    doLast {
                        val unitReport = tasks.named<org.gradle.testing.jacoco.tasks.JacocoReport>("jacocoTestReport").get()
                        val combined = tasks.named<org.gradle.testing.jacoco.tasks.JacocoReport>("jacocoCombinedReport").get()
                        val unitGate = tasks.named<org.gradle.testing.jacoco.tasks.JacocoCoverageVerification>("jacocoTestCoverageVerification").get()
                        val kernelGate = tasks.named<org.gradle.testing.jacoco.tasks.JacocoCoverageVerification>("jacocoKernelCoverageVerification").get()
                        check(unitReport.classDirectories.files.isNotEmpty())
                        check(unitReport.classDirectories.files == combined.classDirectories.files)
                        check(unitReport.classDirectories.files == unitGate.classDirectories.files)
                        check(combined.classDirectories.files == kernelGate.classDirectories.files)
                        check(unitReport.executionData.files == unitGate.executionData.files)
                        check(combined.executionData.files == kernelGate.executionData.files)
                        check(unitReport.executionData.files.none { it.name.startsWith("integrationTest") })
                    }
                }
                """);
            write(fixture, "integrations/spring/starter/build.gradle.kts", "plugins { `java-library` }\n");
            write(fixture, "integrations/spring/java-leaf/src/main/java/io/mazewall/Answer.java", """
                package io.mazewall;
                public final class Answer {
                    public static int value() { return 42; }
                }
                """);
            write(fixture, "integrations/spring/java-leaf/src/test/java/io/mazewall/AnswerTest.java", """
                package io.mazewall;
                public class AnswerTest {
                    @org.junit.Test public void answers() {
                        org.junit.Assert.assertEquals(42, Answer.value());
                    }
                }
                """);
            for (String leaf : List.of("integrations/koog", "core", "portals/leaf")) {
                write(fixture, leaf + "/build.gradle.kts", "plugins { alias(libs.plugins.kotlin) }\n");
                write(fixture, leaf + "/src/main/kotlin/io/mazewall/Answer.kt", """
                    package io.mazewall
                    object Answer {
                        fun value(): Int = 42
                    }
                    """);
            }
            write(fixture, "portals/build.gradle.kts", "plugins { base }\n");

            BuildResult selected = run(fixture, false,
                ":integrations:build", ":core:build", ":portals:build", ":unitCheck", ":kernelCheck",
                ":integrations:spring:java-leaf:verifyCoverageSelection");
            assertQuality(selected);
            Path combined = fixture.resolve("integrations/spring/java-leaf/build/reports/jacoco/jacocoCombinedReport/jacocoCombinedReport.xml");
            String combinedXml = Files.readString(combined);
            if (!combinedXml.contains("name=\"io/mazewall/Answer\"")) {
                throw new AssertionError("Custom combined report lost executable main denominator: " + combinedXml);
            }
            Path leafBuild = fixture.resolve("integrations/spring/java-leaf/build.gradle.kts");
            String passingBuild = Files.readString(leafBuild);
            Files.writeString(leafBuild, """
                tasks.named<org.gradle.testing.jacoco.tasks.JacocoCoverageVerification>("jacocoKernelCoverageVerification") {
                    violationRules { rule { limit { minimum = "0.99".toBigDecimal() } } }
                }
                """, java.nio.file.StandardOpenOption.APPEND);
            BuildResult coverageFailure = run(fixture, true,
                ":integrations:spring:java-leaf:jacocoKernelCoverageVerification");
            outcome(coverageFailure, ":integrations:spring:java-leaf:jacocoKernelCoverageVerification", TaskOutcome.FAILED);
            if (!coverageFailure.getOutput().contains("instructions covered ratio")) {
                throw new AssertionError("Expected genuine inadequate coverage rejection: " + coverageFailure.getOutput());
            }
            Files.writeString(leafBuild, passingBuild);
            // Grouping projects never acquire fake source sets, analyzers or coverage gates.
            for (String group : List.of(":integrations", ":integrations:spring", ":portals")) {
                for (String task : List.of("compileJava", "test", "spotbugsMain", "detektMain", "jacocoTestReport")) {
                    absent(selected, group + ":" + task);
                }
            }
            absent(selected, ":integrations:spring:java-leaf:detektMain");
            absent(selected, ":integrations:spring:starter:detektMain");
            outcome(selected, ":integrations:spring:starter:spotbugsMain", TaskOutcome.NO_SOURCE);
            if (selected.task(":integrations:spring:starter:check") == null) {
                throw new AssertionError("Lost source-free Java starter check");
            }

            write(fixture, "integrations/spring/java-leaf/src/main/java/io/mazewall/Broken.java", """
                package io.mazewall;
                public final class Broken {
                    public static String value() {
                        Object value = null;
                        return value.toString();
                    }
                }
                """);
            BuildResult javaFailure = run(fixture, true, ":integrations:check");
            outcome(javaFailure, ":integrations:spring:java-leaf:spotbugsMain", TaskOutcome.FAILED);
            Files.delete(fixture.resolve("integrations/spring/java-leaf/src/main/java/io/mazewall/Broken.java"));

            Path javaTest = fixture.resolve("integrations/spring/java-leaf/src/test/java/io/mazewall/AnswerTest.java");
            String passingTest = Files.readString(javaTest);
            Files.writeString(javaTest, passingTest.replace("assertEquals(42,", "assertEquals(0,"));
            BuildResult testFailure = run(fixture, true, ":integrations:build");
            outcome(testFailure, ":integrations:spring:java-leaf:test", TaskOutcome.FAILED);
            Files.writeString(javaTest, passingTest);

            write(fixture, "integrations/koog/src/main/kotlin/io/mazewall/Broken.kt", """
                package io.mazewall
                fun broken(): Int {
                    TODO("unfinished implementation")
                }
                """);
            BuildResult kotlinFailure = run(fixture, true, ":integrations:koog:detektMain");
            outcome(kotlinFailure, ":integrations:koog:detektMain", TaskOutcome.FAILED);
            System.out.println("Root verification: implicit/explicit groups, Java tests/SpotBugs, Kotlin Detekt, core and lazy aggregates passed.");
        } catch (Exception failure) {
            throw new AssertionError("Production root verification wiring failed", failure);
        }
    }

    private static void assertQuality(BuildResult result) {
        outcome(result, ":integrations:spring:java-leaf:test", TaskOutcome.SUCCESS);
        for (String leaf : List.of(":integrations:spring:java-leaf", ":integrations:koog", ":core", ":portals:leaf")) {
            outcome(result, leaf + ":spotbugsMain", TaskOutcome.SUCCESS);
            for (String task : List.of("build", "check", "unitCheck", "kernelCheck")) {
                if (result.task(leaf + ":" + task) == null) {
                    throw new AssertionError("Lost real child gate: " + leaf + ":" + task);
                }
            }
        }
        for (String leaf : List.of(":integrations:koog", ":core", ":portals:leaf")) {
            outcome(result, leaf + ":detektMain", TaskOutcome.SUCCESS);
        }
    }

    private static void outcome(BuildResult result, String task, TaskOutcome expected) {
        if (result.task(task) == null || result.task(task).getOutcome() != expected) {
            throw new AssertionError("Expected " + expected + " for " + task + "\n" + result.getOutput());
        }
    }

    private static void absent(BuildResult result, String task) {
        if (result.task(task) != null) throw new AssertionError("Fabricated source-module gate: " + task);
    }

    private static BuildResult run(Path fixture, boolean fail, String... tasks) {
        var arguments = new java.util.ArrayList<>(List.of(tasks));
        arguments.addAll(List.of("--no-configuration-cache", "--max-workers=2", "--stacktrace"));
        GradleRunner runner = GradleRunner.create().withProjectDir(fixture.toFile()).withArguments(arguments).forwardOutput();
        return fail ? runner.buildAndFail() : runner.build();
    }

    private static String convention(String source) {
        int start = source.indexOf("\nsubprojects {", source.indexOf("\ndetekt {"));
        int end = source.indexOf("\n// The root shared-test source set", start);
        if (start < 0 || end < 0) throw new AssertionError("Missing production source-quality convention");
        return source.substring(start, end);
    }

    private static String aggregate(String source, String task) {
        int start = source.lastIndexOf("\ntasks.register(\"" + task + "\") {");
        int end = source.indexOf("\n}", start);
        if (start < 0 || end < 0) throw new AssertionError("Missing production aggregate: " + task);
        return source.substring(start, end + 2) + "\n";
    }

    private static void write(Path root, String name, String content) throws Exception {
        Path target = root.resolve(name);
        Files.createDirectories(target.getParent());
        Files.writeString(target, content, StandardCharsets.UTF_8);
    }
}
