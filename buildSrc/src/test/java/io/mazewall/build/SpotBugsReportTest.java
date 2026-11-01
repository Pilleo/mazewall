package io.mazewall.build;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Even SpotBugs exit zero must not turn incomplete analysis into a successful gate. */
public final class SpotBugsReportTest {
    public static void main(String[] args) throws Exception {
        Path report = Files.createTempDirectory("spotbugs-report-test-").resolve("report.xml");
        String clean = "<BugCollection><Errors errors=\"0\" missingClasses=\"0\"/></BugCollection>";
        Files.writeString(report, clean);
        VerifySpotBugsReport.validate(report);
        for (String invalid : List.of(
                clean.replace("errors=\"0\"", "errors=\"1\""),
                clean.replace("missingClasses=\"0\"", "missingClasses=\"1\""),
                clean.replace("errors=\"0\"", "errors=\"unknown\""),
                clean.replace("missingClasses=\"0\"", ""),
                clean.replace("/>", "><Error/></Errors>"),
                clean.replace("/>", "><MissingClass>missing.Type</MissingClass></Errors>"),
                clean.replace("</BugCollection>", "<Errors errors=\"0\" missingClasses=\"0\"/></BugCollection>"),
                "<BugCollection/>", "<not-a-report/>", "<BugCollection>",
                "<!DOCTYPE BugCollection [<!ENTITY x SYSTEM 'file:///etc/passwd'>]>" + clean)) {
            Files.writeString(report, invalid);
            rejects(() -> VerifySpotBugsReport.validate(report), invalid);
        }
        Files.delete(report);
        rejects(() -> VerifySpotBugsReport.validate(report), "missing report");
        System.out.println("SPOTBUGS_REPORT_TEST_OK");
    }

    private static void rejects(Runnable action, String example) {
        try {
            action.run();
        } catch (org.gradle.api.GradleException expected) {
            return;
        }
        throw new AssertionError("Incomplete analysis accepted: " + example);
    }
}
