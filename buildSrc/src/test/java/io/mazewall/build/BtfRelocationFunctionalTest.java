package io.mazewall.build;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import org.gradle.testkit.runner.GradleRunner;

/** Disposable checkout names must not change the published BTF resource or its containing JAR. */
public final class BtfRelocationFunctionalTest {
    private BtfRelocationFunctionalTest() {}

    public static void main(String[] args) throws Exception {
        verify(Path.of(args[0]));
    }

    static void verify(Path repository) throws Exception {
        String production = Files.readString(repository.resolve("profiler/build.gradle.kts"));
        String generator = production.substring(0, production.indexOf("\nplugins {"));
        String source = Files.readString(repository.resolve("profiler/src/main/c/btf/invocation_state.c"));
        Path fixtures = Files.createTempDirectory("btf-relocation-");
        byte[] resource = null;
        byte[] artifact = null;
        Path second = null;
        for (String checkout : List.of("first", "second")) {
            Path project = Files.createDirectory(fixtures.resolve(checkout));
            Files.writeString(project.resolve("settings.gradle.kts"), "rootProject.name = \"btf-fixture\"\n");
            Files.writeString(project.resolve("build.gradle.kts"), generator + """
                plugins { java }
                val btf = tasks.register<GenerateInvocationStateBtf>("generateInvocationStateBtf") {
                    source.set(layout.projectDirectory.file("src/invocation_state.c"))
                    objectFile.set(layout.buildDirectory.file("generated/invocation_state.o"))
                    resource.set(layout.buildDirectory.file("resources/btf/invocation_state.btf"))
                }
                tasks.jar {
                    dependsOn(btf)
                    from(layout.buildDirectory.dir("resources"))
                }
                """);
            Files.createDirectory(project.resolve("src"));
            Files.writeString(project.resolve("src/invocation_state.c"), source);
            run(project);
            byte[] current = Files.readAllBytes(project.resolve("build/resources/btf/invocation_state.btf"));
            byte[] jar = Files.readAllBytes(project.resolve("build/libs/btf-fixture.jar"));
            if (resource != null && !Arrays.equals(resource, current)) throw new AssertionError("Relocation changed published BTF bytes");
            if (artifact != null && !Arrays.equals(artifact, jar)) throw new AssertionError("Relocation changed containing JAR bytes");
            String strings = new String(current, StandardCharsets.ISO_8859_1);
            if (strings.contains(fixtures.toString())) throw new AssertionError("BTF embeds disposable checkout path");
            if (!strings.contains("mazewall_invocation_state\0") || !strings.contains("mazewall_invocation_state_v2\0")) {
                throw new AssertionError("Both invocation state types must remain in BTF");
            }
            resource = current;
            artifact = jar;
            second = project;
        }
        Path project = java.util.Objects.requireNonNull(second);
        Files.writeString(project.resolve("src/invocation_state.c"), source.replace("u32 reserved;", "u64 reserved;"));
        run(project);
        if (Arrays.equals(resource, Files.readAllBytes(project.resolve("build/resources/btf/invocation_state.btf")))) {
            throw new AssertionError("Changed C type layout reused stale BTF");
        }
        System.out.println("BTF_RELOCATION_OK identical source publishes identical resource/JAR bytes; changed types invalidate");
    }

    private static void run(Path project) {
        GradleRunner.create().withProjectDir(project.toFile())
            .withArguments("jar", "--no-configuration-cache", "--max-workers=1").build();
    }
}
