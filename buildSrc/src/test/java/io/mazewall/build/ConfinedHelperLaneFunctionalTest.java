package io.mazewall.build;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.gradle.testkit.runner.GradleRunner;

/** Exercises production lane routing with real JUnit discovery in a small fixture. */
final class ConfinedHelperLaneFunctionalTest {
    private static final List<String> CONFINED = List.of(
        "io.mazewall.enforcer.api.execution.ConfinedHelperReadinessIntegrationTest",
        "io.mazewall.ffi.networking.ConfinedHelperProcessIntegrationTest",
        "io.mazewall.landlock.OwnedProcFdReopenKernelTest");

    private ConfinedHelperLaneFunctionalTest() {}

    public static void main(String[] args) throws Exception {
        verify(Path.of(args[0]));
    }

    static void verify(Path repository) throws Exception {
        String source = Files.readString(repository.resolve("enforcer/build.gradle.kts"));
        String startMarker = "// BEGIN confined helper CI routing";
        String endMarker = "// END confined helper CI routing";
        int start = source.indexOf(startMarker);
        int end = source.indexOf(endMarker);
        if (start < 0 || end <= start || source.indexOf(startMarker, start + 1) >= 0 || source.indexOf(endMarker, end + 1) >= 0) {
            throw new AssertionError("Missing or ambiguous production lane routing block");
        }
        Path fixture = Files.createTempDirectory("confined-lane-selection-");
        Files.writeString(fixture.resolve("settings.gradle.kts"), "rootProject.name = \"confined-lane-selection\"\n");
        Files.createDirectories(fixture.resolve("gradle"));
        Files.copy(repository.resolve("gradle/libs.versions.toml"), fixture.resolve("gradle/libs.versions.toml"));
        Files.copy(repository.resolve("gradle/verification-metadata.xml"), fixture.resolve("gradle/verification-metadata.xml"));
        Path testKit = Files.createDirectories(Path.of(System.getProperty("org.gradle.testkit.dir")).resolve("confined-helper-lane"));
        String actionClasses = MapFilesAccessCheckAction.class.getProtectionDomain().getCodeSource().getLocation().toURI().toASCIIString();
        Files.writeString(fixture.resolve("build.gradle.kts"), """
            buildscript { dependencies { classpath(files(uri("%s"))) } }
            plugins { java }
            repositories { mavenCentral() }
            dependencies {
                testImplementation(platform(libs.junit.bom))
                testImplementation(libs.junit.jupiter.api)
                testRuntimeOnly(libs.junit.jupiter.engine)
                testRuntimeOnly(libs.junit.platform.launcher)
            }
            tasks.register("prepareLaneDependencies") {
                doLast {
                    configurations.testCompileClasspath.get().resolve()
                    configurations.testRuntimeClasspath.get().resolve()
                }
            }
            """.formatted(actionClasses) + source.substring(start, end + endMarker.length()) + "\n" + """
            tasks.register<Test>("integrationTest") {
                testClassesDirs = sourceSets["test"].output.classesDirs
                classpath = sourceSets["test"].runtimeClasspath
                configureConfinedHelperLane()
                useJUnitPlatform { excludeTags("needs-fresh-jvm") }
            }
            tasks.register<Test>("integrationTestFreshJvm") {
                testClassesDirs = sourceSets["test"].output.classesDirs
                classpath = sourceSets["test"].runtimeClasspath
                useJUnitPlatform { includeTags("needs-fresh-jvm") }
            }
            tasks.register<Test>("configurationCacheAllLaneProbe") {
                rejectUnopenableAllLane()
            }
            """);
        for (String name : CONFINED) writeTest(fixture, name, false);
        writeTest(fixture, "fixture.OrdinaryTest", false);
        writeTest(fixture, "fixture.FreshTest", true);
        String offline = System.getProperty("mazewall.test.outerOffline");
        if (!"true".equals(offline) && !"false".equals(offline)) throw new AssertionError("Missing or invalid outer Gradle offline mode");
        var preparation = new java.util.ArrayList<>(List.of("prepareLaneDependencies", "--no-configuration-cache", "--max-workers=1"));
        if (Boolean.parseBoolean(offline)) preparation.add("--offline");
        GradleRunner.create().withProjectDir(fixture.toFile()).withTestKitDir(testKit.toFile()).withArguments(preparation).build();
        assertAllLaneConfigurationCache(fixture, testKit);
        assertRun(fixture, testKit, null, List.of("fixture.OrdinaryTest", CONFINED.get(0), CONFINED.get(1), CONFINED.get(2)), false);
        assertRun(fixture, testKit, "ALL", List.of("fixture.OrdinaryTest", CONFINED.get(0), CONFINED.get(1), CONFINED.get(2)), false);
        assertRun(fixture, testKit, "BASELINE", List.of("fixture.OrdinaryTest"), false);
        assertRun(fixture, testKit, "CONFINED", CONFINED, false);
        assertRun(fixture, testKit, "INVALID", List.of(), true);
    }

    private static void assertAllLaneConfigurationCache(Path fixture, Path testKit) {
        var arguments = List.of("configurationCacheAllLaneProbe", "--dry-run", "--offline", "--configuration-cache",
            "--configuration-cache-problems=fail", "--max-workers=1", "-PmazewallKernelLane=ALL");
        var runner = GradleRunner.create().withProjectDir(fixture.toFile()).withTestKitDir(testKit.toFile()).withArguments(arguments);
        var stored = runner.build().getOutput();
        if (!stored.contains("Configuration cache entry stored.")) throw new AssertionError(stored);
        var reused = runner.build().getOutput();
        if (!reused.contains("Configuration cache entry reused.")) throw new AssertionError(reused);
    }

    private static void assertRun(Path fixture, Path testKit, String mode, List<String> expected, boolean fail) throws Exception {
        var args = new java.util.ArrayList<>(List.of("integrationTest", "integrationTestFreshJvm", "--offline", "--rerun-tasks", "--no-configuration-cache", "--max-workers=1"));
        if (mode != null) args.add("-PmazewallKernelLane=" + mode);
        var runner = GradleRunner.create().withProjectDir(fixture.toFile()).withTestKitDir(testKit.toFile()).withArguments(args);
        var result = fail ? runner.buildAndFail() : runner.build();
        if (fail) {
            if (!result.getOutput().contains("mazewallKernelLane")) throw new AssertionError("Invalid mode failed for another reason: " + result.getOutput());
            return;
        }
        Path ordinary = fixture.resolve("build/test-results/integrationTest");
        try (var files = Files.list(ordinary)) {
            List<String> actual = files.map(path -> path.getFileName().toString()).filter(name -> name.startsWith("TEST-") && name.endsWith(".xml"))
                .map(name -> name.substring(5, name.length() - 4)).sorted().toList();
            if (!actual.equals(expected.stream().sorted().toList())) throw new AssertionError("Wrong " + mode + " selection: " + actual);
        }
        if (!Files.isRegularFile(fixture.resolve("build/test-results/integrationTestFreshJvm/TEST-fixture.FreshTest.xml"))) {
            throw new AssertionError("Fresh JVM partition was lost for " + mode);
        }
    }

    private static void writeTest(Path fixture, String name, boolean fresh) throws Exception {
        int dot = name.lastIndexOf('.');
        Path file = fixture.resolve("src/test/java/" + name.replace('.', '/') + ".java");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "package " + name.substring(0, dot) + ";\n" + (fresh ? "@org.junit.jupiter.api.Tag(\"needs-fresh-jvm\")\n" : "")
            + "public class " + name.substring(dot + 1) + " { @org.junit.jupiter.api.Test void runs() {} }\n");
    }
}
