---
title: "Make containment exception classification and extraction reliable for callers"
severity: "MEDIUM"
status: "open"
priority: high
dependencies: []
component: "enforcer"
target_modules: ["enforcer"]
target_files:
  - "enforcer/src/main/kotlin/io/mazewall/enforcer/diagnostics/ContainmentViolationDetector.kt"
  - "enforcer/src/main/kotlin/io/mazewall/enforcer/api/ContainmentViolationException.kt"
  - "enforcer/src/main/kotlin/io/mazewall/enforcer/internal/ContainedExecutorWrapper.kt"
  - "enforcer/src/main/kotlin/io/mazewall/Mazewall.kt"
  - "enforcer/src/test/kotlin/io/mazewall/enforcer/ContainmentViolationDetectorTest.kt"
  - "enforcer/src/test/kotlin/io/mazewall/enforcer/ContainmentViolationDetectorExtensibilityTest.kt"
  - "enforcer/src/test/kotlin/io/mazewall/enforcer/internal/ContainedExecutorWrapperTest.kt"
  - "docs/internals/designs/api/enforcer-public-api.md"
target_symbols:
  - "ContainmentViolationDetector"
  - "ContainmentViolationException"
  - "ContainedExecutorWrapper"
  - "runContained"
open_questions: false

paperclip_issue_id: "4d45298b-73c9-44c3-8f2a-328a09b342c2"
paperclip_identifier: "MAZ-1208"
---

# 🟡 [Severity: MEDIUM]: Make containment exception classification and extraction reliable for callers

**Context:**
Exception-based application handling currently conflates inferred permission failures with observed policy denials and makes nested structured metadata difficult to consume. Improve the exception contract without callbacks or changes to kernel enforcement.

**Evidence:**
- The detector accepts `AccessDeniedException`, message errno values 1/13/22, and the broad phrase `Cannot run`. These signals alone do not establish that mazewall denied an operation.
- `ContainedExecutorWrapper` promotes heuristic matches to `ContainmentViolationException`. It preserves a directly thrown structured exception, but wraps a nested violation with null top-level `errno` and `syscallNr` while retaining the original cause.
- `findViolationCause` checks the outer throwable before its causes, so an outer heuristic match can hide a more useful structured inner violation.
- `runContained` unwraps `ExecutionException`, whereas executor futures retain standard Java wrapping. Its KDoc overstates that blocked syscalls necessarily produce exceptions.
- Follow-up to resolved `issue-20260823-171958-structured-violation-taxonomy`: optional fields and matcher ordering alone do not provide evidence provenance or structured-first traversal across the exception graph.

**Needed:**
1. Add regression coverage for false-positive classification and nested structured violation extraction before implementing a compatible caller-facing diagnostic contract.
2. Expose explicit evidence provenance: observed policy denial versus inferred permission failure, with unknown attribution represented honestly. A public exception type or errno alone must not be treated as authenticated proof of a kernel verdict or attacker intent.
3. Provide one Java/Kotlin-friendly extraction path through nested causes and suppressed exceptions, including `ExecutionException` and `CompletionException`. Prefer structured details across the graph over outer message heuristics; retain cycle protection and distinguish suppressed secondary failures from the primary failure.
4. Preserve known errno, syscall number, provenance, and original cause through executor normalization. Leave unavailable details unknown; preserve existing constructors and catch compatibility.
5. Document caller handling for `runContained` and `submit(...).get()`, separating installation failures, ordinary task failures, and policy-denial diagnostics. Correct the exception guarantee and document that swallowed errors and process termination cannot be universally reported through this path.

**Acceptance criteria:**
- Generic permission errors, invalid-argument messages, and missing-program `Cannot run` failures are never reported as confirmed mazewall denials solely from their text or exception class.
- A nested structured violation remains extractable with its metadata even when an outer exception matches a heuristic; direct structured exceptions retain their details.
- Cause/suppressed cycles terminate; secondary suppressed denials do not silently replace the primary failure classification.
- Installation errors and ordinary task errors remain distinguishable. Standard `ExecutorService` wrapping, cancellation, and interruption semantics remain intact.
- Java and Kotlin caller examples show classification without message parsing. Tests cover false positives, nested wrappers, metadata preservation, and the visibility limit when task code handles a failure internally.
- Run focused enforcer tests and the repository merge gate during implementation. Kernel verification is required only if scope is explicitly expanded to change kernel enforcement.

**Out of scope:**
Callbacks, IP attribution or banning, automatic incident responses, new USER_NOTIF interception, kernel-policy changes, dependencies, and breaking public API changes. This issue improves diagnostics and propagation; it does not make exceptions a complete attack-detection channel.

---
<!-- id: issue-20260907-094634-079-make-containment-exception-classification-and-extr  file: issue-20260907-094634-079-make-containment-exception-classification-and-extr.md -->
