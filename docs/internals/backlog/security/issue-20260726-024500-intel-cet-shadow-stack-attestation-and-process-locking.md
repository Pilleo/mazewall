---
title: "Implement Intel CET Shadow Stack Attestation and Process Locking via sys_arch_prctl FFM"
severity: "ENHANCEMENT"
status: "open"
priority: 8
component: "enforcer"
target_modules: [":enforcer"]
target_files: []
effort: "medium"
dependencies: []
github_issue: 324
---

# 🔵 [Severity: ENHANCEMENT]: Implement Intel CET Shadow Stack Attestation and Process Locking via sys_arch_prctl FFM

**Context:**
While modern OpenJDK runtimes (JDK 21+) support the `-XX:+UseCET` flag for JIT compiler `ENDBR64` instruction generation, the JVM runtime deliberately does not issue a Linux kernel-level `arch_prctl(ARCH_SHSTK_LOCK)` call by default. This is to maintain compatibility with dynamic native library loading (JNI/FFM `dlopen`) and native profilers (`async-profiler`). Consequently, OpenJDK delegates kernel-level configuration locking to application security frameworks. Without a kernel-level lock, an attacker who achieves localized native memory write or calling primitives could issue an `arch_prctl(ARCH_SHSTK_DISABLE)` system call to turn off CET hardware enforcement mid-execution. Furthermore, `mazewall` currently lacks runtime attestation to verify whether the host CPU and kernel have hardware Shadow Stack enabled before arming thread or process sandboxes.

**Needed:**
1. Add `sys_arch_prctl` downcall handle bindings in `LinuxNative.kt` for x86_64 architecture:
   - `ARCH_SHSTK_ENABLE` (`0x5001`)
   - `ARCH_SHSTK_DISABLE` (`0x5002`)
   - `ARCH_SHSTK_LOCK` (`0x5003`)
   - `ARCH_SHSTK_STATUS` (`0x5004`)
   - `ARCH_SHSTK_SHSTK` (`0x1`)
2. Extend `Platform.kt` and `Policy.install()` in `:enforcer` to query kernel CET status via `ARCH_SHSTK_STATUS`.
3. Provide a policy builder option (`Policy.builder().lockIntelCet()`) that issues `ARCH_SHSTK_ENABLE` and `ARCH_SHSTK_LOCK` during sandbox arming, permanently locking CET shadow stack configuration for the remainder of the process lifetime.
4. Integrate with `FallbackBehavior.FAIL` so that high-security policy configurations fail fast if CET is explicitly required by policy but unsupported by host CPU/kernel.
