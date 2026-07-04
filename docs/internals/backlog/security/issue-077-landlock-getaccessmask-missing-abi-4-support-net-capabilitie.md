---
title: "`Landlock` getAccessMask missing ABI 4 Support (Net Capabilities)"
severity: "HIGH"
status: "open"
---

# 🔴 [Severity: MEDIUM]: `Landlock` getAccessMask missing ABI 4 Support (Net Capabilities)

*   **Dimension:** FFM ABI / OS Invariants
*   **Target Area:** `enforcer/src/main/kotlin/io/mazewall/landlock/Landlock.kt` (specifically `getAccessMask` and `getFullAccessMask`)
*   **Failure Hypothesis:** Linux Landlock ABI 4 introduced `LANDLOCK_ACCESS_NET_BIND_TCP` and `LANDLOCK_ACCESS_NET_CONNECT_TCP`. The `getAccessMask` and `getFullAccessMask` methods compute access flags for ABI versions up to ABI 5 (e.g. `if (abi >= ABI_V5) mask = mask or LANDLOCK_ACCESS_FS_IOCTL_DEV`), but they completely skip ABI 4 networking capabilities. If a user expects network containment via Landlock on an ABI 4+ kernel, they will not be contained.
*   **Context & Proof:** `Landlock.kt` defines `getAccessMask`. It checks `abi >= 2` (REFER), `abi >= ABI_V3` (TRUNCATE), and `abi >= ABI_V5` (IOCTL_DEV). There is no check for `abi >= 4` to append network capability masks. Although `createRuleset` checks `if (abi >= 4)` to expand the `rulesetAttr` size to include `handled_access_net`, the actual value written to `handled_access_net` is hardcoded to `0L`: `rulesetAttr.set(ValueLayout.JAVA_LONG, Layouts.LANDLOCK_RULESET_ATTR_NET_OFFSET, 0L)`. Thus, Landlock network containment is silently unsupported/disabled despite ABI 4+ sizing handling.
*   **Cascading Risk Potential:** Medium feature gap and potential security evasion if developers rely solely on Landlock for network isolation instead of Seccomp-BPF.
*   **Recommendation:** Document that Landlock ABI 4 network isolation is not supported and rely entirely on Seccomp-BPF for network rules, or implement the ABI 4 `handled_access_net` capability flags.
