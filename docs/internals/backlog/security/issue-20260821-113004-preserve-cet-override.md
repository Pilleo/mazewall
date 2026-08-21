---
title: "Preserve the CET capability override during installation"
severity: "HIGH"
status: "open"
priority: high
dependencies: []
component: "enforcer"
target_modules:
  - ":enforcer"
target_files:
  - "enforcer/src/main/kotlin/io/mazewall/enforcer/api/ContainedExecutors.kt"
effort: "small"
autonomy: "autonomous"
related_pr: 512
related_thread: 3825290317
---

# 🔴 [Severity: HIGH]: Preserve the CET capability override during installation

**Context:** On hosts without CET, this now ignores `Platform.isCpuCetSupportedOverride`, so the existing mocked-success CET tests fail before reaching their mocked `arch_prctl` calls. Reproduced both failures with `./gradlew :enforcer:test`: `armIntelCet enables locks...` and `armIntelCet is idempotent...` throw `UnsupportedPlatformException`.

**Problem:**
- Platform.isCpuCetSupportedOverride ignored
- Mocked-success CET tests fail
- armIntelCet throws UnsupportedPlatformException
- Tests broken

**Impact:**
- CET tests fail on hosts without CET
- Test suite broken

**Needed:**
1. Use Platform.isCpuCetSupportedOverride for testing
2. Preserve override capability during installation
3. Fix CET tests

**Codex PR Comment:** https://github.com/Pilleo/mazewall/pull/512#discussion_r3825290317
