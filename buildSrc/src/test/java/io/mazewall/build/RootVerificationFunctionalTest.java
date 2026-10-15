package io.mazewall.build;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.gradle.testkit.runner.BuildResult;
import org.gradle.testkit.runner.GradleRunner;
import org.gradle.testkit.runner.TaskOutcome;

/** Executes the actual root source-quality convention, including its real analysis plugins. */
final class RootVerificationFunctionalTest {
    private RootVerificationFunctionalTest() {}

    static void verify(Path rootBuild) {
        try {
            Path repository = rootBuild.getParent();
            Path fixture = Files.createTempDirectory("root-verification-");
            System.out.println("Root verification fixture: " + fixture);
            write(fixture, "settings.gradle.kts", """
                rootProject.name = "root-verification-fixture"
                include(":integrations:spring:java-leaf", ":integrations:spring:starter", ":integrations:koog", ":core", ":portals:leaf")
                """);
            write(fixture, "gradle/libs.versions.toml",
                Files.readString(repository.resolve("gradle/libs.versions.toml")));
            write(fixture, "config/spotbugs/exclude.xml", "<FindBugsFilter/>\n");
            write(fixture, "config/detekt/fixture.yml", """
                exceptions:
                  active: true
                  severity: error
                  NotImplementedDeclaration:
                    active: true
                """);
            String source = Files.readString(rootBuild);
            write(fixture, "build.gradle.kts", """
                import org.gradle.api.publish.PublishingExtension
                import java.util.jar.JarFile
                plugins {
                    java
                    alias(libs.plugins.kotlin) apply false
                    alias(libs.plugins.detekt)
                    alias(libs.plugins.spotbugs)
                    alias(libs.plugins.ktlint) apply false
                }
                allprojects { repositories { mavenCentral() } }
                sourceSets { create("sharedTest") }
                """ + convention(source) + """
                // Existing cross-module build scripts query Java providers before child evaluation.
                val earlyCoreJar = project(":core").tasks.named("jar")
                subprojects {
                    plugins.withId("dev.detekt") {
                        detekt {
                            config.setFrom(rootProject.files("config/detekt/fixture.yml"))
                        }
                    }
                }
                """ + aggregate(source, "unitCheck") + aggregate(source, "kernelCheck"));
            write(fixture, "integrations/spring/java-leaf/build.gradle.kts", """
                plugins { `java-library` }
                dependencies { testImplementation("junit:junit:4.13.2") }
                tasks.register("verifyCoverageSelection") {
                    doLast {
                        val unitReport = tasks.named<org.gradle.testing.jacoco.tasks.JacocoReport>("jacocoTestReport").get()
                        val combined = tasks.named<org.gradle.testing.jacoco.tasks.JacocoReport>("jacocoCombinedReport").get()
                        val unitGate = tasks.named<org.gradle.testing.jacoco.tasks.JacocoCoverageVerification>("jacocoTestCoverageVerification").get()
                        val kernelGate = tasks.named<org.gradle.testing.jacoco.tasks.JacocoCoverageVerification>("jacocoKernelCoverageVerification").get()
                        check(unitReport.classDirectories.files.isNotEmpty())
                        check(unitReport.classDirectories.files == combined.classDirectories.files)
                        check(unitReport.classDirectories.files == unitGate.classDirectories.files)
                        check(combined.classDirectories.files == kernelGate.classDirectories.files)
                        check(unitReport.executionData.files == unitGate.executionData.files)
                        check(combined.executionData.files == kernelGate.executionData.files)
                        check(unitReport.executionData.files.none { it.name.startsWith("integrationTest") })
                    }
                }
                """);
            write(fixture, "integrations/spring/starter/build.gradle.kts", "plugins { `java-library` }\n");
            write(fixture, "integrations/spring/java-leaf/src/main/java/io/mazewall/Answer.java", """
                package io.mazewall;
                public final class Answer {
                    public static int value() { return 42; }
                }
                """);
            write(fixture, "integrations/spring/java-leaf/src/test/java/io/mazewall/AnswerTest.java", """
                package io.mazewall;
                public class AnswerTest {
                    @org.junit.Test public void answers() {
                        org.junit.Assert.assertEquals(42, Answer.value());
                    }
                }
                """);
            for (String leaf : List.of("integrations/koog", "core", "portals/leaf")) {
                write(fixture, leaf + "/build.gradle.kts", "plugins {\n    alias(libs.plugins.kotlin)\n    alias(libs.plugins.ktlint)\n}\n");
                write(fixture, leaf + "/src/main/kotlin/io/mazewall/Answer.kt", """
                    package io.mazewall

                    object Answer {
                        fun value(): Int = 42
                    }
                    """);
            }
            write(fixture, "portals/build.gradle.kts", "plugins { base }\n");

            BuildResult selected = run(fixture, false,
                ":integrations:build", ":core:build", ":portals:build", ":unitCheck", ":kernelCheck",
                ":integrations:spring:java-leaf:verifyCoverageSelection");
            assertQuality(selected);
            Path combined = fixture.resolve("integrations/spring/java-leaf/build/reports/jacoco/jacocoCombinedReport/jacocoCombinedReport.xml");
            String combinedXml = Files.readString(combined);
            if (!combinedXml.contains("name=\"io/mazewall/Answer\"")) {
                throw new AssertionError("Custom combined report lost executable main denominator: " + combinedXml);
            }
            Path leafBuild = fixture.resolve("integrations/spring/java-leaf/build.gradle.kts");
            String passingBuild = Files.readString(leafBuild);
            Files.writeString(leafBuild, """
                tasks.named<org.gradle.testing.jacoco.tasks.JacocoCoverageVerification>("jacocoKernelCoverageVerification") {
                    violationRules { rule { limit { minimum = "0.99".toBigDecimal() } } }
                }
                """, java.nio.file.StandardOpenOption.APPEND);
            BuildResult coverageFailure = run(fixture, true,
                ":integrations:spring:java-leaf:jacocoKernelCoverageVerification");
            outcome(coverageFailure, ":integrations:spring:java-leaf:jacocoKernelCoverageVerification", TaskOutcome.FAILED);
            if (!coverageFailure.getOutput().contains("instructions covered ratio")) {
                throw new AssertionError("Expected genuine inadequate coverage rejection: " + coverageFailure.getOutput());
            }
            Files.writeString(leafBuild, passingBuild);
            Files.writeString(leafBuild, """
                tasks.named<org.gradle.testing.jacoco.tasks.JacocoCoverageVerification>("jacocoTestCoverageVerification") {
                    violationRules { rule { limit { minimum = "0.99".toBigDecimal() } } }
                }
                """, java.nio.file.StandardOpenOption.APPEND);
            BuildResult hostCoverageFailure = run(fixture, true, ":unitCheck");
            outcome(hostCoverageFailure, ":integrations:spring:java-leaf:jacocoTestCoverageVerification", TaskOutcome.FAILED);
            Files.writeString(leafBuild, passingBuild);
            // Grouping projects never acquire fake source sets, analyzers or coverage gates.
            for (String group : List.of(":integrations", ":integrations:spring", ":portals")) {
                for (String task : List.of("compileJava", "test", "spotbugsMain", "detektMain", "jacocoTestReport")) {
                    absent(selected, group + ":" + task);
                }
            }
            absent(selected, ":integrations:spring:java-leaf:detektMain");
            absent(selected, ":integrations:spring:starter:detektMain");
            outcome(selected, ":integrations:spring:starter:spotbugsMain", TaskOutcome.NO_SOURCE);
            if (selected.task(":integrations:spring:starter:check") == null) {
                throw new AssertionError("Lost source-free Java starter check");
            }

            write(fixture, "integrations/spring/java-leaf/src/main/java/io/mazewall/Broken.java", """
                package io.mazewall;
                public final class Broken {
                    public static String value() {
                        Object value = null;
                        return value.toString();
                    }
                }
                """);
            BuildResult javaFailure = run(fixture, true, ":unitCheck");
            outcome(javaFailure, ":integrations:spring:java-leaf:spotbugsMain", TaskOutcome.FAILED);
            Files.delete(fixture.resolve("integrations/spring/java-leaf/src/main/java/io/mazewall/Broken.java"));

            Path javaTest = fixture.resolve("integrations/spring/java-leaf/src/test/java/io/mazewall/AnswerTest.java");
            String passingTest = Files.readString(javaTest);
            Files.writeString(javaTest, passingTest.replace("assertEquals(42,", "assertEquals(0,"));
            BuildResult testFailure = run(fixture, true, ":unitCheck");
            outcome(testFailure, ":integrations:spring:java-leaf:test", TaskOutcome.FAILED);
            Files.writeString(javaTest, passingTest);

            write(fixture, "integrations/koog/src/main/kotlin/io/mazewall/Broken.kt", """
                package io.mazewall

                fun broken(): Int {
                    TODO("unfinished implementation")
                }
                """);
            BuildResult kotlinFailure = run(fixture, true, ":unitCheck");
            outcome(kotlinFailure, ":integrations:koog:detektMain", TaskOutcome.FAILED);
            Files.delete(fixture.resolve("integrations/koog/src/main/kotlin/io/mazewall/Broken.kt"));
            write(fixture, "core/src/main/kotlin/io/mazewall/Answer.kt", "package io.mazewall\nobject Answer{fun value():Int=42}\n");
            BuildResult styleFailure = run(fixture, true, ":unitCheck");
            outcome(styleFailure, ":core:ktlintMainSourceSetCheck", TaskOutcome.FAILED);
            System.out.println("Root verification: implicit/explicit groups, Java tests/SpotBugs, Kotlin Detekt, core and lazy aggregates passed.");
        } catch (Exception failure) {
            throw new AssertionError("Production root verification wiring failed", failure);
        }
    }

    private static void assertQuality(BuildResult result) {
        outcome(result, ":integrations:spring:java-leaf:test", TaskOutcome.SUCCESS);
        for (String leaf : List.of(":integrations:spring:java-leaf", ":integrations:koog", ":core", ":portals:leaf")) {
            outcome(result, leaf + ":spotbugsMain", TaskOutcome.SUCCESS);
            for (String task : List.of("build", "check", "unitCheck", "kernelCheck")) {
                if (result.task(leaf + ":" + task) == null) {
                    throw new AssertionError("Lost real child gate: " + leaf + ":" + task);
                }
            }
        }
        for (String leaf : List.of(":integrations:koog", ":core", ":portals:leaf")) {
            outcome(result, leaf + ":detektMain", TaskOutcome.SUCCESS);
        }
    }

    /** Real failed Tests, extracted root wiring, and the production collector, including CC reuse. */
    static void verifyFailureTriage(Path rootBuild) {
        try {
            Path repository = rootBuild.getParent();
            Path fixture = Files.createTempDirectory("root-failure-triage-");
            System.out.println("Root failure triage fixture: " + fixture);
            write(fixture, "settings.gradle.kts", "rootProject.name = \"failure-triage-fixture\"\ninclude(\":leaf\", \":peer\", \":unused\", \":profiler\")\n");
            write(fixture, "gradle.properties", "org.gradle.caching=true\n");
            write(fixture, "gradle/libs.versions.toml", Files.readString(repository.resolve("gradle/libs.versions.toml")));
            write(fixture, "buildSrc/build.gradle", "plugins { id 'java-gradle-plugin' }\n");
            write(fixture, "buildSrc/src/main/java/io/mazewall/build/TestFailureTriage.java",
                Files.readString(repository.resolve("buildSrc/src/main/java/io/mazewall/build/TestFailureTriage.java")));
            write(fixture, "buildSrc/src/main/java/io/mazewall/build/TriageArguments.java",
                Files.readString(repository.resolve("buildSrc/src/main/java/io/mazewall/build/TriageArguments.java")));
            String source = Files.readString(rootBuild);
            int start = source.indexOf("\nval triageRuntimeClasspath =");
            int end = source.indexOf("\n// Resolves the hooks directory", start);
            if (start < 0 || end < 0) throw new AssertionError("Missing production triage wiring");
            String build = """
                import io.mazewall.build.TriageArguments
                plugins { java; alias(libs.plugins.kotlin) apply false }
                allprojects { repositories { mavenCentral() } }
                tasks.register("nonTestFailure") { doLast { throw GradleException("CONTROLLED_NON_TEST_FAILURE") } }
                """ + source.substring(start, end);
            write(fixture, "build.gradle.kts", build);
            write(fixture, "profiler/build.gradle.kts", """
                plugins { alias(libs.plugins.kotlin) }
                dependencies { implementation(libs.kotlinxSerialization) }
                """);
            Path triageSources = repository.resolve("profiler/src/main/kotlin/io/mazewall/profiler/triage");
            try (var files = Files.list(triageSources)) {
                for (Path file : files.filter(path -> path.toString().endsWith(".kt")).toList()) {
                    write(fixture, "profiler/src/main/kotlin/io/mazewall/profiler/triage/" + file.getFileName(), Files.readString(file));
                }
            }
            write(fixture, "leaf/build.gradle.kts", """
                plugins { java }
                dependencies { testImplementation("junit:junit:4.13.2") }
                tasks.test {
                    val modeFile = layout.projectDirectory.file("mode.txt").asFile
                    inputs.file(modeFile)
                    systemProperty("modeFile", modeFile.absolutePath)
                    onlyIf {
                        if (modeFile.readText() == "predicate") throw GradleException("CONTROLLED_TEST_PREDICATE_FAILURE")
                        true
                    }
                    doFirst {
                        if (modeFile.readText() == "prexml") throw GradleException("CONTROLLED_TEST_PRE_XML_FAILURE")
                    }
                }
                """);
            write(fixture, "leaf/src/test/java/ControlledTest.java", """
                public class ControlledTest {
                    @org.junit.Test public void controlled() throws Exception {
                        String mode = java.nio.file.Files.readString(java.nio.file.Path.of(System.getProperty("modeFile")));
                        if (mode.equals("crash")) Runtime.getRuntime().halt(17);
                        org.junit.Assert.assertEquals("pass", mode);
                    }
                }
                """);
            write(fixture, "peer/build.gradle.kts", "plugins { java }\ndependencies { testImplementation(\"junit:junit:4.13.2\") }\n");
            write(fixture, "peer/src/test/java/PeerTest.java", """
                public class PeerTest {
                    @org.junit.Test public void succeeds() throws Exception { Thread.sleep(300); }
                }
                """);
            write(fixture, "unused/build.gradle.kts", "plugins { java }\n");

            write(fixture, "leaf/mode.txt", "fail");
            BuildResult first = triageRun(fixture, "cc-first", true, true, false, ":leaf:test");
            xmlCounts(fixture, 1, 1);
            contains(first, "Configuration cache entry stored.");
            BuildResult reused = triageRun(fixture, "cc-reuse", true, true, false, ":leaf:test");
            xmlCounts(fixture, 1, 1);
            contains(reused, "Reusing configuration cache.");

            // Stale results from an unselected Test must not trigger this invocation's collector.
            write(fixture, "unused/build/test-results/test/TEST-stale.xml", "<testsuite tests=\"1\" failures=\"1\"/>\n");
            write(fixture, "leaf/mode.txt", "pass");
            BuildResult success = triageRun(fixture, "success-after-failure", false, false, false, ":leaf:test");
            xmlCounts(fixture, 1, 0);
            contains(success, "Reusing configuration cache.");
            BuildResult unchanged = triageRun(fixture, "up-to-date-with-stale-xml", false, false, false, ":leaf:test");
            outcome(unchanged, ":leaf:test", TaskOutcome.UP_TO_DATE);
            contains(unchanged, "Reusing configuration cache.");
            BuildResult noSource = triageRun(fixture, "no-source-with-stale-xml", false, false, false, ":unused:test");
            outcome(noSource, ":unused:test", TaskOutcome.NO_SOURCE);

            for (String mode : List.of("prexml", "predicate", "crash")) {
                Path xml = fixture.resolve("leaf/build/test-results/test/TEST-ControlledTest.xml");
                Files.deleteIfExists(xml);
                write(fixture, "leaf/mode.txt", mode);
                BuildResult failed = triageRun(fixture, mode, true, true, false, ":leaf:test");
                contains(failed, "Reusing configuration cache.");
                if (mode.equals("crash")) {
                    contains(failed, "non-zero exit value 17");
                } else {
                    contains(failed, mode.equals("prexml") ? "CONTROLLED_TEST_PRE_XML_FAILURE" : "CONTROLLED_TEST_PREDICATE_FAILURE");
                    if (Files.exists(xml)) throw new AssertionError("Pre-XML failure unexpectedly published Test XML");
                }
            }
            write(fixture, "leaf/mode.txt", "fail");
            // Require a current successful sibling event, even with a populated shared build cache.
            BuildResult siblings = triageRun(fixture, "parallel-siblings", true, true, true, ":leaf:test", ":peer:test", "--rerun");
            outcome(siblings, ":peer:test", TaskOutcome.SUCCESS);
            BuildResult nonTest = triageRun(fixture, "non-test-failure", true, false, true, ":nonTestFailure", ":runTriage");
            contains(nonTest, "CONTROLLED_NON_TEST_FAILURE");

            // Force event delivery to lag behind the barrier task, using the production handler.
            write(fixture, "buildSrc/src/main/java/io/mazewall/build/DelayedFailureEvents.java", """
                package io.mazewall.build;
                public abstract class DelayedFailureEvents extends TestFailureTriage.FailureEvents {
                    private final java.util.concurrent.CountDownLatch waiterAcquired = new java.util.concurrent.CountDownLatch(1);
                    private volatile Thread waiter;

                    @Override public boolean awaitFailure() {
                        waiter = Thread.currentThread();
                        waiterAcquired.countDown();
                        return super.awaitFailure();
                    }

                    public static void awaitActualWait(Thread target, long timeoutMillis) {
                        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
                        do {
                            StackTraceElement[] stack = target.getStackTrace();
                            boolean productionWait = java.util.Arrays.stream(stack).anyMatch(frame ->
                                frame.getClassName().equals(TestFailureTriage.FailureEvents.class.getName())
                                    && frame.getMethodName().equals("awaitFailure"));
                            boolean latchWait = java.util.Arrays.stream(stack).anyMatch(frame ->
                                frame.getClassName().equals("java.util.concurrent.CountDownLatch")
                                    && frame.getMethodName().equals("await"));
                            if (target.getState() == Thread.State.TIMED_WAITING && productionWait && latchWait) {
                                System.out.println("TRIAGE_ACTUAL_WAIT_ENTERED thread=" + target.threadId()
                                    + " state=TIMED_WAITING stack=" + java.util.Arrays.toString(stack));
                                return;
                            }
                            try { Thread.sleep(10); }
                            catch (InterruptedException failure) {
                                Thread.currentThread().interrupt();
                                throw new org.gradle.api.GradleException("Interrupted awaiting fixture actual wait entry", failure);
                            }
                        } while (System.nanoTime() < deadline);
                        throw new org.gradle.api.GradleException("FIXTURE_HANDSHAKE_TIMEOUT: no production latch wait on acquired thread "
                            + target.threadId() + " state=" + target.getState());
                    }

                    @Override public void onFinish(org.gradle.tooling.events.FinishEvent event) {
                        if (event instanceof org.gradle.tooling.events.task.TaskFinishEvent task
                                && task.getDescriptor().getTaskPath().equals(":leaf:test")) {
                            try {
                                if (!waiterAcquired.await(10, java.util.concurrent.TimeUnit.SECONDS)) {
                                    throw new org.gradle.api.GradleException("FIXTURE_HANDSHAKE_TIMEOUT: finalizer thread not acquired");
                                }
                            } catch (InterruptedException failure) {
                                Thread.currentThread().interrupt();
                                throw new org.gradle.api.GradleException("Interrupted acquiring fixture finalizer thread", failure);
                            }
                            // Withhold this event and the serial barrier event until the real production latch blocks.
                            awaitActualWait(waiter, 10_000);
                            System.out.println("DELAYED_TEST_EVENT_DELIVERED");
                        }
                        super.onFinish(event);
                    }
                }
                """);
            write(fixture, "build.gradle.kts", build.replace("\nval triageRuntimeClasspath =", """

                gradle.sharedServices.registerIfAbsent("mazewallTestFailures", io.mazewall.build.DelayedFailureEvents::class.java) {
                    parameters.testTaskPaths.set(listOf(":leaf:test", ":peer:test", ":unused:test", ":profiler:test"))
                    parameters.barrierTaskPath.set(":testFailureEventsReady")
                }
                val triageRuntimeClasspath =""") + """

                tasks.named("testFailureEventsReady") { doLast { println("TRIAGE_BARRIER_EXECUTED") } }
                tasks.register("rejectHandshakeTimeout") {
                    doLast {
                        println("FIXTURE_PRE_AWAIT_MARKER")
                        io.mazewall.build.DelayedFailureEvents.awaitActualWait(Thread.currentThread(), 100)
                    }
                }
                """);
            // Compile the collector against the changed fixture buildSrc before withholding Test events.
            run(fixture, false, ":profiler:jar");
            for (String label : List.of("delayed-cc-first", "delayed-cc-reuse", "delayed-no-cc")) {
                BuildResult delayed = label.equals("delayed-no-cc")
                    ? triageRun(fixture, label, true, true, false, ":leaf:test", "--no-configuration-cache")
                    : triageRun(fixture, label, true, true, false, ":leaf:test");
                if (!label.equals("delayed-no-cc")) {
                    contains(delayed, label.endsWith("first") ? "Configuration cache entry stored." : "Reusing configuration cache.");
                }
                xmlCounts(fixture, 1, 1);
                String output = delayed.getOutput();
                int barrier = output.indexOf("TRIAGE_BARRIER_EXECUTED");
                int waiting = output.indexOf("TRIAGE_ACTUAL_WAIT_ENTERED");
                int delivered = output.indexOf("DELAYED_TEST_EVENT_DELIVERED");
                int collector = output.indexOf("==> Initiating mazewall local diagnostic triage...");
                if (!(barrier >= 0 && waiting > barrier && delivered > waiting && collector > delivered)) {
                    throw new AssertionError("Did not exercise/drain asynchronous Test completion before triage: " + fixture);
                }
            }
            BuildResult handshakeTimeout = triageRun(fixture, "handshake-timeout", true, true, false,
                ":leaf:test", ":rejectHandshakeTimeout", "--continue");
            outcome(handshakeTimeout, ":rejectHandshakeTimeout", TaskOutcome.FAILED);
            contains(handshakeTimeout, "FIXTURE_PRE_AWAIT_MARKER");
            contains(handshakeTimeout, "FIXTURE_HANDSHAKE_TIMEOUT: no production latch wait on acquired thread");
            xmlCounts(fixture, 1, 1);
            System.out.println("Failure triage: 14 executable cases passed; production JSON, cached failure/success/staleness, pre-XML/crash, siblings, actual wait-entry delivery and explicit handshake timeout.");
        } catch (Exception failure) {
            throw new AssertionError("Production failure-triage wiring failed", failure);
        }
    }

    private static BuildResult triageRun(Path fixture, String label, boolean fail, boolean triage, boolean parallel, String... tasks) throws Exception {
        Path report = fixture.resolve("build/triage_report.json");
        Files.deleteIfExists(report);
        var arguments = new java.util.ArrayList<>(List.of(tasks));
        if (!arguments.contains("--no-configuration-cache")) arguments.add("--configuration-cache");
        arguments.addAll(List.of("--max-workers=2", "--stacktrace"));
        if (parallel) arguments.addAll(List.of("--parallel", "--continue"));
        String home = System.getenv("GRADLE_USER_HOME");
        if (home != null) arguments.addAll(List.of("--gradle-user-home", home));
        write(fixture, "evidence/" + label + ".argv", String.join("\n", arguments) + "\n");
        GradleRunner runner = GradleRunner.create().withProjectDir(fixture.toFile()).withArguments(arguments);
        BuildResult result = fail ? runner.buildAndFail() : runner.build();
        write(fixture, "evidence/" + label + ".log", result.getOutput());
        contains(result, fail ? "BUILD FAILED" : "BUILD SUCCESSFUL");
        outcome(result, ":runTriage", triage ? TaskOutcome.SUCCESS : TaskOutcome.SKIPPED);
        if (List.of(tasks).contains(":leaf:test")) {
            if (fail) outcome(result, ":leaf:test", TaskOutcome.FAILED);
            else if (result.task(":leaf:test") == null || !List.of(TaskOutcome.SUCCESS, TaskOutcome.UP_TO_DATE)
                    .contains(result.task(":leaf:test").getOutcome())) {
                throw new AssertionError("Controlled successful Test did not succeed");
            }
        }
        if (triage) {
            contains(result, "==> Local diagnostic triage complete.");
            var json = (java.util.Map<?, ?>) new groovy.json.JsonSlurper().parse(report.toFile());
            var diagnostics = (java.util.Map<?, ?>) json.get("diagnostics");
            if (!(json.get("timestamp") instanceof Number) || diagnostics.size() != 5
                    || !String.valueOf(diagnostics.get("jvm_thread_dump")).contains("Self snapshot: collector PID")) {
                throw new AssertionError("Missing real production diagnostic evidence: " + report);
            }
            Files.copy(report, fixture.resolve("evidence/" + label + ".triage.json"));
        } else if (Files.exists(report) || result.getOutput().contains("==> Initiating mazewall local diagnostic triage...")) {
            throw new AssertionError("Unexpected automatic diagnostics for " + label);
        }
        Path xml = fixture.resolve("leaf/build/test-results/test/TEST-ControlledTest.xml");
        if (Files.exists(xml)) Files.copy(xml, fixture.resolve("evidence/" + label + ".xml"));
        write(fixture, "evidence/" + label + ".result", "build=" + (fail ? "FAILED" : "SUCCESSFUL")
            + "\ntriage=" + result.task(":runTriage").getOutcome() + "\n");
        System.out.println("Failure triage " + label + ": " + (fail ? "failed build retained" : "successful build")
            + ", collector " + result.task(":runTriage").getOutcome());
        return result;
    }

    private static void xmlCounts(Path fixture, int tests, int failures) throws Exception {
        var document = javax.xml.parsers.DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(fixture.resolve("leaf/build/test-results/test/TEST-ControlledTest.xml").toFile());
        var suite = document.getDocumentElement();
        if (!suite.getAttribute("tests").equals(String.valueOf(tests))
                || !suite.getAttribute("failures").equals(String.valueOf(failures)) || !suite.getAttribute("skipped").equals("0")) {
            throw new AssertionError("Unexpected controlled Test XML counts");
        }
    }

    private static void contains(BuildResult result, String text) {
        if (!result.getOutput().contains(text)) throw new AssertionError("Missing Gradle evidence: " + text + "\n" + result.getOutput());
    }

    private static void outcome(BuildResult result, String task, TaskOutcome expected) {
        if (result.task(task) == null || result.task(task).getOutcome() != expected) {
            throw new AssertionError("Expected " + expected + " for " + task + "\n" + result.getOutput());
        }
    }

    private static void absent(BuildResult result, String task) {
        if (result.task(task) != null) throw new AssertionError("Fabricated source-module gate: " + task);
    }

    private static BuildResult run(Path fixture, boolean fail, String... tasks) {
        var arguments = new java.util.ArrayList<>(List.of(tasks));
        arguments.addAll(List.of("--no-configuration-cache", "--max-workers=2", "--stacktrace"));
        GradleRunner runner = GradleRunner.create().withProjectDir(fixture.toFile()).withArguments(arguments).forwardOutput();
        return fail ? runner.buildAndFail() : runner.build();
    }

    private static String convention(String source) {
        int start = source.indexOf("\nsubprojects {", source.indexOf("\ndetekt {"));
        int end = source.indexOf("\n// The root shared-test source set", start);
        if (start < 0 || end < 0) throw new AssertionError("Missing production source-quality convention");
        return source.substring(start, end);
    }

    private static String aggregate(String source, String task) {
        int start = source.lastIndexOf("\ntasks.register(\"" + task + "\") {");
        int end = source.indexOf("\n}", start);
        if (start < 0 || end < 0) throw new AssertionError("Missing production aggregate: " + task);
        return source.substring(start, end + 2) + "\n";
    }

    private static void write(Path root, String name, String content) throws Exception {
        Path target = root.resolve(name);
        Files.createDirectories(target.getParent());
        Files.writeString(target, content, StandardCharsets.UTF_8);
    }
}
