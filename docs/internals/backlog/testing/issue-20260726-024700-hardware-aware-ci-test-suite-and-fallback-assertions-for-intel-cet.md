---
title: Hardware-Aware CI Test Suite and Graceful Fallback Assertions for Intel CET
severity: ENHANCEMENT
status: open
priority: medium
component: enforcer
target_modules:
- :enforcer
target_files:
- enforcer/src/main/kotlin/io/mazewall/ffi/internal/RealNativeEngine.kt
effort: medium
dependencies: []
github_issue: 360
paperclip_issue_id: dfce59da-eb1a-4982-88b6-2f27fb80d5cf
---

# 🔵 [Severity: ENHANCEMENT]: Hardware-Aware CI Test Suite and Graceful Fallback Assertions for Intel CET

**Context:**
Intel CET Shadow Stack hardware enforcement requires specific CPU capabilities (Intel 11th Gen+ / AMD Zen 3+) and Linux Kernel 6.6+. CI/CD runners (e.g. GitHub Actions, virtualized OCI containers) may execute on non-CET host CPUs, older Linux LTS kernels (e.g. 5.15, 6.1), or nested virtual machines where CET CPU flags are masked. If CET integration tests run unconditionally in CI without CPU/kernel capability detection, non-CET runners will fail unexpectedly or produce false negatives.

**Needed:**
1. Implement runtime hardware detection in `Platform.kt` to check `/proc/cpuinfo` for `shstk` / `ibt` flags and query `sys_arch_prctl(ARCH_SHSTK_STATUS)`.
2. Annotate CET integration tests with `@EnabledIfCetSupported` to dynamically run hardware `#CP` exception assertions when running on CET-capable host runners (Linux 6.6+ on CET CPUs).
3. On non-CET test environments, verify that `Policy.install()` gracefully degrades based on `FallbackBehavior`:
   - Under `FallbackBehavior.FAIL`, it throws a clear `UnsupportedPlatformException` explaining the missing kernel/CPU capability.
   - Under `FallbackBehavior.WARN_AND_BYPASS`, it logs a structured warning and bypasses hardware CET locking while retaining Seccomp and Landlock protections.
4. Update `./scripts/run_containerized_tests.sh` to propagate CET CPU flags (`x86_64-v4` or pass-through host CPU flags) into Podman container test runners.
