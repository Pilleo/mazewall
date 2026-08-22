---
title: "Require shadow-stack support for the CET probe"
severity: "MEDIUM"
status: "open"
priority: medium
component: "enforcer"
dependencies: []
target_modules:
  - ":enforcer"
target_files:
  - "enforcer/src/main/kotlin/io/mazewall/RealPlatformProvider.kt"
effort: "small"
autonomy: "autonomous"
---

# Require Shadow-Stack Support for the CET Probe

**Source:** Codex PR review comment (P2)
**Created:** 2026-08-20

## Problem

On an x86 CPU that exposes IBT but not shadow stacks, this reports CET support even though `lockIntelCet` specifically enables `ARCH_SHSTK_SHSTK`; the status syscall only verifies that the kernel interface exists, so assessment can report `installable=true` before installation fails at `ARCH_SHSTK_ENABLE`. Fresh evidence after the earlier CET-assessment fix is that the new provider probe explicitly accepts the independent `ibt` feature as a substitute for `shstk`; require the shadow-stack CPU flag for this policy capability.

## Impact

- CET assessment reports supported when only IBT is available
- Installation fails at ARCH_SHSTK_ENABLE
- Misleading preflight results

## Solution

Require the shadow-stack CPU flag for `lockIntelCet` policy capability. The probe should check for both IBT and SHSTK support.

## Related Files

- `enforcer/src/main/kotlin/io/mazewall/RealPlatformProvider.kt` - Line 99
