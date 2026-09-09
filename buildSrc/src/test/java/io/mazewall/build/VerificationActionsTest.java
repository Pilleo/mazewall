package io.mazewall.build;

import java.util.List;

/** Dependency-free executable checks for our warning inventory contract. */
public final class VerificationActionsTest {
    public static void main(String[] args) {
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
        System.out.println("Verification actions: multiline findings, position invariance, duplicate additions, removals, and severity filtering passed.");
    }

    private static void equal(Object expected, Object actual) {
        if (!expected.equals(actual)) throw new AssertionError("Expected " + expected + ", got " + actual);
    }
}
