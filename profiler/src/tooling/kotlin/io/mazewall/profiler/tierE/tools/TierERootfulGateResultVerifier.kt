package io.mazewall.profiler.tierE.tools

import java.nio.file.Files
import java.nio.file.Path
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory

/** End-to-end rootful qualification requires real, unskipped native-backed test results. */
public object TierERootfulGateResultVerifier {
    public fun verify(output: Path): Boolean {
        require(
            Files.isRegularFile(output.resolve("native-ctest.log")) &&
            Files.isRegularFile(output.resolve("profiler-tests.log")) &&
            Files.readString(output.resolve("benchmark-gate-status.txt")).trim() == "PASS" &&
            Files.readString(output.resolve("benchmark-gate-contract.txt")).trim() == "2",
        ) {
            "Rootful gate lacks passing native, profiler, or benchmark evidence"
        }
        val directory = output.resolve("integrationTestFreshJvm")
        require(Files.isDirectory(directory)) { "Rootful fresh JVM results missing" }
        val reports = Files.list(directory).use { files -> files.filter { it.fileName.toString().endsWith(".xml") }.toList() }
        val factory = DocumentBuilderFactory.newInstance().apply {
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            setFeature("http://xml.org/sax/features/external-general-entities", false)
            setFeature("http://xml.org/sax/features/external-parameter-entities", false)
            setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
            setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "")
            setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "")
        }
        for (name in listOf("TierEEndToEndIntegrationTest", "TierEDaemonNativeWorkflowIntegrationTest")) {
            val matches = reports.filter { name in it.fileName.toString() }
            require(matches.isNotEmpty()) { "Missing rootful native-backed suite: $name" }
            for (file in matches) {
                val suite = factory.newDocumentBuilder().parse(file.toFile()).documentElement
                require(
                    suite.nodeName == "testsuite" &&
                    suite.getAttribute("tests").toIntOrNull()?.let { it > 0 } == true &&
                    listOf("skipped", "failures", "errors").all { suite.getAttribute(it) == "0" },
                ) {
                    "Incomplete rootful native-backed suite: $name"
                }
            }
        }
        return true
    }
}
