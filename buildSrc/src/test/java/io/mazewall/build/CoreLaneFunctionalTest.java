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
        write(root, "settings.gradle.kts", "rootProject.name=\"lane-fixture\"\ninclude(\":demos:vulnerable-web-app\", \":platform\", \":enforcer\", \":profiler\", \":portals:codegen\")\n");
        write(root, "demos/vulnerable-web-app/build.gradle.kts", "tasks.register(\"unitCheck\") { doLast { println(\"DEMO_HOST_GATE_OK\") } }\n");
        for (String module : List.of("platform", "enforcer", "profiler", "portals/codegen")) {
            write(root, module + "/build.gradle.kts", "plugins { java }\n");
            String name = module.replace('/', '_');
            write(root, module + "/src/main/java/" + name + ".java", "public class " + name + " {}\n");
        }
        String harness = source.substring(source.indexOf("tasks.register<Test>(\"consumerHarnessTest\")"), source.indexOf("tasks.register<Test>(\"consumerCheck\")"));
        String consumerModel = source.substring(source.indexOf("val consumerVerification ="), source.indexOf("\ntasks.named<com.github.spotbugs.snom.SpotBugsTask>(\"spotbugsConsumerVerification\")"));
        consumerModel = String.join("\n", consumerModel.lines().filter(line -> !line.trim().startsWith("kotlin.srcDir(")).toList());
        write(root, "build.gradle.kts", """
            plugins { java }
            sourceSets.create("sharedTest")
            """ + consumerModel + """

            repositories { mavenCentral() }
            dependencies {
                "sharedTestImplementation"(project(":platform"))
                "sharedTestImplementation"(project(":enforcer"))
                "sharedTestImplementation"("org.junit.jupiter:junit-jupiter-api:5.10.2")
                "sharedTestImplementation"("org.junit.platform:junit-platform-launcher:1.10.2")
                testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
                testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.10.2")
                testRuntimeOnly(project(":profiler"))
                testRuntimeOnly(project(":portals:codegen"))
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
        require(host.task(":profiler:compileJava") != null && host.task(":portals:codegen:compileJava") != null, "Architecture runtime must retain its producer dependencies");
        require(host.task(":demos:vulnerable-web-app:unitCheck") == null, "Bare CI selector executed demo work in Core");
        require(Files.readString(root.resolve("effects/consumerHarnessTest-FastTest")).equals("executed\n"), "Fast harness did not execute once");
        require(!Files.exists(root.resolve("effects/consumerHarnessTest-HeavyTest")), "Heavy build fixture remained in Core");
        run(root, arguments(step(workflow, "Run Build Logic Verification")));
        BuildResult buildLogic = run(root, arguments(step(workflow, "Run Consumer Build Logic Regressions")));
        require(buildLogic.task(":profiler:compileJava") == null && buildLogic.task(":portals:codegen:compileJava") == null, "Consumer harness selected unrelated architecture producers");
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
        verifySupervisionBoundary(workflow, root);
        System.out.println("CORE_LANES_OK fast/heavy cases execute once in their owners; explicit demo survives scanner failure");
    }

    private static void verifySupervisionBoundary(String workflow, Path root) throws Exception {
        write(root, "gradlew", """
            #!/bin/bash
            set -eu
            mkdir -p effects
            for arg in "$@"; do
                case "$arg" in
                    :buildLogicCheck)
                        test "$MAZEWALL_TEST_INSIDE_PROBE" = 1
                        echo verifier:owned >> effects/supervision ;;
                    :consumerBuildLogicTest)
                        test "$MAZEWALL_TEST_INSIDE_PROBE" = 0 || { echo 'Nested lifetime owners' >&2; exit 73; }
                        echo consumer:independent >> effects/supervision ;;
                esac
            done
            """);
        write(root, "probe.sh", """
            #!/bin/bash
            set -eu
            while [[ "$1" != -- ]]; do shift; done
            shift
            export MAZEWALL_TEST_INSIDE_PROBE=1
            exec "$@"
            """);
        root.resolve("gradlew").toFile().setExecutable(true);
        root.resolve("probe.sh").toFile().setExecutable(true);
        var steps = new ArrayList<>(List.of("Run Build Logic Verification"));
        if (workflow.contains("- name: Run Consumer Build Logic Regressions\n")) steps.add("Run Consumer Build Logic Regressions");
        for (String name : steps) {
            String body = step(workflow, name);
            body = body.substring(body.indexOf("run: ") + 5);
            body = body.startsWith("|") ? body.substring(body.indexOf('\n') + 1) : body.strip();
            body = body.replace("python3 scripts/ci_resource_probe.py", root.resolve("probe.sh").toString());
            Path log = root.resolve("supervision.log");
            var command = new ProcessBuilder("bash", "-euc", body).directory(root.toFile()).redirectErrorStream(true).redirectOutput(log.toFile());
            command.environment().put("MAZEWALL_TEST_INSIDE_PROBE", "0");
            Process child = command.start();
            try {
                require(child.waitFor(30, java.util.concurrent.TimeUnit.SECONDS), "Supervision fixture timed out");
                require(child.exitValue() == 0, "Consumer process fixtures must not be nested under another lifetime owner: " + Files.readString(log));
            } finally {
                if (child.isAlive()) child.destroyForcibly();
                require(child.waitFor(5, java.util.concurrent.TimeUnit.SECONDS), "Supervision fixture did not retire");
            }
        }
        require(Files.readAllLines(root.resolve("effects/supervision")).equals(List.of("verifier:owned", "consumer:independent")), "Both supervision phases must remain required and serial");
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
