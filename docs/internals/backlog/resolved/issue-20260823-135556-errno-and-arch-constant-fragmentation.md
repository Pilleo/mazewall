---
title: "Errno Constant Fragmentation and Non-Exhaustive Arch Guards"
severity: "LOW"
status: "resolved"
priority: medium
component: "enforcer"
target_modules:
  - ":enforcer"
target_files:
  - "enforcer/src/main/kotlin/io/mazewall/landlock/Landlock.kt"
  - "enforcer/src/main/kotlin/io/mazewall/Platform.kt"
  - "enforcer/src/main/kotlin/io/mazewall/BpfFilter.kt"
effort: "small"
autonomy: "autonomous"
open_questions: false
dependencies: []
---

# 🟡 [Severity: LOW]: Errno Constant Fragmentation and Non-Exhaustive Arch Guards

**Context:** Two futureproofness gaps coexist around native constants:

1. **Errno fragmentation.** `NativeConstants` defines `EPERM`, but errno values are privately
   duplicated elsewhere: `ERRNO_EINVAL = 22` in `Landlock.kt:119` *and* `Platform.kt:85`;
   `ERRNO_ELOOP = 40` in `Landlock.kt:120`. New code cannot tell where errno constants should live,
   risking divergent or mistyped literals in security checks (e.g. the seccomp sanity check compares
   against EINVAL).
2. **Arch-specific guards via equality tests and magic numbers.** `BpfFilter.getJvmCriticalNrs`
   hardcodes `nrs.add(158) // arch_prctl` behind `if (arch == Arch.AMD64)` (BpfFilter.kt:194-196)
   instead of a `Syscall.ARCH_PRCTL.numberFor(arch)` lookup; CET capability guards repeat
   `arch == Arch.AMD64` equality ladders in three places (`Platform.isKernelCetSupported:178`,
   `Platform.queryIntelCetStatus:221`, `ContainedExecutors.armIntelCet:434`). Adding an architecture
   requires finding every scattered site; a missed one silently degrades containment.

**Needed:**
1. Consolidate all errno literals into `io.mazewall.ffi.NativeConstants` (or a dedicated `Errno`
   value-class wrapper set, aligning with phantom-type issue #028 direction); replace private copies.
2. Replace the literal `158` with a `Syscall.ARCH_PRCTL` enum entry resolved via `numberFor(arch)`.
3. Introduce capability flags on `Arch` itself (e.g. `val supportsCetShadowStack: Boolean`,
   `val supportsArchPrctl: Boolean`) and use them instead of scattered `== Arch.AMD64` checks.
4. Add unit tests asserting `getJvmCriticalNrs` for every `Arch.entries` value contains arch_prctl on
   architectures that provide it.

