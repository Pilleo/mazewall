---
title: "`Landlock` getAccessMask missing ABI 4 Support (Net Capabilities)"
severity: "HIGH"
status: "resolved"
priority: 9
dependencies: []
component: "enforcer"
effort: "small"
autonomy: "supervised"
solution_approved: false
blast_radius: "medium"
reversible: true
github_issue: 239
---

# 🔴 [Severity: MEDIUM]: `Landlock` getAccessMask missing ABI 4 Support (Net Capabilities)

**Context:**
**Hypothesis:** Linux Landlock ABI 4 introduced `LANDLOCK_ACCESS_NET_BIND_TCP` and `LANDLOCK_ACCESS_NET_CONNECT_TCP`. The `getAccessMask` and `getFullAccessMask` methods compute access flags for ABI versions up to ABI 5 (e.g. `if (abi >= ABI_V5) mask = mask or LANDLOCK_ACCESS_FS_IOCTL_DEV`), but they completely skip ABI 4 networking capabilities. If a user expects network containment via Landlock on an ABI 4+ kernel, they will not be contained.

`Landlock.kt` defines `getAccessMask`. It checks `abi >= 2` (REFER), `abi >= ABI_V3` (TRUNCATE), and `abi >= ABI_V5` (IOCTL_DEV). There is no check for `abi >= 4` to append network capability masks. Although `createRuleset` checks `if (abi >= 4)` to expand the `rulesetAttr` size to include `handled_access_net`, the actual value written to `handled_access_net` is hardcoded to `0L`: `rulesetAttr.set(ValueLayout.JAVA_LONG, Layouts.LANDLOCK_RULESET_ATTR_NET_OFFSET, 0L)`. Thus, Landlock network containment is silently unsupported/disabled despite ABI 4+ sizing handling.


**Needed:**
1. Document that Landlock ABI 4 network isolation is not supported and rely entirely on Seccomp-BPF for network rules, or implement the ABI 4 `handled_access_net` capability flags.

## Solution Options

### Option A — Refactor implementation
Implement the recommendation described in the Needed section to resolve the issue directly. Target area: ``enforcer/src/main/kotlin/io/mazewall/landlock/Landlock.kt` (specifically `getAccessMask` and `getFullAccessMask`)`
**Pros:** Resolves the root cause of the issue.
**Cons:** Requires careful implementation and testing.
**Risk:** MEDIUM
**Effort:** small

---
**Chosen:** *(not yet approved — requires human decision)*

**Acceptance Criteria:**
- [ ] Tests verify the fix works as expected.
- [ ] Issue is fully resolved in the codebase.

**Implementation Hints:**
- Ensure you read existing tests and implementation carefully before modifying code.
