---
title: "Keep worker-installing tests in fresh JVMs"
severity: "MEDIUM"
status: "resolved"
priority: medium
dependencies: []
component: "enforcer"
target_modules:
  - ":enforcer"
target_files:
  - "enforcer/build.gradle.kts"
effort: "small"
autonomy: "autonomous"
related_pr: 512
related_thread: 3797199300
---

# 🟡 [Severity: MEDIUM]: Keep worker-installing tests in fresh JVMs

**Review (2026-08-21):** ALREADY FIXED: the installing IntelCetIntegrationTest method is @NeedsFreshJvm; integrationTest excludes that tag and integrationTestFreshJvm uses forkEvery=1.

**Context:** In the enforcer integration pipeline, `forkEvery=0` now shares one worker among all untagged tests, but `IntelCetIntegrationTest` remains untagged and calls `policy.install()` directly on that JUnit worker. On CET-enabled hosts its irreversible seccomp filter persists into later tests and can deny executable mappings or otherwise contaminate results.

**Problem:**
- forkEvery=0 shares worker
- IntelCetIntegrationTest not tagged
- Irreversible seccomp filter persists
- Contaminates later tests

**Impact:**
- Test contamination on CET-enabled hosts
- False failures in later tests

**Needed:**
1. Tag IntelCetIntegrationTest with @NeedsFreshJvm
2. Or isolate CET tests from others

**Codex PR Comment:** https://github.com/Pilleo/mazewall/pull/512#discussion_r3797199300
