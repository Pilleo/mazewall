---
title: "Qualify out-of-domain syscall attempts by independent evidence source"
severity: "MEDIUM"
status: "open"
priority: high
dependencies: []
component: "core"
target_modules:
  - "profiler"
target_files: 
  - "profiler/src/main/kotlin/io/mazewall/profiler/attribution/AttributionCollector.kt"
target_symbols: 
  - "CaptureDomainEvent"
open_questions: false
---

# 🟡 [Severity: MEDIUM]: Qualify out-of-domain syscall attempts by independent evidence source

**Context:**
An attempt-missed count is meaningful only when an independent source establishes the attempt denominator; zero must not imply complete attempt coverage.

**Needed:**
1. Add evidence-source identity and lower-bound semantics to capture-domain diagnostics without fabricating observed events.

## Acceptance criteria

1. Every `attempt_missed` record identifies the independent evidence source and whether its count is a complete denominator or a lower bound.
2. `attempt_missed=0` never implies complete syscall-attempt coverage without a complete denominator source.
3. Denied-before-`sys_enter` attempts remain boundary diagnostics and never become exact captured events.
4. Final verdicts distinguish capture-domain validity from transport completeness and attribution coverage.

## Required verification

1. Exercise seccomp-denied attempts with an independent attempt source and assert `observed_sys_enter=0` plus explicit limitation metadata.
2. Exercise the no-independent-source case and assert lower-bound semantics.
3. Retain machine-readable capture-domain evidence with `capture_domain_validity` and `attempt_coverage` dimensions.

Verification command: `./gradlew :profiler:test --tests io.mazewall.profiler.attribution.AttributionCollectorTest`.

---
<!-- id: issue-20260913-024414-451-qualify-out-of-domain-syscall-attempts-by-independ  file: issue-20260913-024414-451-qualify-out-of-domain-syscall-attempts-by-independ.md -->
