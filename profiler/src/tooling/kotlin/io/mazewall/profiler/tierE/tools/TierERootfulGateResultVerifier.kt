package io.mazewall.profiler.tierE.tools

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory

/** End-to-end rootful qualification requires real, unskipped native-backed test results. */
public object TierERootfulGateResultVerifier {
    /** A v2 pinned gate must retain exact ID use through privileged staging and host export. */
    public fun verifyPinned(
        output: Path,
        expectedImageId: String,
    ): Boolean {
        require(verify(output)) { "Pinned rootful gate lacks aggregate native and JVM evidence" }
        require(expectedImageId.matches(Regex("sha256:[a-f0-9]{64}"))) { "Invalid expected rootful image ID" }
        val admission = output.resolve("image-admission.json")
        require(Files.isRegularFile(admission, NOFOLLOW_LINKS) && Files.size(admission) <= 8192L) {
            "Pinned rootful image admission receipt missing"
        }
        val record = Json.parseToJsonElement(Files.readString(admission)) as? JsonObject
            ?: throw IllegalArgumentException("Pinned image receipt must be an object")
        require(
            record.keys == setOf("schema_version", "mode", "image_id", "expected_image_id", "image_reference", "containerfile_sha256", "source_digest", "policy_eligibility") &&
            record["schema_version"] == JsonPrimitive(1) &&
            record["mode"]?.jsonPrimitive?.content == "PINNED_IMAGE_ID" &&
            listOf("image_id", "expected_image_id", "image_reference").all { record[it]?.jsonPrimitive?.content == expectedImageId } &&
            listOf("containerfile_sha256", "source_digest").all { record[it]?.jsonPrimitive?.content?.matches(Regex("[a-f0-9]{64}")) == true } &&
            record["policy_eligibility"] == JsonPrimitive(false),
        ) { "Pinned image identity changed before aggregate export" }
        val build = output.resolve("native-build-identity.txt")
        require(
            Files.isRegularFile(build, NOFOLLOW_LINKS) &&
            Files.readAllLines(build).contains("image_id=$expectedImageId") &&
            Files.readAllLines(build).contains("source_digest=${record.getValue("source_digest").jsonPrimitive.content}"),
        ) {
            "Pinned image or source digest disagrees with native build staging"
        }
        return true
    }

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
