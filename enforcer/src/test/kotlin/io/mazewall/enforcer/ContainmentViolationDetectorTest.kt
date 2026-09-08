package io.mazewall.enforcer

import io.mazewall.enforcer.api.ContainmentViolationEvidence
import io.mazewall.enforcer.diagnostics.ContainmentViolationDetector
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import java.io.IOException
import java.net.ConnectException
import java.net.SocketException
import java.nio.file.AccessDeniedException
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ContainmentViolationDetectorTest {
    data class ViolationCase(
        val exception: Throwable,
        val expected: Boolean,
        val description: String,
    )

    companion object {
        @JvmStatic
        fun violationCases() =
            listOf(
                ViolationCase(IOException("Operation not permitted"), false, "unobserved IOException with EPERM"),
                ViolationCase(IOException("Permission denied"), false, "unobserved IOException with Permission denied"),
                ViolationCase(SocketException("Permission denied"), false, "unobserved SocketException with Permission denied"),
                ViolationCase(AccessDeniedException("/some/path"), false, "unobserved AccessDeniedException"),
                ViolationCase(
                    IOException("Cannot run program \"/bin/sh\": IOException: error=1, Operation not permitted"),
                    false,
                    "unobserved JVM exec EPERM",
                ),
                ViolationCase(ConnectException("Permission denied"), false, "unobserved ConnectException with Permission denied"),
                ViolationCase(IOException(null as String?), false, "IOException with null message"),
                ViolationCase(IOException("Connection reset by peer"), false, "Unrelated IOException"),
                ViolationCase(IllegalArgumentException("bad argument"), false, "Unrelated RuntimeException"),
                ViolationCase(
                    RuntimeException("Access denied by application rule"),
                    false,
                    "Non-IO exception with 'denied'",
                ),
                ViolationCase(
                    IOException("Authentication denied by endpoint"),
                    false,
                    "IOException with unrelated 'denied'",
                ),
            )
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("violationCases")
    fun `test violation detection`(case: ViolationCase) {
        if (case.expected) {
            assertTrue(ContainmentViolationDetector.isContainmentViolation(case.exception), case.description)
        } else {
            assertFalse(ContainmentViolationDetector.isContainmentViolation(case.exception), case.description)
        }
    }

    @Test
    fun `IOException with errno 1 deep in cause chain is detected`() {
        val root = IOException("Operation not permitted")
        val mid = RuntimeException("wrapper", root)
        val top = IllegalStateException("top", mid)
        assertEquals(ContainmentViolationEvidence.INFERRED_PERMISSION_FAILURE, ContainmentViolationDetector.diagnose(top)?.evidence)
    }

    @Test
    fun `suppressed exception with EPERM is detected`() {
        val primary = RuntimeException("task failed")
        val suppressed = IOException("Operation not permitted")
        primary.addSuppressed(suppressed)
        assertEquals(ContainmentViolationEvidence.INFERRED_PERMISSION_FAILURE, ContainmentViolationDetector.diagnose(primary)?.evidence)
    }

    @Test
    fun `findViolationCause returns root EPERM from cause chain`() {
        val root = IOException("Operation not permitted")
        val mid = RuntimeException("wrapper", root)
        val top = IllegalStateException("top", mid)
        val found = ContainmentViolationDetector.findViolationCause(top)
        assertTrue(found === root, "Expected the root IOException, got $found")
    }

    @Test
    fun `findViolationCause finds suppressed violation`() {
        val primary = RuntimeException("task failed")
        val suppressed = SocketException("Permission denied")
        primary.addSuppressed(suppressed)
        val found = ContainmentViolationDetector.findViolationCause(primary)
        assertTrue(found === suppressed, "Expected the suppressed SocketException, got $found")
    }

    @Test
    fun `findViolationCause finds violation in cause of suppressed exception`() {
        val primary = RuntimeException("task failed")
        val violation = IOException("Operation not permitted")
        val suppressedWrapper = RuntimeException("suppressed wrapper", violation)
        primary.addSuppressed(suppressedWrapper)
        val found = ContainmentViolationDetector.findViolationCause(primary)
        assertTrue(found === violation, "Expected the nested IOException, got $found")
        assertEquals(ContainmentViolationEvidence.INFERRED_PERMISSION_FAILURE, ContainmentViolationDetector.diagnose(primary)?.evidence)
    }

    class CyclicCauseException(
        val nextProvider: () -> Throwable?,
    ) : RuntimeException("Cyclic Cause") {
        override val cause: Throwable?
            get() = nextProvider()
    }

    @Test
    fun `isContainmentViolation handles circular references in cause chain`() {
        var next: Throwable? = null
        val t1 = CyclicCauseException { next }
        val t2 = CyclicCauseException { t1 }
        next = t2

        assertFalse(ContainmentViolationDetector.isContainmentViolation(t1))

        val violation = IOException("Operation not permitted")
        val t3 = CyclicCauseException { violation }
        next = t3
        assertEquals(ContainmentViolationEvidence.INFERRED_PERMISSION_FAILURE, ContainmentViolationDetector.diagnose(t1)?.evidence)
    }

    @Test
    fun `isContainmentViolation handles mixed cause and suppressed cycles gracefully`() {
        var next: Throwable? = null
        val t1 = CyclicCauseException { next }
        val t2 = RuntimeException("t2")
        next = t2

        t2.addSuppressed(t1)

        assertFalse(ContainmentViolationDetector.isContainmentViolation(t1))

        val violation = IOException("Operation not permitted")
        t2.addSuppressed(violation)
        assertEquals(ContainmentViolationEvidence.INFERRED_PERMISSION_FAILURE, ContainmentViolationDetector.diagnose(t1)?.evidence)
        val found = ContainmentViolationDetector.findViolationCause(t1)
        assertTrue(found === violation)
    }

    @Test
    fun `structured violation matches even with null message and no defaults`() {
        // issue-20260823-171958: structured type is matched by construction, before any
        // message heuristic, and regardless of the useDefaults switch.
        val detector = ContainmentViolationDetector(useDefaults = false, loadServices = false)
        val v = io.mazewall.enforcer.api.ContainmentViolationException(
            message = "denied",
            errno = io.mazewall.ffi.NativeConstants.EPERM,
            syscallNr = 42,
            evidence = ContainmentViolationEvidence.OBSERVED_POLICY_DENIAL,
        )
        assertTrue(detector.isContainmentViolation(v))
        assertEquals(io.mazewall.ffi.NativeConstants.EPERM, v.errno)
        assertEquals(42, v.syscallNr)
    }

    @Test
    fun `legacy constructor keeps nullable taxonomy fields`() {
        val v = io.mazewall.enforcer.api
            .ContainmentViolationException("legacy")
        assertEquals(null, v.errno)
        assertEquals(null, v.syscallNr)
        assertFalse(ContainmentViolationDetector.isContainmentViolation(v))
        assertEquals(ContainmentViolationEvidence.UNKNOWN, ContainmentViolationDetector.diagnose(v)?.evidence)
    }

    @Test
    fun `generic permission failures are not confirmed containment violations`() {
        assertFalse(ContainmentViolationDetector.isContainmentViolation(AccessDeniedException("/user-owned-file")))
        assertFalse(ContainmentViolationDetector.isContainmentViolation(IOException("error=13, Permission denied")))
        assertFalse(ContainmentViolationDetector.isContainmentViolation(IOException("Cannot run program \"missing-tool\"")))
        assertFalse(ContainmentViolationDetector.isContainmentViolation(IllegalArgumentException("error=22, bad argument")))
    }

    @Test
    fun `structured violation wins over an outer heuristic match`() {
        val structured = io.mazewall.enforcer.api.ContainmentViolationException(
            message = "kernel denial",
            errno = io.mazewall.ffi.NativeConstants.EPERM,
            syscallNr = 59,
            evidence = ContainmentViolationEvidence.OBSERVED_POLICY_DENIAL,
        )
        val outer = IOException("Permission denied", structured)

        assertTrue(ContainmentViolationDetector.findViolationCause(outer) === structured)
    }
}
