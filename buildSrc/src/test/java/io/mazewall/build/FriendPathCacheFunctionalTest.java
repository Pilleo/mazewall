package io.mazewall.build;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;
import org.gradle.testkit.runner.BuildResult;
import org.gradle.testkit.runner.GradleRunner;
import org.gradle.testkit.runner.TaskOutcome;

/** Real internal Kotlin access must survive relocation without putting absolute paths in compiler inputs. */
public final class FriendPathCacheFunctionalTest {
    private FriendPathCacheFunctionalTest() {}

    public static void main(String[] args) throws Exception {
        verify(Path.of(args[0]));
    }

    static void verify(Path repository) throws Exception {
        String enforcer = Files.readString(repository.resolve("enforcer/build.gradle.kts"));
        String registration = enforcer.substring(enforcer.indexOf("kotlinCompilations.matching"), enforcer.indexOf("kotlinCompilations.named(\"integrationTest\")"));
        var matcher = Pattern.compile("(?m)^kotlin\\s*=\\s*\"([^\"]+)\"").matcher(Files.readString(repository.resolve("gradle/libs.versions.toml")));
        if (!matcher.find()) throw new AssertionError("Missing configured Kotlin version");
        Path fixtures = Files.createTempDirectory("friend-path-cache-");
        Path second = null;
        String consumer = """
            import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension
            plugins { id("org.jetbrains.kotlin.jvm") }
            kotlin { jvmToolchain(25) }
            evaluationDependsOn(":producer")
            val platformJar = project(":producer").tasks.named<Jar>("jar").flatMap { it.archiveFile }
            dependencies { implementation(files(platformJar)) }
            val kotlinExtension = extensions.getByType<KotlinJvmProjectExtension>()
            val kotlinCompilations = kotlinExtension.target.compilations
            """;
        String producerHash = null;
        for (String checkout : List.of("first", "second")) {
            Path project = Files.createDirectory(fixtures.resolve(checkout));
            write(project, "settings.gradle.kts", """
                pluginManagement { repositories { gradlePluginPortal(); mavenCentral() } }
                rootProject.name = "friend-path-fixture"
                include(":producer", ":consumer")
                buildCache { local { directory = file("%s") } }
                """.formatted(fixtures.resolve("cache")));
            write(project, "build.gradle.kts", "plugins { id(\"org.jetbrains.kotlin.jvm\") version \"" + matcher.group(1) + "\" apply false }\nallprojects { repositories { mavenCentral() } }\n");
            write(project, "producer/build.gradle.kts", "plugins { id(\"org.jetbrains.kotlin.jvm\") }\nkotlin { jvmToolchain(25) }\n");
            write(project, "producer/src/main/kotlin/friend/Artifact.kt", "package friend\ninternal fun answer(): Int = 42\n");
            write(project, "consumer/build.gradle.kts", consumer + registration);
            write(project, "consumer/src/main/kotlin/friend/Use.kt", "package friend\nfun use(): Int = answer()\n");
            BuildResult result = runner(project).build();
            TaskOutcome expected = checkout.equals("first") ? TaskOutcome.SUCCESS : TaskOutcome.FROM_CACHE;
            if (result.task(":consumer:compileKotlin").getOutcome() != expected) {
                throw new AssertionError(checkout + " friend compilation must be " + expected + ": " + result.getOutput());
            }
            byte[] jar = Files.readAllBytes(project.resolve("producer/build/libs/producer.jar"));
            String hash = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(jar));
            if (producerHash != null && !producerHash.equals(hash)) throw new AssertionError("Relocation changed producer artifact bytes");
            producerHash = hash;
            if (!Files.isRegularFile(project.resolve("consumer/build/classes/kotlin/main/friend/UseKt.class"))) throw new AssertionError("Missing compiled internal-access caller");
            second = project;
        }
        Path project = java.util.Objects.requireNonNull(second);
        write(project, "consumer/build.gradle.kts", consumer);
        BuildResult denied = runner(project).buildAndFail();
        if (denied.task(":consumer:compileKotlin").getOutcome() != TaskOutcome.FAILED) throw new AssertionError("Removing friend authority must reject internal access");
        write(project, "consumer/build.gradle.kts", consumer + registration);
        write(project, "producer/src/main/kotlin/friend/Artifact.kt", "package friend\ninternal fun renamedAnswer(): Int = 42\n");
        BuildResult changed = runner(project).buildAndFail();
        if (changed.task(":consumer:compileKotlin").getOutcome() != TaskOutcome.FAILED) throw new AssertionError("Changed internal API reused stale consumer output");
        System.out.println("FRIEND_PATH_CACHE_OK relocated compilation reused; internal access and changed-friend invalidation enforced");
    }

    private static GradleRunner runner(Path project) {
        return GradleRunner.create().withProjectDir(project.toFile())
            .withArguments(":consumer:compileKotlin", "--build-cache", "--no-configuration-cache", "--max-workers=1");
    }

    private static void write(Path root, String name, String contents) throws Exception {
        Path target = root.resolve(name);
        Files.createDirectories(target.getParent());
        Files.writeString(target, contents);
    }
}
