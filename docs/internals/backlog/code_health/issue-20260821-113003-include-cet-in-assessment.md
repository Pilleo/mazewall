---
title: "Include Intel CET support in installation assessment"
severity: "MEDIUM"
status: "open"
priority: medium
dependencies: []
component: "enforcer"
target_modules:
  - ":enforcer"
target_files:
  - "enforcer/src/main/kotlin/io/mazewall/InstallationAssessment.kt"
effort: "small"
autonomy: "autonomous"
related_pr: 512
related_thread: 3819982867
---

# 🟡 [Severity: MEDIUM]: Include Intel CET support in installation assessment

**Context:** When a policy has `lockIntelCet=true` on a non-AMD64 host or a CPU without CET support, this assessment can still return `installable=true` and `requireInstallable()` succeeds. The immediately following installation deterministically reaches `armIntelCet()` and throws under the default `FAIL` fallback, so the new preflight does not accurately predict installability for one of the policy features it exposes.

**Problem:**
- Policy has lockIntelCet=true
- Assessment returns installable=true on non-CET hosts
- Installation throws on armIntelCet()
- Preflight doesn't predict installability

**Impact:**
- Assessment passes but installation fails
- CET feature not properly assessed

**Needed:**
1. Add CET blocking status to assessment
2. Check Platform.cetSupported in assessment
3. Make preflight accurate for all policy features

**Codex PR Comment:** https://github.com/Pilleo/mazewall/pull/512#discussion_r3819982867
