package io.mazewall.build;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.gradle.testkit.runner.BuildResult;
import org.gradle.testkit.runner.GradleRunner;
import org.gradle.testkit.runner.TaskOutcome;

/** Cache native build outputs only; never package CTest execution or mutable CMake state. */
final class TierENativeBuildTaskFunctionalTest {
    private static final String IMAGE_DIGEST = "sha256:" + "a".repeat(64);
    private TierENativeBuildTaskFunctionalTest() {}

    static void verify() {
        try {
            Path cache = Files.createTempDirectory("tier-e-native-test-build-cache-");
            Path docker = cache.resolve("fake-docker");
            Files.writeString(docker, """
                #!/bin/sh
                while [ "$#" -gt 0 ]; do
                    if [ "$1" = "-v" ]; then
                        case "$2" in *:/workspace/profiler-native/build) output=${2%:/workspace/profiler-native/build};; esac
                        shift 2
                    else shift; fi
                done
                mkdir -p "$output/agent-test-classes"
                printf native > "$output/libmazewall_stack_agent.so"
                printf fixture > "$output/dummy_test"
                chmod +x "$output/dummy_test"
                printf class > "$output/agent-test-classes/AgentWorkload.class"
                printf jar > "$output/agent-test-classes/redefinition-agent.jar"
                """);
            docker.toFile().setExecutable(true);
            Path first = Files.createTempDirectory("tier-e-native-first-");
            Path second = Files.createTempDirectory("tier-e-native-second-");
            fixture(first, cache, docker);
            fixture(second, cache, docker);
            equal(TaskOutcome.SUCCESS, run(first).task(":nativeBuild").getOutcome());
            equal(TaskOutcome.UP_TO_DATE, run(first).task(":nativeBuild").getOutcome());
            equal(TaskOutcome.FROM_CACHE, run(second).task(":nativeBuild").getOutcome());
            if (!Files.readString(second.resolve("build/native-package/manifest.json")).contains(IMAGE_DIGEST)) {
                throw new AssertionError("Restored native package lost image identity");
            }
            Files.writeString(second.resolve("native/src/agent.cpp"), "different native source\n");
            equal(TaskOutcome.SUCCESS, run(second).task(":nativeBuild").getOutcome());
            Files.writeString(second.resolve("native/src/agent.cpp"), "native source\n");
            equal(TaskOutcome.FROM_CACHE, run(second).task(":nativeBuild").getOutcome());
            Files.writeString(second.resolve("build.gradle"),
                Files.readString(second.resolve("build.gradle")).replace("compilerVersion.set('compiler-a')", "compilerVersion.set('compiler-b')"));
            equal(TaskOutcome.SUCCESS, run(second).task(":nativeBuild").getOutcome());
            Path note = second.resolve("build/native-package/operator-note.txt");
            Files.writeString(note, "operator-owned\n");
            Files.writeString(second.resolve("native/src/agent.cpp"), "third native source\n");
            GradleRunner.create().withProjectDir(second.toFile()).withPluginClasspath(pluginClasspath())
                .withArguments("nativeBuild", "--build-cache").buildAndFail();
            if (!Files.readString(note).equals("operator-owned\n")) {
                throw new AssertionError("Native artifact replacement discarded an unrelated file");
            }
        } catch (Exception error) {
            throw new AssertionError("Portable native build Gradle cache contract failed", error);
        }
    }

    private static void fixture(Path root, Path cache, Path docker) throws Exception {
        Files.writeString(root.resolve("settings.gradle"), "rootProject.name='tier-e-native-fixture'\n"
            + "buildCache { local { directory = file('" + cache + "') } }\n");
        Files.writeString(root.resolve("build.gradle"), """
            import io.mazewall.build.TierENativeBuildTask
            plugins { id 'io.mazewall.test-health' }
            tasks.register('nativeBuild', TierENativeBuildTask) {
                nativeDirectory.fileValue(file('native'))
                nativeSources.from(fileTree('native') { include 'CMakeLists.txt', 'src/**', 'include/**', 'tests/**' })
                dockerExecutable.fileValue(file('%s'))
                dockerEndpoint.set('unix:///var/run/docker.sock')
                imageDigest.set('%s')
                compilerVersion.set('compiler-a')
                javaVersion.set('jdk-25')
                cmakeVersion.set('cmake-a')
                targetArchitecture.set('x86_64')
                buildType.set('Debug')
                usdtEnabled.set(false)
                artifactDirectory.fileValue(file('build/native-package'))
            }
            """.formatted(docker, IMAGE_DIGEST));
        write(root, "native/CMakeLists.txt", "add_library(mazewall_stack_agent SHARED src/agent.cpp)\nadd_executable(dummy_test tests/dummy_test.cpp)\n");
        write(root, "native/src/agent.cpp", "native source\n");
        write(root, "native/tests/dummy_test.cpp", "test source\n");
    }

    private static void write(Path root, String name, String value) throws Exception {
        Path path = root.resolve(name);
        Files.createDirectories(path.getParent());
        Files.writeString(path, value);
    }

    private static BuildResult run(Path root) {
        return GradleRunner.create().withProjectDir(root.toFile()).withPluginClasspath(pluginClasspath())
            .withArguments("nativeBuild", "--build-cache").build();
    }

    private static List<java.io.File> pluginClasspath() {
        var entries = new ArrayList<java.io.File>();
        for (String item : System.getProperty("java.class.path").split(java.io.File.pathSeparator)) {
            if (item.contains("buildSrc/build/classes/java/main") || item.contains("buildSrc/build/resources/main")) {
                entries.add(new java.io.File(item));
            }
        }
        if (entries.isEmpty()) {
            entries.add(Path.of(TierENativeBuildTaskFunctionalTest.class.getProtectionDomain().getCodeSource().getLocation().getPath()).toFile());
        }
        return List.copyOf(entries);
    }

    private static void equal(TaskOutcome expected, TaskOutcome actual) {
        if (expected != actual) throw new AssertionError("Expected " + expected + ", got " + actual);
    }
}
