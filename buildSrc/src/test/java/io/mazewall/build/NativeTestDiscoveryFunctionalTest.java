package io.mazewall.build;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import javax.xml.parsers.DocumentBuilderFactory;
import org.gradle.testkit.runner.GradleRunner;

/** Helper classes must not consume a fresh JVM; inherited/nested tests and CLI filters remain executable. */
public final class NativeTestDiscoveryFunctionalTest {
    private NativeTestDiscoveryFunctionalTest() {}

    public static void main(String[] args) throws Exception {
        verify(Path.of(args[0]));
    }

    static void verify(Path repository) throws Exception {
        String production = Files.readString(repository.resolve("portals/runtime/build.gradle.kts"));
        String registration = production.substring(production.indexOf("val integrationTest ="), production.indexOf("\ntasks.test {"));
        Path root = Files.createTempDirectory("native-test-discovery-");
        Path forks = Files.createDirectory(root.resolve("forks"));
        write(root, "settings.gradle.kts", "rootProject.name=\"native-discovery\"\ninclude(\":portals:worker\")\n");
        write(root, "portals/worker/build.gradle.kts", "plugins { java }\n");
        write(root, "build.gradle.kts", """
            import org.gradle.process.CommandLineArgumentProvider
            plugins { java; id("io.mazewall.test-health") }
            sourceSets.create("integrationTest")
            repositories { mavenCentral() }
            dependencies {
                "integrationTestImplementation"("org.junit.jupiter:junit-jupiter:5.10.2")
                "integrationTestRuntimeOnly"("org.junit.platform:junit-platform-launcher:1.10.2")
            }
            class EmptyWorkerClasspath : CommandLineArgumentProvider {
                override fun asArguments() = emptyList<String>()
            }
            val portalWorkerCpArgProvider = EmptyWorkerClasspath()
            """ + registration + "\nintegrationTest.configure { jvmArgs(\"-Xlog:os=info:file=" + forks + "/fork-%p.log\") }\n");
        write(root, "src/integrationTest/java/fixture/ConcreteTest.java", """
            package fixture;
            import org.junit.jupiter.api.*;
            abstract class Base { @Test void inherited() {} }
            public class ConcreteTest extends Base {
                @Nested class Inner { @Test void nested() {} }
            }
            class IndependentTest { @Test void independent() {} }
            class OuterContainer { @Nested class Checks { @Test void nestedOnly() {} } }
            class Helper { static { if (true) throw new AssertionError("Helper must never load"); } }
            """);
        run(root);
        equal(3L, count(forks), "One fresh JVM per real root, none for helpers");
        equal(4, cases(root), "Inherited/nested test inventory");
        run(root, "--tests", "fixture.ConcreteTest");
        equal(4L, count(forks), "CLI filter must launch only its selected root");
        equal(2, cases(root), "CLI filter must retain inherited and nested cases");
        write(root, "src/integrationTest/java/fixture/AddedTest.java", "package fixture; class AddedTest { @org.junit.jupiter.api.Test void added() {} }\n");
        run(root);
        equal(8L, count(forks), "Changed compiled input must discover the added root");
        equal(5, cases(root), "Changed input inventory");
        System.out.println("NATIVE_DISCOVERY_OK no empty forks; inherited/nested/filtered/added tests execute with fresh JVMs");
    }

    private static void run(Path root, String... extra) throws Exception {
        var args = new java.util.ArrayList<>(List.of("integrationTest", "--max-workers=1", "--configuration-cache", "--configuration-cache-problems=fail"));
        args.addAll(List.of(extra));
        GradleRunner.create().withProjectDir(root.toFile())
            .withPluginClasspath(TestHealthPluginFunctionalTest.pluginClasspath())
            .withArguments(args).build();
    }

    private static long count(Path forks) throws Exception {
        try (var files = Files.list(forks)) { return files.count(); }
    }

    private static int cases(Path root) throws Exception {
        int count = 0;
        try (var files = Files.list(root.resolve("build/test-results/integrationTest"))) {
            for (Path file : files.filter(p -> p.getFileName().toString().startsWith("TEST-")).toList()) {
                var suite = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file.toFile()).getDocumentElement();
                equal("0", suite.getAttribute("failures"), "Fixture failures");
                equal("0", suite.getAttribute("skipped"), "Fixture skips");
                count += Integer.parseInt(suite.getAttribute("tests"));
            }
        }
        return count;
    }

    private static void equal(Object expected, Object actual, String label) {
        if (!expected.equals(actual)) throw new AssertionError(label + ": expected " + expected + ", got " + actual);
    }

    private static void write(Path root, String name, String contents) throws Exception {
        Path file = root.resolve(name);
        Files.createDirectories(file.getParent());
        Files.writeString(file, contents);
    }
}
