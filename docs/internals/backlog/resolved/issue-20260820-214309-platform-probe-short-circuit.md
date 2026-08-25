---
title: "Check the platform before probing Linux kernel features"
severity: "MEDIUM"
status: "resolved"
priority: high
dependencies: []
component: "enforcer"
target_modules:
  - ":enforcer"
target_files:
  - "enforcer/src/main/kotlin/io/mazewall/InstallationAssessment.kt"
  - "enforcer/src/main/kotlin/io/mazewall/Platform.kt"
effort: "small"
autonomy: "autonomous"
related_pr: 512
related_thread: PRRC_kwDOScnnEM7kBdW
---

# 🟠 [Severity: MEDIUM]: Check the platform before probing Linux kernel features

**Context:** On a non-Linux host or an architecture unsupported by `Arch.current()`, `assess()` resolves `Platform.featureMatrix` before recording the platform failure. Matrix resolution immediately invokes Linux-specific seccomp, Landlock, and CET probes, including `probeSeccompFlag`, so the supposedly diagnostic preflight can throw or issue unrelated native syscall numbers instead of returning `installable=false` with a `PLATFORM`/`SECCOMP` reason.

**Problem:**
- assess() resolves Platform.featureMatrix before platform check
- Matrix resolution triggers Linux-specific probes
- probeSeccompFlag throws on non-Linux or unsupported architecture
- Preflight diagnostic throws instead of returning clean failure

**Impact:**
- Assessment fails with native syscall errors instead of clean platform message
- Error messages are confusing and unrelated to actual issue
- Hard to diagnose platform compatibility issues

**Needed:**
1. Short-circuit unsupported platforms before resolving the matrix
2. Make every provider probe platform-safe
3. Return installable=false with PLATFORM/SECCOMP reason for platform issues

**Codex PR Comment:** https://github.com/Pilleo/mazewall/pull/512#discussion_r3825587202
