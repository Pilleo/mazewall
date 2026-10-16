package io.mazewall.profiler.tierE.stress

/** Independent benchmark axes; never reinterpret the session's existing fail-closed terminal. */
internal object TierESustainedComparisonVerdict {
    enum class DeclaredOperations { PASS, INCOMPLETE }

    enum class DiagnosticIntegrity { DISABLED, PASS, INCOMPLETE }

    data class Result(
        val declaredOperations: DeclaredOperations,
        val diagnosticIntegrity: DiagnosticIntegrity,
        val qualification: String = "UNVERIFIED",
    )

    fun evaluate(
        expected: Int,
        retainedIds: Int?,
        oracleAvailable: Boolean,
        operationOracleComplete: Boolean,
        auditsEnabled: Boolean,
        pairedDiagnosticComplete: Boolean,
    ): Result {
        val declared = if (expected > 0 && retainedIds == expected && oracleAvailable && operationOracleComplete) {
            DeclaredOperations.PASS
        } else {
            DeclaredOperations.INCOMPLETE
        }
        val diagnostic = when {
            !auditsEnabled -> DiagnosticIntegrity.DISABLED
            pairedDiagnosticComplete -> DiagnosticIntegrity.PASS
            else -> DiagnosticIntegrity.INCOMPLETE
        }
        return Result(declared, diagnostic)
    }
}
