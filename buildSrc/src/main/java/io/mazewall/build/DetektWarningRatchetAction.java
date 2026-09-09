package io.mazewall.build;

import java.io.File;
import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.xml.sax.InputSource;
import org.gradle.api.Action;
import org.gradle.api.GradleException;
import org.gradle.api.Task;

/** Captures only immutable file locations, so execution never reaches a Gradle project. */
public final class DetektWarningRatchetAction implements Action<Task> {
    private static final Pattern FILE_BLOCK = Pattern.compile("<file name=\"([^\"]+)\">(.*?)</file>", Pattern.DOTALL);
    private static final Pattern FINDING = Pattern.compile("<error\\b.*?/>", Pattern.DOTALL);
    private static final Pattern POSITION = Pattern.compile("(line|column)=\"\\d+\"");
    private final Map<String, File> reports;
    private final Map<String, File> inventories;

    public DetektWarningRatchetAction(Map<String, File> reports, Map<String, File> inventories) {
        this.reports = new TreeMap<>(reports);
        this.inventories = new TreeMap<>(inventories);
    }

    @Override
    public void execute(Task task) {
        int additions = 0;
        for (Map.Entry<String, File> report : reports.entrySet()) {
            try {
                List<String> expected = Files.readAllLines(inventories.get(report.getKey()).toPath(), StandardCharsets.UTF_8);
                List<String> actual = normalize(Files.readAllLines(report.getValue().toPath(), StandardCharsets.UTF_8));
                List<String> added = subtract(actual, expected);
                List<String> removed = subtract(expected, actual);
                task.getLogger().lifecycle("{}: {} added, {} removed normalized Detekt warnings", report.getKey(), added.size(), removed.size());
                added.forEach(finding -> task.getLogger().lifecycle("  ADDED {}", finding));
                removed.forEach(finding -> task.getLogger().lifecycle("  REMOVED {}", finding));
                additions += added.size();
            } catch (IOException failure) {
                throw new GradleException("Cannot read Detekt warning inventory for " + report.getKey(), failure);
            }
        }
        if (additions != 0) {
            throw new GradleException("Detekt warning ratchet exceeded: " + additions + " added normalized findings. Remove new warnings; reviewed inventories must not grow.");
        }
    }

    static List<String> normalize(List<String> lines) {
        String xml = String.join("\n", lines);
        try {
            var factory = DocumentBuilderFactory.newInstance();
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            var parser = factory.newDocumentBuilder();
            parser.setErrorHandler(new org.xml.sax.helpers.DefaultHandler() {
                @Override
                public void error(org.xml.sax.SAXParseException failure) throws org.xml.sax.SAXException {
                    throw failure;
                }

                @Override
                public void fatalError(org.xml.sax.SAXParseException failure) throws org.xml.sax.SAXException {
                    throw failure;
                }
            });
            parser.parse(new InputSource(new StringReader(xml)));
        } catch (javax.xml.parsers.ParserConfigurationException | org.xml.sax.SAXException | IOException failure) {
            throw new IllegalArgumentException("Invalid Detekt XML report; cannot compare warning inventory", failure);
        }
        List<String> findings = new ArrayList<>();
        var files = FILE_BLOCK.matcher(xml);
        while (files.find()) {
            var errors = FINDING.matcher(files.group(2));
            while (errors.find()) {
                String finding = errors.group();
                if (finding.contains("severity=\"warning\"")) {
                    findings.add(files.group(1) + "|" + POSITION.matcher(finding).replaceAll("").replaceAll("\\s+", " ").trim());
                }
            }
        }
        Collections.sort(findings);
        return findings;
    }

    /** Multiset subtraction preserves duplicate warnings at distinct source positions. */
    static List<String> subtract(List<String> left, List<String> right) {
        List<String> result = new ArrayList<>(left);
        right.forEach(result::remove);
        Collections.sort(result);
        return result;
    }
}
