package io.mazewall.profiler.internal.attribution

/**
 * Validates one native MZSE committed prefix before any definition enters the
 * session collector. The preparation step is read-only; identical retries do
 * not re-admit definitions and a conflicting prefix cannot partially commit.
 */
internal class TierENativeSnapshotAdmission(
    private val expectedSession: SessionEpoch,
    private val maxDefinitions: Int = TierEOptions.DEFAULT_RECORD_LIMIT,
) {
    init {
        require(maxDefinitions > 0)
    }

    data class Prepared(
        val publication: SnapshotPublication,
        val digest: String,
        val stacks: List<StackDefinition>,
        val invocations: List<InvocationDefinition>,
    )

    private val definitions = SnapshotReplayAdmission()
    private var lastPublication: SnapshotPublication? = null
    private var lastDigest: String? = null
    var publicationCount: Long = 0
        private set

    fun prepare(contents: AgentDefinitionFile.Contents): Prepared? {
        val publication = requireNotNull(contents.publication) { "native live snapshot requires MZSE metadata" }
        require(publication.sessionEpoch == expectedSession) { "live dictionary snapshot belongs to a foreign session" }
        val digest = requireNotNull(contents.sourceSha256) { "live dictionary snapshot lacks immutable source digest" }
        require(digest.matches(DIGEST) && contents.sourceByteLength > 0) { "invalid live dictionary snapshot identity" }
        require(contents.stacks.size.toLong() + contents.invocations.size <= maxDefinitions) { "live snapshot exceeds definition budget" }
        val previous = lastPublication
        if (previous != null && publication.publicationId == previous.publicationId) {
            require(publication == previous && digest == lastDigest) { "conflicting replay of native snapshot publication" }
            return null
        }
        require(publication.publicationId > (previous?.publicationId ?: 0)) { "live snapshot publication id regressed" }
        require(publication.watermark >= (previous?.watermark ?: 0)) { "live snapshot watermark regressed" }
        require(contents.invocations.all { it.invocationId.value <= publication.watermark }) {
            "live snapshot contains invocation beyond committed watermark"
        }
        val includedStacks = contents.stacks.mapTo(HashSet()) { it.stackTraceId }
        require(contents.invocations.all { it.stackTraceId == null || it.stackTraceId in includedStacks }) {
            "live snapshot omitted an invocation stack dependency"
        }
        require(
            contents.stacks.none { definitions.inspect(it) == DefinitionAdmission.CONFLICTING } &&
            contents.invocations.none { definitions.inspect(it) == DefinitionAdmission.CONFLICTING },
        ) { "live snapshot conflicts with previously admitted definitions" }
        return Prepared(
            publication,
            digest,
            contents.stacks.filter { definitions.inspect(it) == DefinitionAdmission.NEW },
            contents.invocations.filter { definitions.inspect(it) == DefinitionAdmission.NEW },
        )
    }

    fun commit(prepared: Prepared) {
        check(prepared.publication.publicationId > (lastPublication?.publicationId ?: 0)) { "live snapshot commit was replayed" }
        prepared.stacks.forEach { check(definitions.admit(it) == DefinitionAdmission.NEW) }
        prepared.invocations.forEach { check(definitions.admit(it) == DefinitionAdmission.NEW) }
        lastPublication = prepared.publication
        lastDigest = prepared.digest
        publicationCount++
    }

    private companion object {
        val DIGEST = Regex("[0-9a-f]{64}")
    }
}
