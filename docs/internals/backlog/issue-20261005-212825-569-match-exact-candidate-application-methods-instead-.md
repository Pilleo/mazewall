---
title: "Match exact candidate application methods instead of substrings"
severity: "MEDIUM"
status: "open"
priority: high
dependencies: []
component: "profiler"
target_modules: [profiler]
target_files:
  - "experiments/hotspot-agentless/internal/candidate/verify.go"
target_symbols:
  - "routeMatches"
open_questions: false
---

# 🟡 [Severity: MEDIUM]: Match exact candidate application methods instead of substrings

**Context:**
Review reproduces WrongFixture.leafCorrupt and WrongFixture.routeACorrupt counted as correct Fixture leaf and routeA; Loom matcher has the same weakness

**Needed:**
1. Add false-useful route reproductions and parse exact owner method names before ordered comparison

**Execution:** Exact renderer owner/method and ordered-prefix regressions now pass
in the Go verifier; see [remediation evidence](../../plans/2026-10-05-hotspot-review-remediation-evidence.md).
Retained packet revalidation remains required before archival.

---
<!-- id: issue-20261005-212825-569-match-exact-candidate-application-methods-instead-  file: issue-20261005-212825-569-match-exact-candidate-application-methods-instead-.md -->
