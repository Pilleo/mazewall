---
title: "Move SeccompInstallationState ordering into the sealed hierarchy"
severity: "MEDIUM"
status: "open"
priority: high
dependencies: []
component: "enforcer"
target_modules:
  - ":enforcer"
target_files:
  - "enforcer/src/main/kotlin/io/mazewall/seccomp/SeccompInstallationState.kt"
target_symbols:
  - "SeccompInstallationState"
needs_kernel: true
core_lock: false
effort: "medium"
autonomy: "supervised"
open_questions: false
paperclip_issue_id: de005944-bdcf-4571-8c4e-cd2c4350ee42
---

# 🟡 [Severity: MEDIUM]: Move SeccompInstallationState ordering into the sealed hierarchy

**Context:**
The partial order over `SeccompInstallationState` variants ("which state is stronger") is implemented as a private `stateRank(): Int` mapping inside `ContainmentStateRegistry.kt:129-139`, far from the type it ranks. The merge logic (`mergeEngineStates`, `ContainmentStateRegistry.kt:120-127`) uses these ranks to decide which engine state survives a thread/process state merge — this is security-relevant semantics (e.g. `Verified` (5) must dominate `Failed` (1)). If a new variant is added to the sealed hierarchy, compiler exhaustiveness forces an update here, but nothing forces the *ordering value* to be correct; an arbitrary insertion rank silently corrupts merge precedence. Domain knowledge about a type's lifecycle ordering belongs on the type itself.

**Needed:**
1. Move the ordering onto `SeccompInstallationState` in `enforcer/src/main/kotlin/io/mazewall/seccomp/SeccompInstallationState.kt`: add an open `val rank: Int` (or an explicit ordered comparison such as `fun dominates(other): Boolean`) with a KDoc table fixing the intended order: Uninitialized(0) < Failed(1) < FilterBuilt(2) < PrivilegesLocked(3) < SystemCallApplied=FallbackPrctlApplied(4) < Verified(5).
2. Replace `ContainmentStateRegistry.stateRank()` and `mergeEngineStates()` with `maxByOrNull { it.rank }` (or the explicit comparison), deleting the remote mapping.
3. Add a unit test asserting the full expected ordering of all variants (fails if a future variant is inserted with a wrong rank).
4. Run `./gradlew :enforcer:test`.

---

**Verification:** `./gradlew :tools:orchestrator:checkBacklog` plus the `verify_cheap` commands above (if any).

<!-- id: issue-20260826-102539  file: issue-20260826-102539-move-seccompinstallationstate-ordering-into-the-sealed-hiera.md -->
<!-- Agent: fill Context and Needed; add files/symbols if the impact walk missed them. Do not rename the file. -->
