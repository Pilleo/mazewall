---
title: "Structured Containment-Violation Taxonomy Replacing Message Regexes"
severity: "MEDIUM"
status: "open"
priority: high
component: "enforcer"
target_modules:
  - ":enforcer"
target_files:
  - "enforcer/src/main/kotlin/io/mazewall/enforcer/diagnostics/ContainmentViolationDetector.kt"
  - "enforcer/src/main/kotlin/io/mazewall/enforcer/api/ContainedExecutors.kt"
effort: "medium"
autonomy: "supervised"
open_questions: true
dependencies: []
---

# 🟠 [Severity: MEDIUM]: Structured Containment-Violation Taxonomy Replacing Message Regexes

**Context:** Violation detection currently rests on `isDirectContainmentViolation`'s two-priority
strategy: locale-independent errno regexes (`\berror[=:]\s*(1|13)\b`) plus localized message
fragments for IOException subclasses (`"Operation not permitted"`, `"refusé"`, `"verweigert"`,
`"negado"`). This is brittle against JVM exception-message drift across JDK releases and locales,
produces both false negatives (unlisted locale) and false-positive risk, and cannot distinguish
*which* syscall/facility was denied. The recent errno-constant consolidation
(issue-20260823-135556) removed literal drift but not the structural weakness.

**Needed:**
1. Introduce `ContainmentViolationException(errno: Int, syscallNr: Int?, facility: Facility)` —
   raised at enforcement boundaries where mazewall itself observes the kernel decision (raw syscall
   wrappers already have `SyscallResult.Error` with errno in hand).
2. Keep the regex detector strictly as a FALLBACK for third-party exceptions crossing the boundary,
   demoted behind structured checks; log when fallback fires so coverage gaps surface.
3. Map errno → typed cause (`EACCES`/`EPERM` → access-denied family; `ENOSYS` → blocked-feature)
   using the centralized `NativeConstants`.
4. Update `ContainedExecutors.isContainmentViolation` traversal order: structured causes first,
   detector last; document the precedence in KDoc.
5. Tests: JDK-locale matrix for the legacy path; structured path asserted without any string
   matching.

## ❓ Open Questions
1. Public API impact: does `ContainmentViolationException` gain fields (breaking) or get a subclass?
   Operator previously waived compatibility constraints — confirm full replacement is acceptable.
