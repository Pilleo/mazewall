package io.mazewall.enforcer.internal.artifact

import io.mazewall.enforcer.api.CanonicalPolicyArtifact
import io.mazewall.enforcer.api.CoverageFacet
import io.mazewall.enforcer.api.OperationAdapter
import io.mazewall.enforcer.api.OperationBinding
import io.mazewall.enforcer.api.OperationPolicyDocument
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Versioned framework-neutral binding envelope; trusted approval is deliberately external. */
internal object OperationDocumentJson {
    private const val VERSION = 1

    fun encode(document: OperationPolicyDocument): String =
        buildJsonObject {
        put("formatVersion", VERSION)
        put(
            "binding",
            buildJsonObject {
            val binding = document.binding
            put("operationId", binding.operationId)
            put("adapter", binding.adapter.name)
            put("implementationSha256", binding.implementationSha256)
            put("resourceGeneration", binding.resourceGeneration)
            put("identity", buildJsonObject { binding.adapterIdentity.toSortedMap().forEach { (key, value) -> put(key, value) } })
        },
        )
        put("policy", Json.parseToJsonElement(document.policy.toJson()))
        put(
            "missingCoverage",
            JsonArray(
                document.missingCoverage
            .map { it.name }
            .sorted()
            .map { JsonPrimitive(it) },
            ),
        )
        put(
            "reviewedExceptions",
            buildJsonObject {
            document.reviewedExceptions.entries
            .sortedBy { it.key.name }
            .forEach { (facet, reason) -> put(facet.name, reason) }
        },
        )
    }.toString()

    fun decode(document: String): OperationPolicyDocument {
        val root = StrictArtifactJson.parse(document).fields("formatVersion", "binding", "policy", "missingCoverage", "reviewedExceptions")
        require(root.getValue("formatVersion").intValue() == VERSION) { "Unsupported operation document version" }
        val binding = root.getValue("binding").objectValue().fields("operationId", "adapter", "implementationSha256", "resourceGeneration", "identity")
        val identity = binding.getValue("identity").objectValue().mapValues { (_, value) -> value.stringValue() }
        val missing = root.getValue("missingCoverage").arrayValue().map { CoverageFacet.valueOf(it.stringValue()) }
        require(missing.distinct().size == missing.size) { "Duplicate missing-coverage facet" }
        val exceptions = root
            .getValue("reviewedExceptions")
            .objectValue()
            .entries
            .associate { (facet, reason) -> CoverageFacet.valueOf(facet) to reason.stringValue() }
        return OperationPolicyDocument(
            OperationBinding(
                binding.getValue("operationId").stringValue(),
                OperationAdapter.valueOf(binding.getValue("adapter").stringValue()),
                binding.getValue("implementationSha256").stringValue(),
                binding.getValue("resourceGeneration").stringValue(),
                identity,
            ),
            CanonicalPolicyArtifact.fromJson(root.getValue("policy").toString()),
            missing.toSet(),
            exceptions,
        )
    }
}
