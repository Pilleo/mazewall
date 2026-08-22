---
title: "Include Intel CET support in installation assessment"
severity: "MEDIUM"
status: "resolved"
priority: medium
component: "enforcer"
dependencies: []
target_modules:
  - ":enforcer"
target_files:
  - "enforcer/src/main/kotlin/io/mazewall/InstallationAssessment.kt"
effort: "small"
autonomy: "autonomous"
---

# Include Intel CET Support in Installation Assessment

**Source:** Codex PR review comment (P2)
**Created:** 2026-08-20

## Problem

When a policy has `lockIntelCet=true` on a non-AMD64 host or a CPU without CET support, this assessment can still return `installable=true` and `requireInstallable()` succeeds. The immediately following installation deterministically reaches `armIntelCet()` and throws under the default `FAIL` fallback, so the new preflight does not accurately predict installability for one of the policy features it exposes.

## Impact

- Preflight assessment inaccurate
- Installation fails after assessment passes
- Poor user experience

## Solution

Add a CET blocking stage/reason based on the same platform, architecture, and CPU checks used by installation.

## Related Files

- `enforcer/src/main/kotlin/io/mazewall/InstallationAssessment.kt` - Line 124
