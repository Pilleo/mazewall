package demo.koog

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
internal data class EvidenceRef(
    val document: String,
    val line: Int,
)

@Serializable
internal data class IncidentReport(
    val hypothesis: String,
    val evidence: List<EvidenceRef>,
    val nextSteps: List<String>,
)

internal val incidentDocuments: Set<String> = setOf("application.log", "deployment.md", "pool-settings.txt")

internal fun validateIncidentDocument(
    document: String,
    text: String,
) {
    require(document in incidentDocuments) { "Unknown incident document" }
    requireUtf8Bound(text, 8192)
    require(text.lineSequence().count() <= 256) { "Incident document exceeds256 lines" }
}

internal fun decodeIncidentReport(
    json: String,
    successfulReads: Map<String, String>,
): IncidentReport {
    requireUtf8Bound(json, 16_384)
    require(successfulReads.size in 1..3) { "Actual successful reads required" }
    successfulReads.forEach(::validateIncidentDocument)
    // shortcut: duplicate keys keep the last value; reject duplicates before reports can authorize actions.
    val report = Json.decodeFromString<IncidentReport>(json)
    require(report.hypothesis.isNotBlank() && report.evidence.size in 1..8 && report.nextSteps.size in 1..5) { "Invalid incident report shape" }
    requireDisplayText(report.hypothesis)
    report.nextSteps.forEach {
        require(it.isNotBlank())
        requireDisplayText(it)
    }
    report.evidence.forEach { reference ->
        val content = requireNotNull(successfulReads[reference.document]) { "Evidence references an unread document" }
        require(reference.line in 1..content.lineSequence().count()) { "Evidence references a nonexistent line" }
    }
    return report
}

internal fun renderIncidentReport(report: IncidentReport): String {
    return buildString {
        appendLine("# Incident analysis (model-generated hypothesis)")
        appendLine("\n## Hypothesis\n${report.hypothesis}")
        appendLine("\n## Evidence")
        report.evidence.forEach { appendLine("- ${it.document}:${it.line}") }
        appendLine("\n## Next steps")
        report.nextSteps.forEach { appendLine("- $it") }
    }
}

private fun requireUtf8Bound(
    text: String,
    bytes: Int,
) {
    require(text.length <= bytes && Charsets.UTF_8.newEncoder().canEncode(text) && text.toByteArray(Charsets.UTF_8).size <= bytes) { "Invalid or excessive UTF-8 input" }
}

private fun requireDisplayText(text: String) {
    requireUtf8Bound(text, 16_384)
    require(text.none { it.isISOControl() && it != '\n' && it != '\t' }) { "Terminal control characters are not report content" }
}
