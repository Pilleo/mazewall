---
title: "Process portal granted FDs must use openat2 RESOLVE_BENEATH"
severity: "HIGH"
status: "resolved"
priority: high
dependencies: []
component: "enforcer"
target_modules:
  - ":portal"
target_files:
  - "portal/src/main/kotlin/io/mazewall/portal/PortalChannel.kt"
  - "portal/src/integrationTest/kotlin/io/mazewall/portal/ProcessBrokerIntegrationTest.kt"
effort: "small"
autonomy: "supervised"
open_questions: false
---

# 🔴 [Severity: HIGH]: Process portal granted FDs must use openat2 RESOLVE_BENEATH

**Context:** [process-portal-design.md](../../designs/enforcer/process-portal-design.md) requires the broker to open files with `openat2` + `RESOLVE_BENEATH` before `SCM_RIGHTS`. `openGrantedRead` currently calls `LinuxNative.fileSystem.open` on a string path. That is TOCTOU- and symlink-vulnerable and is the Glassbox `SecurityGate.normalize()` failure mode we refused to copy.

**Needed:**
1. Open under an explicit root FD with `openat2(..., RESOLVE_BENEATH)` (or equivalent `openat` + `O_NOFOLLOW` only if `openat2` is proven unavailable and then fail closed).
2. Integration test: symlink escaping the allowed root must not yield a granted FD.
3. Do not send path strings to the worker.
