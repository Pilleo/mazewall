package io.mazewall.build;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.gradle.testkit.runner.BuildResult;
import org.gradle.testkit.runner.GradleRunner;

/** Executes actual CI selectors and harness declarations: no implicit demos, duplicated cases or missing heavy lane. */
public final class CoreLaneFunctionalTest {
    private CoreLaneFunctionalTest() {}

    public static void main(String[] args) throws Exception { verify(Path.of(args[0])); }

    static void verify(Path repository) throws Exception {
        String workflow = Files.readString(repository.resolve(".github/workflows/ci.yml"));
        String source = Files.readString(repository.resolve("build.gradle.kts"));
        List<String> core = arguments(step(workflow, "Run Host-Safe Unit Quality Gate"));
        Path root = Files.createTempDirectory("core-lane-");
        write(root, "settings.gradle.kts", "rootProject.name=\"lane-fixture\"\ninclude(\":demos:vulnerable-web-app\")\n");
        write(root, "demos/vulnerable-web-app/build.gradle.kts", "tasks.register(\"unitCheck\") { doLast { println(\"DEMO_HOST_GATE_OK\") } }\n");
        String harness = source.substring(source.indexOf("tasks.register<Test>(\"consumerHarnessTest\")"), source.indexOf("tasks.register<Test>(\"consumerCheck\")"));
        write(root, "build.gradle.kts", """
            plugins { java }
            val consumerVerification = sourceSets.create("consumerVerification")
            repositories { mavenCentral() }
            dependencies {
                "consumerVerificationImplementation"("org.junit.jupiter:junit-jupiter:5.10.2")
                "consumerVerificationRuntimeOnly"("org.junit.platform:junit-platform-launcher:1.10.2")
            }
            tasks.withType<Test>().configureEach {
                systemProperty("fixture.effects", layout.projectDirectory.dir("effects").asFile.absolutePath)
                systemProperty("fixture.task", name)
            }
            tasks.register("buildLogicCheck")
            """ + harness + AggregateVerificationFunctionalTest.declaration(source, "unitCheck")
            + "\ntasks.named(\"unitCheck\") { dependsOn(\"consumerHarnessTest\") }\n");
        for (String name : List.of("FastTest", "HeavyTest", "NativeTest")) {
            String tag = name.equals("HeavyTest") ? "@org.junit.jupiter.api.Tag(\"build-logic\")" : name.equals("NativeTest") ? "@org.junit.jupiter.api.Tag(\"consumer-native\")" : "";
            write(root, "src/consumerVerification/java/fixture/" + name + ".java", """
                package fixture;
                %s public class %s {
                    @org.junit.jupiter.api.Test void records() throws Exception {
                        if (getClass().getSimpleName().equals("NativeTest")) throw new AssertionError("Native task escaped its gate");
                        var path = java.nio.file.Path.of(System.getProperty("fixture.effects"), System.getProperty("fixture.task") + "-" + getClass().getSimpleName());
                        java.nio.file.Files.createDirectories(path.getParent());
                        java.nio.file.Files.writeString(path, "executed" + System.lineSeparator(), java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
                    }
                }
                """.formatted(tag, name));
        }
        BuildResult host = run(root, core);
        require(host.task(":demos:vulnerable-web-app:unitCheck") == null, "Bare CI selector executed demo work in Core");
        require(Files.readString(root.resolve("effects/consumerHarnessTest-FastTest")).equals("executed\n"), "Fast harness did not execute once");
        require(!Files.exists(root.resolve("effects/consumerHarnessTest-HeavyTest")), "Heavy build fixture remained in Core");
        BuildResult buildLogic = run(root, arguments(step(workflow, "Run Build Logic Verification")));
        require(buildLogic.task(":consumerBuildLogicTest") != null, "Build Logic omitted required consumer regressions");
        require(Files.readString(root.resolve("effects/consumerBuildLogicTest-HeavyTest")).equals("executed\n"), "Heavy case missing or duplicated");
        require(!Files.exists(root.resolve("effects/consumerBuildLogicTest-FastTest")), "Fast case duplicated in Build Logic");
        String demo = step(workflow, "Run Demo Host Quality Gate");
        BuildResult demoResult = run(root, arguments(demo));
        require(demoResult.getOutput().contains("DEMO_HOST_GATE_OK"), "Explicit demo gate disappeared");
        String condition = demo.lines().map(String::trim).filter(s -> s.startsWith("if: ")).findFirst().orElse("if: success()");
        condition = condition.substring(4).replace("${{", "").replace("}}", "").trim();
        for (boolean cancelled : List.of(false, true)) for (boolean priorSuccess : List.of(false, true)) {
            String expression = condition.replace("!cancelled()", String.valueOf(!cancelled))
                .replace("cancelled()", String.valueOf(cancelled)).replace("success()", String.valueOf(priorSuccess));
            int exit = new ProcessBuilder("bash", "-c", expression).start().waitFor();
            require((exit == 0) == !cancelled, "Demo gate depends on scanner success/eligibility instead of cancellation only");
        }
        System.out.println("CORE_LANES_OK fast/heavy cases execute once in their owners; explicit demo survives scanner failure");
    }

    private static String step(String workflow, String name) {
        String marker = "            - name: " + name + "\n";
        int start = workflow.indexOf(marker);
        if (start < 0) throw new AssertionError("Required CI step missing: " + name);
        int end = workflow.indexOf("\n            - name:", start + marker.length());
        return workflow.substring(start + marker.length(), end < 0 ? workflow.length() : end);
    }

    private static List<String> arguments(String step) {
        String line = step.lines().filter(s -> s.contains("./gradlew ")).findFirst().orElseThrow();
        String invocation = line.substring(line.indexOf("./gradlew ") + "./gradlew ".length()).split("2>&1", 2)[0].trim();
        var arguments = new ArrayList<>(List.of(invocation.split("\\s+")));
        // TestKit's Tooling API owns daemon lifecycle; retain the actual selectors and gate flags.
        arguments.remove("--no-daemon");
        if (arguments.stream().noneMatch(s -> s.startsWith("--max-workers"))) arguments.add("--max-workers=1");
        return arguments;
    }

    private static BuildResult run(Path root, List<String> arguments) {
        return GradleRunner.create().withProjectDir(root.toFile()).withArguments(arguments).build();
    }

    private static void require(boolean value, String message) { if (!value) throw new AssertionError(message); }

    private static void write(Path root, String name, String contents) throws Exception {
        Path file = root.resolve(name); Files.createDirectories(file.getParent()); Files.writeString(file, contents);
    }
}
