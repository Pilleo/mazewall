package io.mazewall.build;

import com.github.spotbugs.snom.SpotBugsTask;
import java.io.File;
import java.nio.file.Path;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.gradle.api.Action;
import org.gradle.api.GradleException;
import org.gradle.api.Task;

/** A SpotBugs process may exit zero after recording internal analysis errors. */
public final class VerifySpotBugsReport implements Action<Task> {
    private final File report;

    public VerifySpotBugsReport(File report) {
        this.report = report;
    }

    @Override public void execute(Task task) {
        ((SpotBugsTask) task).getWorkerExecutor().await();
        validate(report.toPath());
    }

    static void validate(Path report) {
        try {
            var factory = DocumentBuilderFactory.newInstance();
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            var parser = factory.newDocumentBuilder();
            parser.setErrorHandler(new org.xml.sax.helpers.DefaultHandler() {
                @Override public void error(org.xml.sax.SAXParseException failure) throws org.xml.sax.SAXException { throw failure; }
                @Override public void fatalError(org.xml.sax.SAXParseException failure) throws org.xml.sax.SAXException { throw failure; }
            });
            var root = parser.parse(report.toFile()).getDocumentElement();
            var summaries = root.getElementsByTagName("Errors");
            if (!root.getTagName().equals("BugCollection") || summaries.getLength() != 1
                    || summaries.item(0).getParentNode() != root) {
                throw new GradleException("Missing or unexpected SpotBugs analysis summary: " + report);
            }
            var errors = (org.w3c.dom.Element) summaries.item(0);
            if (!errors.getAttribute("errors").equals("0") || !errors.getAttribute("missingClasses").equals("0")
                    || root.getElementsByTagName("Error").getLength() != 0
                    || root.getElementsByTagName("MissingClass").getLength() != 0) {
                throw new GradleException("Incomplete SpotBugs analysis (errors=" + errors.getAttribute("errors")
                        + ", missingClasses=" + errors.getAttribute("missingClasses") + "): " + report);
            }
        } catch (javax.xml.parsers.ParserConfigurationException | org.xml.sax.SAXException | java.io.IOException failure) {
            throw new GradleException("Cannot verify SpotBugs analysis report: " + report, failure);
        }
    }
}
