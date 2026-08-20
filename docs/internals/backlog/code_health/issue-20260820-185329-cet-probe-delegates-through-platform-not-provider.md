---
title: "CET support is probed twice: provider matrix vs Platform.isCpuCetSupported"
severity: "MEDIUM"
status: "resolved"
priority: medium
dependencies: []
component: "enforcer"
target_modules:
  - ":enforcer"
target_files:
  - "enforcer/src/main/kotlin/io/mazewall/RealPlatformProvider.kt"
  - "enforcer/src/main/kotlin/io/mazewall/Platform.kt"
  - "enforcer/src/main/kotlin/io/mazewall/enforcer/api/ContainedExecutors.kt"
  - "enforcer/src/main/kotlin/io/mazewall/InstallationAssessment.kt"
effort: "small"
autonomy: "autonomous"
---

# 🟡 [Severity: MEDIUM]: CET support is probed twice: provider matrix vs Platform.isCpuCetSupported

**Context:** `KernelFeatureMatrix.cetSupported` is filled from `PlatformProvider.probeCetSupported()`. `RealPlatformProvider.probeCetSupported()` does not probe the CPU/kernel itself; it calls `Platform.isCpuCetSupported()`, which reads `/proc/cpuinfo` and `ARCH_SHSTK_STATUS` and has its own cache plus `isCpuCetSupportedOverride`. `InstallationAssessor` gates `lockIntelCet` on `matrix.cetSupported`. `ContainedExecutors.armIntelCet()` still gates on `Platform.isCpuCetSupported()` and ignores the matrix.

With `RealPlatformProvider` the two paths usually agree. With `MockPlatformProvider.mockCetSupported` they do not: assessment can block or allow CET while arming still consults the real CPU/override. `setProvider()` clears `cachedMatrix` but does not clear `isCpuCetSupportedCached`. That is a DIP inversion (Platform implements the probe; the provider only forwards) and a test/fault-injection hole.

**Needed:**
1. Put the CET probe implementation on `PlatformProvider` (cpuinfo + `arch_prctl`). `Platform.isCpuCetSupported()` should read `featureMatrix.cetSupported` (or call `provider.probeCetSupported()` once).
2. Make `armIntelCet()` use the same source as `InstallationAssessor` (`featureMatrix.cetSupported`).
3. Clear `isCpuCetSupportedCached` in `setProvider` / `resetToDefault`, or delete that cache once the matrix is the sole source.
4. Tests: mock `probeCetSupported=false` must make both `assess()` and `armIntelCet()` fail closed; override/cache must not outlive `setProvider()`.
