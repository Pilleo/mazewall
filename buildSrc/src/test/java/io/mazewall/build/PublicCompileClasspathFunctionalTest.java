package io.mazewall.build;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.gradle.testkit.runner.GradleRunner;

/** Exercises the actual Spring Test configuration and real external javac visibility. */
final class PublicCompileClasspathFunctionalTest {
    private PublicCompileClasspathFunctionalTest() {}

    static void verify(Path repository) {
        try {
            Path root = Files.createTempDirectory("public-compile-classpath-");
            Files.writeString(root.resolve("settings.gradle.kts"), "rootProject.name = \"public-classpath-fixture\"\n");
            String spring = Files.readString(repository.resolve(
                "integrations/spring/mazewall-spring-boot/build.gradle.kts"));
            String testConfiguration = spring.substring(spring.indexOf("tasks.test {"), spring.indexOf("\npublishing {"));
            Files.writeString(root.resolve("build.gradle.kts"), """
                import io.mazewall.build.*
                plugins { java; id("io.mazewall.test-health") }
                repositories { mavenCentral() }
                dependencies {
                    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
                    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.10.2")
                }
                """ + testConfiguration);
            write(root, "src/main/java/fixture/PublicApi.java", "package fixture; public class PublicApi {}\n");
            write(root, "src/test/java/fixture/TestOnlyApi.java", "package fixture; public class TestOnlyApi {}\n");
            write(root, "src/test/java/fixture/ClasspathTest.java", """
                package fixture;
                import java.nio.file.*;
                import javax.tools.ToolProvider;
                import org.junit.jupiter.api.Test;
                import static org.junit.jupiter.api.Assertions.*;
                public class ClasspathTest {
                    @Test void independentConsumerSeesPublicApiButNotTestOutput() throws Exception {
                        String classpath = System.getProperty("mazewall.spring.publicCompileClasspath");
                        assertNotNull(classpath);
                        Path scratch = Files.createTempDirectory("independent-javac-");
                        Path source = scratch.resolve("Consumer.java");
                        var compiler = ToolProvider.getSystemJavaCompiler();
                        var diagnostics = new java.io.ByteArrayOutputStream();
                        Files.writeString(source, "class Consumer { fixture.PublicApi value; }");
                        assertEquals(0, compiler.run(null, null, diagnostics, "-classpath", classpath,
                            "-d", scratch.toString(), source.toString()), diagnostics.toString());
                        Files.writeString(source, "class Consumer { fixture.TestOnlyApi value; }");
                        assertEquals(1, compiler.run(null, null, diagnostics, "-classpath", classpath,
                            "-d", scratch.toString(), source.toString()), "test output leaked to public consumer");
                    }
                }
                """);
            run(root);
            String reused = run(root);
            if (!reused.contains("Reusing configuration cache")) {
                throw new AssertionError("Actual Spring test configuration did not reuse its cache: " + reused);
            }
            System.out.println("Public compile classpath: real javac visibility and configuration-cache reuse passed.");
        } catch (Exception failure) {
            throw new AssertionError("Public compile classpath contract failed", failure);
        }
    }

    private static String run(Path root) {
        var classpath = new ArrayList<java.io.File>();
        for (String entry : System.getProperty("java.class.path").split(java.io.File.pathSeparator)) {
            if (entry.contains("buildSrc/build/classes/java/main") || entry.contains("buildSrc/build/resources/main")) {
                classpath.add(new java.io.File(entry));
            }
        }
        return GradleRunner.create().withProjectDir(root.toFile()).withPluginClasspath(classpath)
            .withArguments(List.of("test", "--configuration-cache", "--configuration-cache-problems=fail", "--max-workers=1"))
            .forwardOutput().build().getOutput();
    }

    private static void write(Path root, String name, String content) throws Exception {
        Path target = root.resolve(name);
        Files.createDirectories(target.getParent());
        Files.writeString(target, content);
    }
}
