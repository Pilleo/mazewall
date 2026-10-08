package io.mazewall.build;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import javax.tools.ToolProvider;

/** Dependency-free executable checks for our warning inventory contract. */
public final class VerificationActionsTest {
    public static void main(String[] args) {
        Path successMarker = requireSuccessMarker(args);
        String xml = "<file name=\"module/Example.kt\">\n"
            + "<error line=\"12\" column=\"4\" severity=\"warning\" message=\"first\nsecond\" source=\"detekt.Example\" />\n</file>";
        List<String> normalized = DetektWarningRatchetAction.normalize(xml.lines().toList());
        equal(List.of("module/Example.kt|<error severity=\"warning\" message=\"first second\" source=\"detekt.Example\" />"), normalized);
        equal(normalized, DetektWarningRatchetAction.normalize(xml.replace("12", "90").replace("4", "20").lines().toList()));
        equal(List.of("duplicate"), DetektWarningRatchetAction.subtract(List.of("duplicate", "duplicate"), List.of("duplicate")));
        equal(List.of("added"), DetektWarningRatchetAction.subtract(List.of("same", "added"), List.of("same", "removed")));
        equal(List.of("removed"), DetektWarningRatchetAction.subtract(List.of("same", "removed"), List.of("same", "added")));
        equal(List.of(), DetektWarningRatchetAction.normalize(List.of("<file name=\"f.kt\">", "<error severity=\"error\" />", "</file>")));
        boolean malformedRejected = false;
        try {
            DetektWarningRatchetAction.normalize(List.of("<checkstyle><file name=\"truncated.kt\">"));
        } catch (IllegalArgumentException expected) {
            malformedRejected = true;
        }
        equal(true, malformedRejected);
        verifiesFreshJvmClassMetadata();
        verifiesBacklogContracts();
        verifiesTestHealthAggregation();
        TestHealthPluginFunctionalTest.verify();
        TierEPhaseReplayTaskFunctionalTest.verify();
        TierENativeBuildTaskFunctionalTest.verify();
        AggregateVerificationFunctionalTest.verify(
            successMarker.getParent().getParent().getParent().getParent().resolve("build.gradle.kts"));
        RootVerificationFunctionalTest.verify(
            successMarker.getParent().getParent().getParent().getParent().resolve("build.gradle.kts"));
        writeSuccessMarker(successMarker);
        System.out.println("Verification actions: multiline findings, position invariance, duplicate additions, removals, and severity filtering passed.");
    }

    private static Path requireSuccessMarker(String[] args) {
        if (args.length != 1 || args[0].isBlank()) {
            throw new AssertionError("verificationActionsTest requires one success-marker path");
        }
        return Path.of(args[0]);
    }

    private static void writeSuccessMarker(Path marker) {
        try {
            Files.createDirectories(marker.getParent());
            Path temporary = Files.createTempFile(marker.getParent(), marker.getFileName().toString(), ".tmp");
            Files.writeString(temporary, "verified\n", StandardCharsets.UTF_8);
            try {
                Files.move(temporary, marker, java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            } catch (java.nio.file.AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, marker, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Exception failure) {
            throw new AssertionError("Cannot publish verification-actions success marker", failure);
        }
    }

    private static void verifiesFreshJvmClassMetadata() {
        try {
            Path root = Files.createTempDirectory("fresh-jvm-metadata-");
            Path sources = Files.createDirectories(root.resolve("sources"));
            Path classes = Files.createDirectories(root.resolve("classes"));
            write(sources, "io/mazewall/NeedsFreshJvm.java", """
                package io.mazewall;
                import java.lang.annotation.*;
                @Inherited @Retention(RetentionPolicy.RUNTIME) @Target({ElementType.TYPE, ElementType.METHOD})
                public @interface NeedsFreshJvm {}
                """);
            write(sources, "org/junit/jupiter/api/Test.java", """
                package org.junit.jupiter.api;
                import java.lang.annotation.*;
                @Retention(RetentionPolicy.RUNTIME) @Target(ElementType.METHOD)
                public @interface Test {}
                """);
            for (String annotation : List.of("ParameterizedTest", "RepeatedTest", "TestTemplate", "Nested")) {
                String pkg = annotation.equals("ParameterizedTest") ? "org.junit.jupiter.params" : "org.junit.jupiter.api";
                write(sources, pkg.replace('.', '/') + "/" + annotation + ".java",
                    "package " + pkg + "; import java.lang.annotation.*; @Retention(RetentionPolicy.RUNTIME) "
                        + "public @interface " + annotation + " {}");
            }
            write(sources, "sample/Fixtures.java", """
                package sample;
                import io.mazewall.NeedsFreshJvm;
                import org.junit.jupiter.api.Test;
                import org.junit.jupiter.api.*;
                import org.junit.jupiter.params.ParameterizedTest;
                class MentionOnly { Class<?> marker = NeedsFreshJvm.class; }
                @NeedsFreshJvm class ClassTagged { @Test void test() {} }
                class MethodTagged { @Test @NeedsFreshJvm void test() {} }
                class MethodOnlyBase { @NeedsFreshJvm void test() {} }
                class MethodOnlyChild extends MethodOnlyBase {}
                abstract class InheritedMethodBase { @Test @NeedsFreshJvm void inheritedTest() {} }
                class InheritedMethodChild extends InheritedMethodBase {}
                class OverriddenMethodChild extends InheritedMethodBase { @Override void inheritedTest() {} }
                interface InheritedMethodInterface { @Test @NeedsFreshJvm default void inheritedInterfaceTest() {} }
                class InheritedInterfaceChild implements InheritedMethodInterface {}
                class OverriddenInterfaceChild implements InheritedMethodInterface { @Override public void inheritedInterfaceTest() {} }
                class InheritedTagged extends ClassTagged {}
                class Outer { @Nested class NestedTagged { @Test @NeedsFreshJvm void nestedTest() {} } }
                abstract class AbstractNestedBase {
                    @Nested class InheritedInner { @Test @NeedsFreshJvm void inheritedNestedTest() {} }
                }
                class InheritedNestedFirst extends AbstractNestedBase {}
                class InheritedNestedSecond extends AbstractNestedBase {}
                abstract class UntaggedNestedBase {
                    @Nested class UntaggedInner { @Test void inheritedNestedTest() {} }
                }
                @NeedsFreshJvm class TaggedNestedChild extends UntaggedNestedBase {}
                abstract class TemplateBase {
                    @ParameterizedTest @NeedsFreshJvm void parameterized(String value) {}
                    @RepeatedTest @NeedsFreshJvm void repeated() {}
                    @TestTemplate @NeedsFreshJvm void template() {}
                }
                class ParameterizedChild extends TemplateBase {
                    @Override void repeated() {} @Override void template() {}
                }
                class RepeatedChild extends TemplateBase {
                    @Override void parameterized(String value) {} @Override void template() {}
                }
                class TemplateChild extends TemplateBase {
                    @Override void parameterized(String value) {} @Override void repeated() {}
                }
                """);
            var compiler = ToolProvider.getSystemJavaCompiler();
            var arguments = new java.util.ArrayList<>(List.of("-d", classes.toString()));
            try (var files = Files.walk(sources)) {
                files.filter(path -> path.toString().endsWith(".java")).map(Path::toString).forEach(arguments::add);
            }
            int result = compiler.run(null, null, null, arguments.toArray(String[]::new));
            equal(0, result);
            equal(List.of(
                "sample.ClassTagged",
                "sample.InheritedInterfaceChild",
                "sample.InheritedMethodChild",
                "sample.InheritedNestedFirst",
                "sample.InheritedNestedSecond",
                "sample.InheritedTagged",
                "sample.MethodTagged",
                "sample.Outer",
                "sample.ParameterizedChild",
                "sample.RepeatedChild",
                "sample.TaggedNestedChild",
                "sample.TemplateChild"
            ), FreshJvmClassSelector.select(List.of(classes.toFile())));
            Path empty = Files.createDirectories(root.resolve("empty"));
            equal(List.of(), FreshJvmClassSelector.select(List.of(empty.toFile())));
            Path invalid = Files.createDirectories(root.resolve("invalid/sample"));
            Files.copy(classes.resolve("sample/MethodOnlyBase.class"), invalid.resolve("MethodOnlyBase.class"));
            try {
                FreshJvmClassSelector.select(List.of(invalid.getParent().toFile()));
                throw new AssertionError("Declared fresh-JVM marker without executable tests must fail");
            } catch (IllegalStateException expected) {
                equal(true, expected.getMessage().contains("sample.MethodOnlyBase"));
                equal(true, expected.getMessage().contains("no executable"));
            }
        } catch (Exception failure) {
            throw new AssertionError("Fresh-JVM metadata contract failed", failure);
        }
    }

    private static void write(Path root, String relative, String value) throws Exception {
        Path target = root.resolve(relative);
        Files.createDirectories(target.getParent());
        Files.writeString(target, value, StandardCharsets.UTF_8);
    }

    private static void verifiesBacklogContracts() {
        try {
            Path backlog = Files.createTempDirectory("backlog-contract-");
            write(backlog, "issue-open.md", issue("open", "open-id"));
            write(backlog, "resolved/issue-done.md", issue("resolved", "done-id"));
            Path index = backlog.resolve("README.md");
            Files.writeString(index, """
                # Index
                *Total Active Issues:* 1 | *Total Resolved Issues:* 1
                | Issue | Title | Priority | Component |
                |---|---|---|---|
                | [open](issue-open.md) | Open | high | testing |
                """);
            BacklogIndexValidator.validate(index);

            Files.writeString(index, Files.readString(index).replace("issue-open.md", "missing.md"));
            rejects(() -> BacklogIndexValidator.validate(index), "Broken backlog link");
            Files.writeString(index, """
                # Index
                *Total Active Issues:* 1 | *Total Resolved Issues:* 1
                | Issue | Title | Priority | Component |
                |---|---|---|---|
                | [open](../outside.md) | Open | high | testing |
                """);
            rejects(() -> BacklogIndexValidator.validate(index), "escapes index directory");
            Files.writeString(index, """
                # Index
                *Total Active Issues:* 1 | *Total Resolved Issues:* 1
                | Issue | Title | Priority | Component |
                |---|---|---|---|
                | [open](issue-open.md) | Open | high | testing |
                | [open again](issue-open.md) | Open | high | testing |
                """);
            rejects(() -> BacklogIndexValidator.validate(index), "Duplicate backlog index entry");
            Files.writeString(index, Files.readString(index).replace("*Total Active Issues:* 1", "*Total Active Issues:* 2")
                .replace("| [open again](issue-open.md) | Open | high | testing |\n", ""));
            rejects(() -> BacklogIndexValidator.validate(index), "Backlog totals are stale");
            Files.writeString(index, "*Total Active Issues:* 0 | *Total Resolved Issues:* 1\n");
            rejects(() -> BacklogIndexValidator.validate(index), "membership mismatch");

            Path duplicate = backlog.resolve("nested/issue-copy.md");
            write(backlog, "nested/issue-copy.md", issue("open", "open-id"));
            Files.writeString(index, "*Total Active Issues:* 2 | *Total Resolved Issues:* 1\n");
            rejects(() -> BacklogIndexValidator.validate(index), "Duplicate backlog issue id");
            Files.delete(duplicate);
            Files.writeString(backlog.resolve("issue-open.md"), issue("resolved", "open-id"));
            rejects(() -> BacklogIndexValidator.validate(index), "status/location mismatch");
            Files.writeString(backlog.resolve("issue-open.md"), issue("mystery", "open-id"));
            rejects(() -> BacklogIndexValidator.validate(index), "Unknown backlog status");
        } catch (Exception failure) {
            throw new AssertionError("Backlog validator contract failed", failure);
        }
    }

    private static String issue(String status, String id) {
        return "---\nstatus: \"" + status + "\"\n---\n<!-- id: " + id + " file: ignored.md -->\n";
    }

    private static void verifiesTestHealthAggregation() {
        try {
            Path root = Files.createTempDirectory("test-health-contract-");
            Path fragments = Files.createDirectories(root.resolve("fragments"));
            Files.writeString(fragments.resolve("z.json"), "{\"invocationToken\":\"current\",\"module\":\":profiler\",\"task\":\"test\",\"taskPath\":\":profiler:test\",\"fork\":\"2\",\"executionStatus\":\"completed\"}");
            Files.writeString(fragments.resolve("a.json"), "{\"invocationToken\":\"current\",\"module\":\":platform\",\"task\":\"test\",\"taskPath\":\":platform:test\",\"fork\":\"1\",\"executionStatus\":\"completed\"}");
            Path output = root.resolve("aggregate.json");
            TestHealthAggregateAction.aggregate(fragments.toFile(), output.toFile(),
                List.of(":enforcer:test", ":platform:test", ":profiler:test"), "current");
            String first = Files.readString(output);
            equal(true, first.indexOf("platform") < first.indexOf("profiler"));
            equal(true, first.contains("\"cachedOrNotExecutedTasks\": [\":enforcer:test\"]"));
            TestHealthAggregateAction.aggregate(fragments.toFile(), output.toFile(),
                List.of(":enforcer:test", ":platform:test", ":profiler:test"), "current");
            equal(first, Files.readString(output));

            Files.writeString(fragments.resolve("stale.json"), "{\"invocationToken\":\"previous\",\"module\":\":enforcer\",\"task\":\"test\",\"taskPath\":\":enforcer:test\",\"fork\":\"3\",\"executionStatus\":\"completed\"}");
            rejectsState(
                () -> TestHealthAggregateAction.aggregate(fragments.toFile(), output.toFile(),
                    List.of(":enforcer:test", ":platform:test", ":profiler:test"), "current"),
                "missing current tasks [:enforcer:test]"
            );
            equal(false, Files.exists(output));
        } catch (Exception failure) {
            throw new AssertionError("Test-health aggregation contract failed", failure);
        }
    }

    private static void rejects(ThrowingRunnable action, String expected) throws Exception {
        try {
            action.run();
            throw new AssertionError("Expected rejection containing " + expected);
        } catch (IllegalArgumentException failure) {
            if (!failure.getMessage().contains(expected)) throw failure;
        }
    }

    private static void rejectsState(ThrowingRunnable action, String expected) throws Exception {
        try {
            action.run();
            throw new AssertionError("Expected rejection containing " + expected);
        } catch (IllegalStateException failure) {
            if (!failure.getMessage().contains(expected)) throw failure;
        }
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }

    private static void equal(Object expected, Object actual) {
        if (!expected.equals(actual)) throw new AssertionError("Expected " + expected + ", got " + actual);
    }
}
