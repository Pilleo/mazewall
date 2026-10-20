package io.mazewall.build;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.gradle.testkit.runner.BuildResult;
import org.gradle.testkit.runner.GradleRunner;

/** Resolves the actual shared PIT configuration without running mutation workloads. */
public final class PitestLimitsFunctionalTest {
    private PitestLimitsFunctionalTest() {}

    public static void main(String[] args) {
        if (args.length != 1) throw new AssertionError("Expected the actual repository root");
        verify(Path.of(args[0]));
    }

    static void verify(Path repository) {
        Path script = repository.resolve("gradle/pitest-limits.gradle");
        if (!Files.isRegularFile(script)) throw new AssertionError("Missing production PIT limits script: " + script);
        try {
            Path fixture = Files.createTempDirectory("pitest-limits-");
            System.out.println("PIT limits fixture: " + fixture);
            write(fixture, "gradle/pitest-limits.gradle", Files.readString(script));
            write(fixture, "gradle.properties", Files.readString(repository.resolve("gradle.properties")));
            write(fixture, "gradle/libs.versions.toml", Files.readString(repository.resolve("gradle/libs.versions.toml")));
            write(fixture, "settings.gradle.kts", "include(\":enforcer\", \":platform\", \":profiler\")\n");
            write(fixture, "build.gradle.kts", "plugins { alias(libs.plugins.pitest) apply false }\n");
            writeModule(fixture, "enforcer", 89, 61, 76);
            writeModule(fixture, "platform", 97, 61, 98);
            writeModule(fixture, "profiler", 93, 65, 75);

            assertConfiguration(runner(fixture).build(), 2);
            assertConfiguration(runner(fixture, "-Pmazewall.pitest.threads=1").build(), 1);
            assertConfiguration(runner(fixture, "-Dpitest.threads=3").build(), 3);
            assertConfiguration(runner(fixture, "-Pmazewall.pitest.threads=2", "-Dpitest.threads=4").build(), 2);
            assertConfiguration(runner(fixture, "-Pmazewall.pitest.threads=2", "-Dpitest.threads=abc").build(), 2);
            for (String property : List.of("-Pmazewall.pitest.threads=", "-Dpitest.threads=")) {
                for (String invalid : List.of("0", "-1", "", "abc")) {
                    BuildResult rejected = runner(fixture, property + invalid).buildAndFail();
                    if (!rejected.getOutput().contains("PIT threads must be a positive integer")) {
                        throw new AssertionError("Selected invalid PIT thread value must fail validation: "
                            + property + invalid + "\n" + rejected.getOutput());
                    }
                }
            }
            for (String property : List.of("-Pmazewall.pitest.threads=", "-Dpitest.threads=")) {
                for (int threads : List.of(1, 3)) {
                    assertConfiguration(runner(fixture, "--configuration-cache", property + threads).build(), threads);
                    BuildResult reused = runner(fixture, "--configuration-cache", property + threads).build();
                    assertConfiguration(reused, threads);
                    if (!reused.getOutput().contains("Reusing configuration cache.")) {
                        throw new AssertionError("PIT configuration must reuse the cache with the current property: "
                            + reused.getOutput());
                    }
                }
            }
            System.out.println("PITEST_LIMITS_OK defaults, overrides, precedence, validation, JVM budgets, unrelated settings and configuration-cache changes");
        } catch (Exception failure) {
            throw new AssertionError("Shared PIT limits configuration contract failed", failure);
        }
    }

    private static void writeModule(Path fixture, String module, int coverage, int mutation, int strength) throws Exception {
        write(fixture, module + "/build.gradle.kts", """
            import java.math.BigDecimal
            plugins {
                java
                id("info.solidsoft.pitest")
            }
            pitest {
                junit5PluginVersion.set("1.2.1")
                targetClasses.set(setOf("fixture.Production*"))
                targetTests.set(setOf("fixture.ProductionTest"))
                excludedClasses.set(setOf("fixture.Native*"))
                excludedMethods.set(setOf("nativeCall"))
                excludedTestClasses.set(setOf("fixture.KernelTest"))
                timeoutConstInMillis.set(2000)
                timeoutFactor.set(BigDecimal("1.25"))
                coverageThreshold.set(%d)
                mutationThreshold.set(%d)
                testStrengthThreshold.set(%d)
            }
            apply(from = rootProject.file("gradle/pitest-limits.gradle"))
            val limits = extensions.getByType<info.solidsoft.gradle.pitest.PitestPluginExtension>()
            val report = listOf(
                "PITEST:" + name,
                "threads=" + limits.threads.get(),
                "parent=" + limits.mainProcessJvmArgs.get().joinToString(","),
                "minion=" + limits.jvmArgs.get().joinToString(","),
                "junit=" + limits.junit5PluginVersion.get(),
                "targets=" + limits.targetClasses.get().sorted().joinToString(","),
                "tests=" + limits.targetTests.get().sorted().joinToString(","),
                "excluded=" + limits.excludedClasses.get().sorted().joinToString(","),
                "methods=" + limits.excludedMethods.get().sorted().joinToString(","),
                "excludedTests=" + limits.excludedTestClasses.get().sorted().joinToString(","),
                "timeout=" + limits.timeoutConstInMillis.get() + "," + limits.timeoutFactor.get(),
                "thresholds=" + limits.coverageThreshold.get() + "," + limits.mutationThreshold.get() + "," + limits.testStrengthThreshold.get()
            ).joinToString("|")
            tasks.register("printPitestConfiguration") {
                val resolvedReport = report
                doLast { println(resolvedReport) }
            }
            """.formatted(coverage, mutation, strength));
    }

    private static void assertConfiguration(BuildResult result, int threads) {
        for (String module : List.of("enforcer", "platform", "profiler")) {
            String thresholds = switch (module) {
                case "enforcer" -> "89,61,76";
                case "platform" -> "97,61,98";
                case "profiler" -> "93,65,75";
                default -> throw new AssertionError("Unexpected fixture module: " + module);
            };
            String expected = "PITEST:" + module + "|threads=" + threads
                + "|parent=-Xms128m,-Xmx512m,-XX:MaxMetaspaceSize=256m,--enable-native-access=ALL-UNNAMED"
                + "|minion=-Xms128m,-Xmx256m,-XX:MaxMetaspaceSize=256m,--enable-native-access=ALL-UNNAMED"
                + "|junit=1.2.1|targets=fixture.Production*|tests=fixture.ProductionTest|excluded=fixture.Native*"
                + "|methods=nativeCall|excludedTests=fixture.KernelTest|timeout=2000,1.25|thresholds=" + thresholds;
            if (!result.getOutput().lines().anyMatch(expected::equals)) {
                throw new AssertionError("Expected resolved configuration " + expected + "\n" + result.getOutput());
            }
        }
    }

    private static GradleRunner runner(Path fixture, String... overrides) {
        var arguments = new ArrayList<>(List.of(
            ":enforcer:printPitestConfiguration", ":platform:printPitestConfiguration", ":profiler:printPitestConfiguration",
            "--max-workers=1", "--no-parallel", "--console=plain"));
        arguments.addAll(List.of(overrides));
        return GradleRunner.create().withProjectDir(fixture.toFile()).withArguments(arguments);
    }

    private static void write(Path root, String relative, String content) throws Exception {
        Path target = root.resolve(relative);
        Files.createDirectories(target.getParent());
        Files.writeString(target, content);
    }
}
