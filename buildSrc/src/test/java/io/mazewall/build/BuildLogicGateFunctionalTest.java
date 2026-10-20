package io.mazewall.build;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.gradle.testkit.runner.BuildResult;
import org.gradle.testkit.runner.GradleRunner;

/** Exercises copied production wiring, never the active repository build. */
public final class BuildLogicGateFunctionalTest {
    private BuildLogicGateFunctionalTest() {}

    public static void main(String[] args) {
        verify(Path.of(args[0]));
    }

    static void verify(Path repository) {
        try {
            verifyBootstrap(repository);
            String root = Files.readString(repository.resolve("build.gradle.kts"));
            String begin = "// BEGIN explicit build-logic gate";
            String end = "// END explicit build-logic gate";
            require(root.contains(begin) && root.contains(end), "Missing production explicit build-logic gate");
            String gate = root.substring(root.indexOf(begin), root.indexOf(end) + end.length());
            verifyGate(gate);
            System.out.println("BUILD_LOGIC_GATE_OK bootstrap isolation, lifecycle failure, strict cache reuse, input invalidation and direct ordering edges; serial execution only");
        } catch (Exception failure) {
            throw new AssertionError("Explicit build-logic gate contract failed", failure);
        }
    }

    private static void verifyBootstrap(Path repository) throws Exception {
        Path fixture = fixture("build-logic-bootstrap-");
        System.out.println("Build-logic bootstrap fixture: " + fixture);
        for (String file : List.of("buildSrc/build.gradle.kts", "buildSrc/settings.gradle.kts", "gradle/libs.versions.toml")) {
            write(fixture, file, Files.readString(repository.resolve(file)));
        }
        for (String task : List.of("jar", "test", "check")) {
            BuildResult result = run(fixture.resolve("buildSrc"), false, task, "--dry-run");
            require(result.getOutput().contains(":verificationActionsTest SKIPPED") != task.equals("jar"),
                task + " verifier selection: " + result.getOutput());
        }
    }

    private static void verifyGate(String gate) throws Exception {
        Path fixture = fixture("build-logic-gate-");
        System.out.println("Build-logic gate fixture: " + fixture);
        write(fixture, "settings.gradle.kts", "rootProject.name = \"gate-fixture\"\ninclude(\":child\")\n");
        writeRoot(fixture, gate);
        write(fixture, "child/build.gradle", """
            plugins { id 'java' }
            def marker = rootProject.layout.projectDirectory.file('buildSrc/build/verification/success').asFile
            tasks.register('heavy') {
                inputs.file(marker)
                doLast {
                    assert marker.text == 'SUCCESS\\n'
                    println('HEAVY_AFTER_VERIFICATION')
                }
            }
            // Same name in a child must not be excluded from ordering by name alone.
            tasks.register('buildLogicCheck') { dependsOn('heavy') }
            tasks.named('check') { dependsOn('buildLogicCheck') }
            """);
        write(fixture, "buildSrc/settings.gradle", "rootProject.name = 'gate-build-logic'\n");
        write(fixture, "buildSrc/verification-input.txt", "PASS\n");
        write(fixture, "buildSrc/build.gradle", """
            plugins { id 'java' }
            def input = layout.projectDirectory.file('verification-input.txt').asFile
            def marker = layout.buildDirectory.file('verification/success').get().asFile
            def count = layout.buildDirectory.file('verification/count').get().asFile
            tasks.register('verificationActionsTest') {
                inputs.file(input).withPathSensitivity(PathSensitivity.RELATIVE)
                inputs.property('workers', gradle.startParameter.maxWorkerCount)
                inputs.property('parallel', gradle.startParameter.parallelProjectExecutionEnabled)
                outputs.file(marker)
                localState.register(count)
                doLast {
                    marker.delete()
                    assert inputs.properties.workers == 1
                    assert !inputs.properties.parallel
                    count.parentFile.mkdirs()
                    count.text = ((count.exists() ? count.text.trim().toInteger() : 0) + 1).toString()
                    if (input.text.trim() == 'FAIL') throw new GradleException('deliberate verification failure')
                    marker.text = 'SUCCESS\\n'
                }
            }
            tasks.named('test') { dependsOn('verificationActionsTest') }
            tasks.named('check') { dependsOn('verificationActionsTest') }
            """);
        Path marker = fixture.resolve("buildSrc/build/verification/success");
        Path count = fixture.resolve("buildSrc/build/verification/count");
        for (String task : List.of("help", "assemble")) {
            BuildResult fast = run(fixture, false, task);
            require(fast.task(":buildLogicCheck") == null, "Fast path selected gate: " + fast.getOutput());
            require(!Files.exists(count), "Fast path ran verifier");
        }
        BuildResult ordering = run(fixture, false, "inspectGateOrdering");
        assertDirectAnchors(ordering);
        require(ordering.task(":buildLogicCheck") == null && !Files.exists(count), "Ordering inspection selected verifier");
        BuildResult orderingReused = run(fixture, false, "inspectGateOrdering");
        assertDirectAnchors(orderingReused);
        require(orderingReused.getOutput().contains("Reusing configuration cache."), orderingReused.getOutput());
        BuildResult first = run(fixture, false, "check", ":child:check", "--parallel");
        require(first.getOutput().contains("Configuration cache entry stored"), first.getOutput());
        require(first.getOutput().contains("HEAVY_AFTER_VERIFICATION"), first.getOutput());
        require(Files.readString(count).equals("1"), "Verifier must run once");
        BuildResult reused = run(fixture, false, "check", ":child:check", "--parallel");
        require(reused.getOutput().contains("Reusing configuration cache."), reused.getOutput());
        require(Files.readString(count).equals("1"), "Unchanged verifier should be up-to-date");
        Files.writeString(fixture.resolve("buildSrc/verification-input.txt"), "FAIL\n");
        BuildResult failed = run(fixture, true, "check", ":child:check", "--parallel");
        require(failed.getOutput().contains("Reusing configuration cache."), failed.getOutput());
        require(failed.getOutput().contains("deliberate verification failure"), failed.getOutput());
        require(!Files.exists(marker) && Files.readString(count).equals("2"), "Failure retained old success");
        BuildResult build = run(fixture, true, "build");
        require(build.getOutput().contains("deliberate verification failure"), build.getOutput());
        require(!Files.exists(marker) && Files.readString(count).equals("3"), "Build bypassed failed verifier");
        Files.writeString(fixture.resolve("buildSrc/verification-input.txt"), "PASS\n");
        run(fixture, false, "buildLogicCheck");
        require(Files.exists(marker) && Files.readString(count).equals("4"), "Explicit gate did not recover");

        String omittedHeavy = gate.replace("path != \":buildLogicCheck\"", "path != \":buildLogicCheck\" && path != \":child:heavy\"");
        rejectOrderingControl(fixture, gate, omittedHeavy, "OMITTED_HEAVY");
        String nameExcluded = gate.replace("path != \":buildLogicCheck\"", "name != \"buildLogicCheck\"");
        rejectOrderingControl(fixture, gate, nameExcluded, "SAME_NAME");
    }

    private static void writeRoot(Path fixture, String gate) throws Exception {
        write(fixture, "build.gradle.kts", "plugins { java }\n" + gate + """

            tasks.register("inspectGateOrdering") {
                val targets = listOf(tasks.named("assemble").get(), project(":child").tasks.named("heavy").get(), project(":child").tasks.named("buildLogicCheck").get())
                targets.forEach { target ->
                    inputs.property(target.path, target.mustRunAfter.getDependencies(target).map { it.path }.sorted())
                }
                doLast {
                    inputs.properties.toSortedMap().forEach { (target, anchors) -> println("DIRECT_ORDERING=$target=$anchors") }
                }
            }
            """);
    }

    private static void assertDirectAnchors(BuildResult result) {
        for (String target : List.of(":assemble", ":child:heavy", ":child:buildLogicCheck")) {
            String expected = "DIRECT_ORDERING=" + target + "=[:buildLogicCheck]";
            require(result.getOutput().lines().anyMatch(expected::equals), "Missing direct gate anchor for " + target + ": " + result.getOutput());
            System.out.println(expected);
        }
    }

    private static void rejectOrderingControl(Path fixture, String gate, String wrongGate, String name) throws Exception {
        require(!wrongGate.equals(gate), "Negative control did not alter the fixture exclusion");
        writeRoot(fixture, wrongGate);
        BuildResult wrongOrdering = run(fixture, false, "inspectGateOrdering");
        try {
            assertDirectAnchors(wrongOrdering);
        } catch (AssertionError expected) {
            System.out.println(name + "_NEGATIVE_CONTROL_REJECTED missing direct root gate anchor");
            return;
        }
        throw new AssertionError("Direct anchor assertion accepted " + name);
    }

    private static BuildResult run(Path fixture, boolean failure, String... tasks) {
        var arguments = new ArrayList<>(List.of(tasks));
        arguments.addAll(List.of("--console=plain", "--max-workers=1", "--configuration-cache", "--configuration-cache-problems=fail"));
        GradleRunner runner = GradleRunner.create().withProjectDir(fixture.toFile()).withArguments(arguments);
        return failure ? runner.buildAndFail() : runner.build();
    }

    private static void write(Path root, String file, String content) throws Exception {
        Path target = root.resolve(file);
        Files.createDirectories(target.getParent());
        Files.writeString(target, content);
    }

    private static Path fixture(String prefix) throws Exception {
        Path scratch = Path.of(System.getProperty("java.io.tmpdir"), "opencode");
        Files.createDirectories(scratch);
        return Files.createTempDirectory(scratch, prefix);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
