---
title: "Result Monads (No-Throw Error Handling for FFM)"
severity: "HIGH"
status: "resolved"
priority: high
dependencies: []
component: "enforcer"
target_modules:
  - ":enforcer"
  - ":platform"
target_files:
  - "enforcer/src/main/kotlin/io/mazewall/enforcer/api/ContainedExecutors.kt"
  - "enforcer/src/main/kotlin/io/mazewall/landlock/Landlock.kt"
  - "enforcer/src/main/kotlin/io/mazewall/landlock/LandlockApplyResult.kt"
effort: "large"
autonomy: "supervised"
---

# 🟢 [Severity: HIGH]: Result Monads (No-Throw Error Handling for FFM)

**Context:**
`SyscallResult` already wraps raw downcalls. Landlock and `ContainedExecutors` still unpacked with `throwErrno` / discarded `Unit`, so a missed `-1` or a swallowed exception could continue uncontained.

**Resolution:**
- `LandlockApplyResult` (`Applied` / `Bypassed` / `Rejected`) is the sealed install outcome. `orThrow()` fail-closes `Rejected` (EPERM is never success).
- `tryCreateRuleset` / `tryEnforceRuleset` return `SyscallResult` without throwing. Throwing wrappers unpack exhaustively.
- `Landlock.tryApplyRuleset` / `LandlockSession.tryApplyRuleset` fold kernel errno into `Rejected` or operator `Bypassed`.
- `ContainedExecutors.installOnCurrentThread` / `installOnProcess` return `InstallationReceipt`. Landlock `Bypassed` yields `installed = false`. `requireInstalled()` is the fail-closed unpack.

**Needed:** (done for the targeted install/Landlock flows; remaining downcalls already return `SyscallResult`)
