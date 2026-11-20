package io.mazewall.build;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import org.gradle.testkit.runner.GradleRunner;
import org.gradle.testkit.runner.TaskOutcome;

/** Exercises the upstream producer through the shared plugin with a shrinking classpath. */
public final class SpotBugsAuxClasspathFunctionalTest {
    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("spotbugs-aux-test-");
        Files.writeString(root.resolve("settings.gradle"), """
            rootProject.name = 'spotbugs-fixture'
            dependencyResolutionManagement { versionCatalogs { libs { version('spotbugsCore', '4.10.5') } } }
            buildCache { local { directory = file('fixture-cache') } }
            """);
        Files.writeString(root.resolve("build.gradle"), """
            plugins { id 'java'; id 'com.github.spotbugs'; id 'io.mazewall.spotbugs-toolchain' }
            repositories { mavenCentral() }
            tasks.named('spotbugsMain') {
                useAuxclasspathFile = true
                auxClassPaths.setFrom(files('short', providers.gradleProperty('large').map { 'a-much-longer-classpath-entry' }.orElse('short')))
            }
            """);
        Path patches = Path.of(args[0], "config/spotbugs/patches");
        Path destination = Files.createDirectories(root.resolve("config/spotbugs/patches"));
        try (var files = Files.list(patches)) {
            for (Path source : files.toList()) Files.copy(source, destination.resolve(source.getFileName()));
        }
        Files.createDirectories(root.resolve("short"));
        Files.createDirectories(root.resolve("a-much-longer-classpath-entry"));
        Files.writeString(root.resolve("short/short-resource.txt"), "short");
        Files.writeString(root.resolve("a-much-longer-classpath-entry/long-resource.txt"), "long");
        Path source = root.resolve("src/main/java/fixture/Example.java");
        Files.createDirectories(source.getParent());
        Files.writeString(source, "package fixture; public class Example {}\n");
        requireExecuted(run(root, "-Plarge=true"));
        Path other = root.resolve("build/spotbugs/auxclasspath/anotherTask");
        Files.writeString(other, "other task metadata");
        requireExecuted(run(root));
        Path aux = root.resolve("build/spotbugs/auxclasspath/spotbugsMain");
        String actual = Files.readString(aux);
        String expected = root.resolve("short").toString();
        if (!actual.equals(expected)) throw new AssertionError("Stale auxclasspath: " + actual);
        Files.writeString(aux, "stale-suffix", java.nio.file.StandardOpenOption.APPEND);
        Files.writeString(root.resolve("short/short-resource.txt"), "changed");
        var reused = run(root);
        requireExecuted(reused);
        if (!reused.getOutput().contains("Reusing configuration cache")) {
            throw new AssertionError("Expected configuration cache reuse: " + reused.getOutput());
        }
        actual = Files.readString(aux);
        if (!actual.equals(expected)) throw new AssertionError("Stale auxclasspath after cache reuse: " + actual);
        if (!Files.readString(other).equals("other task metadata")) throw new AssertionError("Another task's metadata changed");
        var cached = run(root);
        if (cached.task(":spotbugsMain").getOutcome() != TaskOutcome.UP_TO_DATE
                || !cached.getOutput().contains("Reusing configuration cache")) {
            throw new AssertionError("Expected task and configuration cache reuse: " + cached.getOutput());
        }
        runner(root, "clean").build();
        var restored = run(root);
        if (restored.task(":spotbugsMain").getOutcome() != TaskOutcome.FROM_CACHE) {
            throw new AssertionError("Expected analysis output cache reuse: " + restored.getOutput());
        }
        Files.createDirectories(aux);
        Files.writeString(aux.resolve("block-deletion"), "fixture");
        var failed = runner(root, "spotbugsMain", "--rerun-tasks", "--configuration-cache").buildAndFail();
        if (!failed.getOutput().contains("Cannot regenerate SpotBugs auxiliary classpath")) {
            throw new AssertionError("Expected metadata deletion failure: " + failed.getOutput());
        }
        System.out.println("SPOTBUGS_AUX_CLASSPATH_OK");
    }

    private static void requireExecuted(org.gradle.testkit.runner.BuildResult result) {
        if (result.task(":spotbugsMain").getOutcome() != TaskOutcome.SUCCESS) {
            throw new AssertionError("Expected analysis execution: " + result.getOutput());
        }
    }

    private static org.gradle.testkit.runner.BuildResult run(Path root, String... extra) {
        var args = new java.util.ArrayList<>(java.util.List.of("spotbugsMain", "--configuration-cache", "--build-cache", "--stacktrace"));
        args.addAll(Arrays.asList(extra));
        return runner(root, args.toArray(String[]::new)).build();
    }

    private static GradleRunner runner(Path root, String... args) {
        return GradleRunner.create().withProjectDir(root.toFile())
                .withPluginClasspath(Arrays.stream(System.getProperty("java.class.path").split(File.pathSeparator)).map(File::new).toList())
                .withArguments(args);
    }
}
