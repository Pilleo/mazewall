---
title: "Replace Hidden Policy Defaults with Runtime-Aware Baselines"
severity: "MEDIUM"
status: "resolved"
priority: high
dependencies:
  - "issue-20260808-025037"
component: "enforcer"
target_modules:
  - ":enforcer"
target_files:
  - "enforcer/src/main/kotlin/io/mazewall/Policy.kt"
  - "enforcer/src/main/kotlin/io/mazewall/PolicyBuilder.kt"
  - "enforcer/src/main/kotlin/io/mazewall/PolicyPresets.kt"
effort: "large"
autonomy: "supervised"
---

# 🟡 [Severity: MEDIUM]: Replace Hidden Policy Defaults with Runtime-Aware Baselines

**Context:** Presets such as `NO_EXEC` and `NO_NETWORK` inherit restrictive builder flags that are not expressed by their names, including executable-memory and clone/prctl inspection. Application developers must understand HotSpot and Native Image internals and discover exception switches such as `allowMmapExec()` to construct a viable process baseline.

**Needed:** With approval for API changes, add explicit `RuntimeProfile` choices and capability-named process/thread policy factories. Provide tested HotSpot JIT and Native Image baseline profiles whose effective syscall argument rules are inspectable. Move raw compatibility switches to an advanced API, eliminate hidden security-relevant defaults, and add integration tests for post-installation JIT expansion and AOT W^X configurations.

**Resolution:** `RuntimeProfile.HOTSPOT_JIT` / `NATIVE_IMAGE` plus `ProcessPolicies.denyProcessCreation` / `denyNetwork` make executable-mapping policy explicit. `Policy.argumentRules` exposes mmap/clone/prctl inspections. `allowMmapExec()` is documented as advanced. `Policy.NO_EXEC_NATIVE_IMAGE` names the W^X preset. Legacy `NO_NETWORK` still defaults to W^X. JIT post-install coverage remains in `ProcessContainmentTest`. Full Mazewall.start DSL is issue 032523.
