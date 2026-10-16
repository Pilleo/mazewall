package consumer

import io.mazewall.profiler.api.BillOfBehavior
import io.mazewall.profiler.api.IncompleteProfileException
import io.mazewall.profiler.api.ProfileDocument
import io.mazewall.profiler.api.ProfilingResult
import recipes.convertProfile

fun main() {
    // The public legacy constructor genuinely has absent/incomplete collection evidence.
    val result = ProfilingResult(Unit, BillOfBehavior(), emptyMap())
    check(!result.coverage.complete)
    val document = ProfileDocument.fromJson(result.toProfileDocumentJson())
    check(document.coverage == result.coverage)
    check(document.behavior == result.behavior)
    for (convert in listOf<() -> Any>(
        { convertProfile(result) },
        { result.toPolicy() },
        { ProfilingResult.dedicatedWorkerPoliciesFromDocument(result.toProfileDocumentJson()) },
    )) {
        try {
            convert()
            error("Incomplete profile unexpectedly authorized enforcement")
        } catch (expected: IncompleteProfileException) {
            check(!expected.coverage.complete)
        }
    }
    println("PASS: actual conversion snippet, policy and Portal document refuse absent coverage; export preserves it")
}
