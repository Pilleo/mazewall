package io.mazewall.build;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.gradle.testkit.runner.GradleRunner;
import org.gradle.testkit.runner.TaskOutcome;

/** Fresh producer checkouts must retain nested Gradle cache reuse, not old test outcomes. */
public final class TestKitCacheFunctionalTest {
    private TestKitCacheFunctionalTest() {}

    public static void main(String[] args) throws Exception {
        verify();
    }

    static void verify() throws Exception {
        Path cache = Path.of(System.getProperty("org.gradle.testkit.dir")).toAbsolutePath().normalize();
        Path home = Path.of(System.getProperty("mazewall.test.gradleUserHome")).toAbsolutePath().normalize();
        if (!cache.equals(home.resolve("caches/mazewall-test-kit"))) {
            throw new AssertionError("Nested Gradle cache must follow the configured Gradle home: " + cache);
        }
        Path fixtures = Files.createTempDirectory("testkit-cache-reuse-");
        String name = "CacheProbe" + UUID.randomUUID().toString().replace("-", "");
        for (String checkout : new String[] {"first", "second"}) {
            Path root = Files.createDirectory(fixtures.resolve(checkout));
            Files.writeString(root.resolve("settings.gradle"), "rootProject.name = 'cache-reuse'\n");
            Files.writeString(root.resolve("build.gradle"), """
                plugins { id 'java' }
                tasks.register('verifyNestedHome') {
                    doLast { println('NESTED_GRADLE_HOME=' + gradle.gradleUserHomeDir.canonicalPath) }
                }
                """);
            Path source = root.resolve("src/main/java/" + name + ".java");
            Files.createDirectories(source.getParent());
            Files.writeString(source, "public final class " + name + " {}\n");
            var result = GradleRunner.create().withProjectDir(root.toFile())
                .withArguments("compileJava", "verifyNestedHome", "--build-cache", "--max-workers=1")
                .build();
            if (!result.getOutput().contains("NESTED_GRADLE_HOME=" + cache.toRealPath())) {
                throw new AssertionError("Nested build did not use the configured persistent TestKit home: " + result.getOutput());
            }
            var outcome = result.task(":compileJava").getOutcome();
            TaskOutcome expected = checkout.equals("first") ? TaskOutcome.SUCCESS : TaskOutcome.FROM_CACHE;
            if (outcome != expected || !Files.isRegularFile(root.resolve("build/classes/java/main/" + name + ".class"))) {
                throw new AssertionError(checkout + " compilation must be " + expected + ": " + result.getOutput());
            }
        }
        System.out.println("TESTKIT_CACHE_REUSE_OK independent checkouts share compilation cache");
    }
}
