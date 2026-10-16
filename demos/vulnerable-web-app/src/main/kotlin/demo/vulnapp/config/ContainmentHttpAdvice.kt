package demo.vulnapp.config

import io.mazewall.enforcer.api.ContainmentViolationEvidence
import io.mazewall.enforcer.api.ContainmentViolationException
import io.mazewall.platform.syscall.ErrnoMapping
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

/** Preserve the library's evidence strength; an unknown origin is a server failure, not denial proof. */
@RestControllerAdvice
internal class ContainmentHttpAdvice {
    @ExceptionHandler(ContainmentViolationException::class)
    fun refused(failure: ContainmentViolationException): ResponseEntity<Map<String, String>> {
        val status = when (failure.evidence) {
            ContainmentViolationEvidence.OBSERVED_POLICY_DENIAL,
            ContainmentViolationEvidence.INFERRED_PERMISSION_FAILURE,
            -> HttpStatus.FORBIDDEN
            ContainmentViolationEvidence.UNKNOWN -> HttpStatus.INTERNAL_SERVER_ERROR
        }
        val detail = when (failure.evidence) {
            ContainmentViolationEvidence.OBSERVED_POLICY_DENIAL -> "Observed policy denial: ${failure.errno?.let(ErrnoMapping::getSymbolicName).orEmpty()}"
            ContainmentViolationEvidence.INFERRED_PERMISSION_FAILURE -> failure.cause?.let { "${it.javaClass.simpleName}: ${it.message.orEmpty()}" } ?: "Permission failure was inferred"
            ContainmentViolationEvidence.UNKNOWN -> "Containment origin was not recorded"
        }
        return ResponseEntity.status(status).body(mapOf("error" to "containment-refused", "evidence" to failure.evidence.name, "detail" to detail.take(512)))
    }
}
