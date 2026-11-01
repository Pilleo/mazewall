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
            sourceSets["integrationTest"].compileClasspath += sourceSets.test.get().output
            sourceSets["integrationTest"].runtimeClasspath += sourceSets.test.get().output
            val ancestors = sourceSets.create("ancestors")
            configurations[ancestors.implementationConfigurationName].extendsFrom(configurations.testImplementation.get())
            val ancestorJar = tasks.register<Jar>("ancestorJar") { from(ancestors.output) }
            sourceSets["integrationTest"].compileClasspath += files(ancestorJar)
            sourceSets["integrationTest"].runtimeClasspath += files(ancestorJar)
            repositories { mavenCentral() }
            dependencies {
                testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
                "integrationTestImplementation"("org.junit.jupiter:junit-jupiter:5.10.2")
                "integrationTestRuntimeOnly"("org.junit.platform:junit-platform-launcher:1.10.2")
            }
            class EmptyWorkerClasspath : CommandLineArgumentProvider {
                override fun asArguments() = emptyList<String>()
            }
            val portalWorkerCpArgProvider = EmptyWorkerClasspath()
            """ + registration + "\nintegrationTest.configure {\n"
            + "    providers.gradleProperty(\"fixture.include\").orNull?.let { include(it) }\n"
            + "    jvmArgs(\"-Xlog:os=info:file=" + forks + "/fork-%p.log\")\n}\n");
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
            @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME)
            @java.lang.annotation.Target(java.lang.annotation.ElementType.METHOD)
            @Test @interface NativeCase {}
            class ComposedRoot { @NativeCase void composed() {} }
            class ExternalInherited extends ExternalBase {}
            class InterfaceInherited implements ExternalContract {}
            class ExternalNestedInherited extends ExternalNestedBase {}
            class JarHelper implements PlainContract { public void work() {} }
            class JdkHelper implements AutoCloseable { public void close() {} }
            """);
        write(root, "src/test/java/fixture/ExternalBase.java", "package fixture; public abstract class ExternalBase { @org.junit.jupiter.api.Test public void externalInherited() {} }\n");
        write(root, "src/ancestors/java/fixture/ExternalContract.java", "package fixture; public interface ExternalContract { @org.junit.jupiter.api.Test default void externalDefaultTest() {} }\n");
        write(root, "src/ancestors/java/fixture/PlainContract.java", "package fixture; public interface PlainContract { void work(); }\n");
        write(root, "src/test/java/fixture/ExternalNestedBase.java", "package fixture; public abstract class ExternalNestedBase { @org.junit.jupiter.api.Nested class Checks { @org.junit.jupiter.api.Test void nestedExternal() {} } }\n");
        run(root);
        equal(8, cases(root), "Direct/composed/external-inherited/nested test inventory");
        equal(7L, count(forks), "One fresh JVM per real root, none for helpers");
        run(root, "--tests", "fixture.ComposedRoot", "--tests", "fixture.ExternalInherited", "--tests", "fixture.InterfaceInherited", "--tests", "fixture.ExternalNestedInherited");
        equal(11L, count(forks), "Composed and external-inherited CLI roots must remain executable");
        equal(4, cases(root), "Composed and external-inherited CLI cases");
        run(root, "--tests", "fixture.ConcreteTest");
        equal(12L, count(forks), "CLI filter must launch only its selected root");
        equal(2, cases(root), "CLI filter must retain inherited and nested cases");
        write(root, "src/integrationTest/java/fixture/AddedTest.java", "package fixture; class AddedTest { @org.junit.jupiter.api.Test void added() {} }\n");
        run(root);
        equal(20L, count(forks), "Changed compiled input must discover the added root");
        equal(9, cases(root), "Changed input inventory");
        run(root, "-Pfixture.include=fixture/IndependentTest.class");
        equal(21L, count(forks), "Explicit file selection must not be broadened");
        equal(1, cases(root), "Explicit file filter inventory");
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
