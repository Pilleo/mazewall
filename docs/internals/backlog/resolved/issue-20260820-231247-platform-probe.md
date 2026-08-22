---
title: "Check the platform before probing Linux kernel features"
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

# Check the Platform Before Probing Linux Kernel Features

**Source:** Codex PR review comment (P2)
**Created:** 2026-08-20

## Problem

On a non-Linux host or an architecture unsupported by `Arch.current()`, `assess()` resolves `Platform.featureMatrix` before recording the platform failure. Matrix resolution immediately invokes Linux-specific seccomp, Landlock, and CET probes, including `probeSeccompFlag`, so the supposedly diagnostic preflight can throw or issue unrelated native syscall numbers instead of returning `installable=false` with a `PLATFORM`/`SECCOMP` reason.

## Impact

- Preflight throws exceptions on non-Linux platforms
- Misleading error messages
- Poor diagnostic experience

## Solution

Short-circuit unsupported platforms before resolving the matrix, or make every provider probe platform-safe.

## Related Files

- `enforcer/src/main/kotlin/io/mazewall/InstallationAssessment.kt` - Line 64
