package io.mazewall.enforcer.api

import io.mazewall.enforcer.internal.artifact.OperationDocumentJson

/** Evidence and reviewed exceptions accompany candidate policy; serialization is not approval. */
public class OperationPolicyDocument(
    public val binding: OperationBinding,
    public val policy: CanonicalPolicyArtifact,
    missingCoverage: Set<CoverageFacet>,
    reviewedExceptions: Map<CoverageFacet, String>,
) {
    public val missingCoverage: Set<CoverageFacet> = java.util.Set.copyOf(missingCoverage)
    public val reviewedExceptions: Map<CoverageFacet, String> = java.util.Map.copyOf(reviewedExceptions)

    init {
        require(this.reviewedExceptions.keys.all { it in this.missingCoverage }) { "Reviewed exceptions must identify missing coverage" }
        require(this.reviewedExceptions.values.all { it.isNotBlank() }) { "Reviewed exceptions require specific reasons" }
    }

    public fun toJson(): String = OperationDocumentJson.encode(this)

    public fun sha256(): String =
        java.util.HexFormat
        .of()
        .formatHex(
            java.security.MessageDigest
            .getInstance("SHA-256")
            .digest(toJson().toByteArray(Charsets.UTF_8)),
        )

    public companion object {
        @JvmStatic
        public fun fromJson(document: String): OperationPolicyDocument = OperationDocumentJson.decode(document)
    }
}

/** Operator-controlled review configuration, never constructed from request or model metadata. */
public class TrustedPolicyReview(
    public val reviewer: String,
    public val documentSha256: String,
    acceptedExceptions: Set<CoverageFacet>,
) {
    public val acceptedExceptions: Set<CoverageFacet> = java.util.Set.copyOf(acceptedExceptions)

    init {
        require(reviewer.isNotBlank())
        requireSha256(documentSha256)
    }
}

/** Exact document approval and configured identity are validated before this value is produced. */
public class ReviewedOperationArtifact private constructor(
    public val document: OperationPolicyDocument,
    public val review: TrustedPolicyReview,
) {
    public val binding: OperationBinding get() = document.binding

    public companion object {
        @JvmStatic
        public fun load(
            document: String,
            review: TrustedPolicyReview,
            expectedBinding: OperationBinding,
        ): ReviewedOperationArtifact {
            val decoded = OperationPolicyDocument.fromJson(document)
            require(decoded.sha256() == review.documentSha256) { "Document is not covered by the configured trusted review" }
            require(decoded.binding.matches(expectedBinding)) { "Operation implementation, adapter identity or resource generation does not match configuration" }
            require(decoded.reviewedExceptions.keys == decoded.missingCoverage) { "Missing coverage lacks a specifically reviewed exception" }
            require(decoded.reviewedExceptions.keys.all { it in review.acceptedExceptions }) { "Document exceptions exceed trusted review authorization" }
            return ReviewedOperationArtifact(decoded, review)
        }
    }
}
