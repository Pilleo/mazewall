package io.mazewall.build;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.gradle.testkit.runner.BuildResult;
import org.gradle.testkit.runner.GradleRunner;
import org.gradle.testkit.runner.TaskOutcome;

/** Exercises the shared selector gate before the real PIT task's process boundary. */
public final class PitestTargetInventoryFunctionalTest {
    private PitestTargetInventoryFunctionalTest() {}

    public static void main(String[] args) throws Exception {
        verify(Path.of(args[0]));
    }

    static void verify(Path repository) throws Exception {
        Path fixture = Files.createTempDirectory("pit-target-inventory-");
        System.out.println("PIT inventory fixture: " + fixture);
        write(fixture, "settings.gradle.kts", "rootProject.name = \"pit-inventory-fixture\"\n");
        write(fixture, "gradle/libs.versions.toml", Files.readString(repository.resolve("gradle/libs.versions.toml")));
        write(fixture, "gradle/pitest-limits.gradle", Files.readString(repository.resolve("gradle/pitest-limits.gradle")));
        write(fixture, "src/main/java/fixture/Production.java", "package fixture; public class Production { public int value() { return 1; } }\n");
        write(fixture, "src/main/java/fixture/MainOnly.java", "package fixture; public class MainOnly {}\n");
        write(fixture, "src/test/java/fixture/ProductionTest.java", "package fixture; public class ProductionTest {}\n");
        configure(fixture, "\"fixture.Production*\", \"fixture.Stale*\"", "\"fixture.ProductionTest\"", "", "", "");
        rejected(fixture, "mixed-stale-production", "targetClasses", "fixture.Stale*");

        configure(fixture, "\"fixture.Production*\"", "\"fixture.ProductionTest\", \"fixture.MissingTest\"", "", "", "");
        rejected(fixture, "mixed-stale-test", "targetTests", "fixture.MissingTest");
        configure(fixture, "\"fixture.Production*\"", "\"fixture.MainOnly\"", "", "", "");
        rejected(fixture, "main-is-not-test", "targetTests", "fixture.MainOnly");
        configure(fixture, "\"fixture.ProductionTest\"", "\"fixture.ProductionTest\"", "", "", "");
        rejected(fixture, "test-is-not-main", "targetClasses", "fixture.ProductionTest");
        configure(fixture, "\"fixture.Production*\"", "\"fixture.ProductionTest\"", "\"fixture.Production\"", "", "");
        rejected(fixture, "excluded-production", "targetClasses", "fixture.Production*");
        configure(fixture, "\"fixture.Production*\"", "\"fixture.ProductionTest\"", "", "\"fixture.Production*\"", "");
        rejected(fixture, "excluded-test", "targetTests", "fixture.ProductionTest");
        configure(fixture, "\"fixture.Production*\"", "\"fixture.ProductionTest\"", "\"fixture.ProductionTest\"", "", "");
        succeeded(fixture, "production-exclusion-does-not-exclude-test");
        configure(fixture, "\"fixture.*Productio?\"", "\"~fixture[.]Production(Test)\"", "\"fixture.*Native\"", "", "");
        succeeded(fixture, "glob-and-regex");
        configure(fixture, "\"**.Production\"", "\"**.ProductionTest\"", "", "", "");
        succeeded(fixture, "package-glob");
        configure(fixture, "\"~[\"", "\"fixture.ProductionTest\"", "", "", "");
        rejected(fixture, "invalid-pattern", "Invalid PIT selector", "~[");
        configure(fixture, "\"fixture.Production*\"", "\"fixture.ProductionTest\"", "", "", "targetClasses.set(setOf(\"fixture.TaskOverrideMissing\"))");
        rejected(fixture, "task-override", "targetClasses", "fixture.TaskOverrideMissing");
        configure(fixture, "\"fixture.Production*\"", "\"fixture.ProductionTest\"", "", "", "");
        rejected(fixture, "cli-override", "targetTests", "fixture.CliMissing", "--targetTests=fixture.CliMissing");
        rejected(fixture, "cli-comma-stale", "targetTests", "fixture.CliMissing", "--targetTests=fixture.ProductionTest,fixture.CliMissing");
        succeeded(fixture, "cli-comma-valid", "--targetTests=fixture.ProductionTest,fixture.Production*");
        succeeded(fixture, "cli-empty-tokens", "--targetTests=,fixture.ProductionTest,,");
        rejected(fixture, "cli-separators-only", "targetTests", ",,", "--targetTests=,,");
        configure(fixture, "\"fixture.Production*,fixture.MainOnly\"", "\"fixture.ProductionTest\"", "", "", "");
        succeeded(fixture, "comma-production");
        configure(fixture, "\"fixture.Production*,fixture.MainOnly\"", "\"fixture.ProductionTest\"", "\"fixture.Missing,fixture.MainOnly\"", "", "");
        rejected(fixture, "comma-excluded-production", "targetClasses", "fixture.MainOnly");
        configure(fixture, "\"fixture.Production*\"", "\"fixture.ProductionTest\"", "", "\"fixture.Missing,fixture.ProductionTest\"", "");
        rejected(fixture, "comma-excluded-test", "targetTests", "fixture.ProductionTest");
        configure(fixture, "\"fixture.Production*\"", "", "", "", "");
        succeeded(fixture, "empty-tests-use-production-filter");
        configure(fixture, "\"fixture.Production*\"", "\"fixture.ProductionTest\"", "", "", "");

        BuildResult fresh = succeeded(fixture, "valid-fresh");
        require(fresh.task(":compileJava") != null && fresh.task(":compileTestJava") != null, "Gate must select both compilation tasks");
        BuildResult reused = run(fixture, "valid-reused");
        require(reused.task(":pitest") != null && List.of(TaskOutcome.SUCCESS, TaskOutcome.UP_TO_DATE).contains(reused.task(":pitest").getOutcome()),
            "Reused invocation must accept the normal PIT up-to-date outcome");
        require(reused.getOutput().contains("Reusing configuration cache."), "Valid invocation must reuse strict configuration cache");
        require(reused.task(":verifyPitestTargetInventory").getOutcome() == TaskOutcome.SUCCESS, "Inventory must be rechecked rather than cached without evidence");
        write(fixture, "src/main/java/fixture/Production.java", "package fixture; class RenamedProduction {}\n");
        BuildResult renamed = rejected(fixture, "renamed-bytecode", "targetClasses", "fixture.Production*");
        require(renamed.getOutput().contains("Reusing configuration cache."), "Class rename must be detected even with reused configuration");
        require(!Files.exists(fixture.resolve("build/classes/java/main/fixture/Production.class")), "Compiler must remove renamed class bytecode");
        System.out.println("PITEST_TARGET_INVENTORY_OK mixed selectors, source roots, exclusions, overrides, compilation ordering, strict cache reuse and renamed bytecode");
    }

    private static void configure(Path fixture, String targets, String tests, String exclusions, String excludedTests, String overrides) throws Exception {
        write(fixture, "build.gradle.kts", """
            plugins { java; alias(libs.plugins.pitest) }
            repositories { mavenCentral() }
            pitest {
                pitestVersion.set("1.22.1")
                targetClasses.set(setOf(%s))
                targetTests.set(setOf(%s))
                excludedClasses.set(setOf(%s))
                excludedTestClasses.set(setOf(%s))
            }
            apply(from = file("gradle/pitest-limits.gradle"))
            tasks.named<info.solidsoft.gradle.pitest.PitestTask>("pitest") {
                %s
                actions.clear()
                outputs.doNotCacheIf("The fixture substitutes the mutation process with a marker") { true }
                doLast { println("PIT_PROCESS_BOUNDARY_REACHED") }
            }
            """.formatted(targets, tests, exclusions, excludedTests, overrides));
    }

    private static BuildResult rejected(Path fixture, String scenario, String kind, String selector, String... extra) throws Exception {
        BuildResult result = run(fixture, scenario, extra);
        require(result.task(":verifyPitestTargetInventory") != null
            && result.task(":verifyPitestTargetInventory").getOutcome() == TaskOutcome.FAILED,
            scenario + ": inventory gate must fail before PIT (see " + fixture.resolve(scenario + ".log") + ")");
        require(!result.getOutput().contains("PIT_PROCESS_BOUNDARY_REACHED"), scenario + ": PIT process must not start");
        require(result.getOutput().contains(kind) && result.getOutput().contains(selector), scenario + ": diagnostic must identify selector and kind");
        return result;
    }

    private static BuildResult succeeded(Path fixture, String scenario, String... extra) throws Exception {
        BuildResult result = run(fixture, scenario, extra);
        require(result.task(":pitest") != null && result.task(":pitest").getOutcome() == TaskOutcome.SUCCESS,
            scenario + ": valid inventory must reach PIT (see " + fixture.resolve(scenario + ".log") + ")");
        require(result.getOutput().contains("PIT_PROCESS_BOUNDARY_REACHED"), scenario + ": missing PIT process boundary");
        return result;
    }

    private static BuildResult run(Path fixture, String scenario, String... extra) throws Exception {
        var arguments = new ArrayList<>(List.of("pitest"));
        arguments.addAll(List.of(extra));
        arguments.addAll(List.of("--configuration-cache", "--configuration-cache-problems=fail", "--build-cache", "--max-workers=1", "--console=plain"));
        BuildResult result = GradleRunner.create().withProjectDir(fixture.toFile()).withArguments(arguments).run();
        Files.writeString(fixture.resolve(scenario + ".log"), result.getOutput());
        return result;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static void write(Path root, String relative, String content) throws Exception {
        Path target = root.resolve(relative);
        Files.createDirectories(target.getParent());
        Files.writeString(target, content);
    }
}
