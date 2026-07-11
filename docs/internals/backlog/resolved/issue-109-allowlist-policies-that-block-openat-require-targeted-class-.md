---
title: "ALLOW_LIST policies that block `openat` require targeted class pre-loading"
severity: "RESOLVED"
status: "resolved"
---

# ✅ [RESOLVED]: ALLOW_LIST policies that block `openat` require targeted class pre-loading

**Target:** `AllowListTest.preWarm()`, `design-specs/containment-design.md §3g`
**Context:** When `defaultAction = ACT_ERRNO` (ALLOW_LIST), `openat` is blocked unless explicitly in the allow set. Classes referenced by `PureJavaBpfEngine` immediately after filter installation (specifically `SeccompInstallationState$Failed`) are loaded lazily via `openat`. After the filter blocks `openat`, these classes can no longer be loaded → `NoClassDefFoundError`. The old `JitWarmup` attempted to solve this globally but was fragile and non-deterministic. The correct fix is targeted: explicitly touch the exact class graph that will be used post-installation, in the specific test/component that uses the restrictive ALLOW_LIST policy.
**Fix:** Extended `AllowListTest.preWarm()` to touch all `SeccompInstallationState` subclasses before the filter is installed. Added `§3g` to `design-specs/containment-design.md` documenting the rule and its scope.
