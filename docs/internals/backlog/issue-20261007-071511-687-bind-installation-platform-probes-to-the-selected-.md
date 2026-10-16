---
title: "Bind installation platform probes to the selected native and provider context"
severity: "HIGH"
status: "open"
priority: high
dependencies: []
component: "core"
target_modules: [enforcer]
target_files:
  - "enforcer/src/main/kotlin/io/mazewall/enforcer/api/Platform.kt"
  - "enforcer/src/main/kotlin/io/mazewall/enforcer/api/CapturedPlatform.kt"
  - "enforcer/src/main/kotlin/io/mazewall/enforcer/api/InstallationProgress.kt"
  - "enforcer/src/main/kotlin/io/mazewall/enforcer/api/ContainedExecutors.kt"
  - "enforcer/src/main/kotlin/io/mazewall/enforcer/internal/ffi/RealPlatformProvider.kt"
  - "enforcer/src/main/kotlin/io/mazewall/landlock/LandlockCapabilityProbe.kt"
  - "enforcer/src/main/kotlin/io/mazewall/seccomp/PureJavaBpfEngine.kt"
  - "enforcer/src/main/kotlin/io/mazewall/seccomp/SeccompInstallationState.kt"
  - "enforcer/src/test/kotlin/io/mazewall/CapturedPlatformContextTest.kt"
  - "enforcer/src/integrationTest/kotlin/io/mazewall/CapturedLandlockBackendNativeTest.kt"
  - "docs/internals/designs/enforcer/containment-design.md"
target_symbols: []
open_questions: false
---

# 🟡 [Severity: HIGH]: Bind installation platform probes to the selected native and provider context

**Context:**
C3 installation pins native traits but Platform.isSupported, real capability probes, CET eligibility and later supervised feature selection still consult independent global provider/native state. Capture one provider selection and bind real probes to the attempt backend; preserve capability failures and resource owners.

**Needed:**
1. Reproduce backend/provider replacement during support and feature probes, then propagate immutable attempt context through required installation.

## Implementation evidence

`CapturedPlatform` retains the selected provider; production real probes use one shared native-parameterized implementation bound to the installing owner. Required support, Linux validation, Landlock capability reporting, CET eligibility/status, USER_NOTIF selection and plain Seccomp privilege/TSYNC validation receive the captured selection. Standalone diagnostics retain their live entry semantics. Custom internal test providers remain responsible for their own probe implementation; no ambient engine override or new enforcement compiler was added.

Two host regressions first failed (`run-ee43c720b2a3`): native replacement during support redirected subsequent real feature probes; provider replacement changed later support/validation. Forwarding capture exposed a remaining global Linux validation in the plain Seccomp state machine, which was migrated to the same captured platform. Focused capture/CET/fallback cases then passed (`run-2fa7855778bc`), as did full enforcer/profiler host, ABI and Detekt checks (`run-635c7770f686`).

Actual-kernel Landlock capture now has three passing cases, zero skips/errors/failures, including provider replacement between Landlock and supervised installation. Full `./gradlew build integrationTest integrationTestFreshJvm` passed in 5m59s, 272 tasks (94 executed, 1 from cache, 177 up-to-date), after correcting a new line-length warning without changing the ratchet (`run-f1fafd615559`). Serial caller-mapped Docker gate passed in 49s (`run-f87619953fa8`).

This is per-attempt platform authority capture. Application-owned prepared-context lifetime and supervisor startup/retirement ownership remain managed-runtime work. The issue remains open pending the repository review path.

---
<!-- id: issue-20261007-071511-687-bind-installation-platform-probes-to-the-selected-  file: issue-20261007-071511-687-bind-installation-platform-probes-to-the-selected-.md -->
