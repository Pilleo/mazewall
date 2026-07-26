---
title: "🟢 [RESOLVED]: Nested Seccomp Stacking Security Containment Bypass on already-blocked Syscalls"
severity: "RESOLVED"
status: "resolved"
---

# 🟢 [RESOLVED]: Nested Seccomp Stacking Security Containment Bypass on already-blocked Syscalls

**Target:** `io.mazewall.enforcer.FilterInstallationPlanner`
**Failure Hypothesis:** When a user stacked policy contains a more restrictive or more severe action for a syscall that is already blocked by a previously applied policy, the planner incorrectly skips the filter installation under a false optimization path because it only checks if the syscall is "blocked".
**Context & Proof:** `FilterInstallationPlanner.calculateNewFilter` calculates `newBlocks = blockedInPolicy - state.currentlyBlocked`. Any syscall with an action priority > ACT_ALLOW is in `blockedInPolicy`. If a syscall (e.g. `EXECVE`) was blocked by Policy 1 with a lenient action (like `ACT_LOG`), `currentlyBlocked` already contains it. When Policy 2 is nestedly stacked to block `EXECVE` with a severe action (like `ACT_KILL_PROCESS`), `newBlocks` evaluates to empty because it was already blocked. As a result, the optimizer sets `needsNewFilter` to `false`, silently skipping the installation of the second filter. The thread continues executing with only the weaker `ACT_LOG` filter in place, completely bypassing the intended `ACT_KILL_PROCESS` containment.
**Cascading Risk Potential:** High security containment bypass. A stacked policy that is intended to restrict thread capabilities further is ignored, causing RCE/compromised code to execute under weaker sandbox rules than designed.
**Fix:** Modified `currentlyBlocked` to track `Map<Syscall, SeccompAction>` rather than `Set<Syscall>`. In `calculateNewFilter`, `newBlocks` now includes any syscall in the new policy that maps to a *higher priority (more restrictive) action* than the currently installed action for that syscall.
