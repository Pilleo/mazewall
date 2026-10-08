---
title: "Stable packaged consumer compatibility gates"
severity: "MEDIUM"
status: "open"
priority: high
dependencies: []
component: "core"
target_modules: []
target_files:
  - "tests/consumers/kotlin-api/build.gradle"
target_symbols:
  - "packagedConsumers"
open_questions: false
---

# 🟡 [Severity: MEDIUM]: Stable packaged consumer compatibility gates

**Context:**
Fleet C0/R0 requires independent stable Kotlin and JDK22/25 proof; existing fixture uses producer prerelease compiler and daemon javac.

**Needed:**
1. Integrate the scoped consumer fixture commit through the fleet coordinator.
2. Re-stage final merged artifacts and retain the strict-lock matrix as release evidence.
3. Coordinator may then resolve/index this task-owned issue; backlog README is coordinator-owned.

## Implemented and verified

- Candidate producer revision `39cd5ce060a5310ec64b42de56873bb85ac6b6e6`,
  `io.mazewall:{platform,enforcer}:0.1.0-candidate.39cd5ce0`.
- Actual stable Kotlin compiler **2.4.20** accepted **2.4.20-Beta1** producer
  metadata on JDK **22.0.2+9** and **25.0.4.1.1+1-LTS-jvmci-25.4-b23**.
- Java construction/run, independent uncast Callable compilation, and exact
  process-filesystem/worker-process negative scope diagnostics passed on both JDKs.
- Koog **1.3.0** and Boot **4.1.1** baseline/candidate graphs resolved under new
  strict locks; original baseline locks were preserved.
- Final producer staging ran with no build cache and rerun tasks in the private
  worktree; all staging/checks held the fleet verification lock.
- No stable metadata blocker was reproduced; no root/compiler/core patch is needed.

See [the compatibility manifest](../reviews/integration-compatibility-manifest.md)
and `scripts/check_integration_consumers.sh`. Detailed private logs:
`build/fleet-consumer-evidence/stage-no-cache.log` and
`build/fleet-consumer-evidence/matrix-corrected/jdk{22,25}.log`.
Native packaged and optional framework runtime proof are subsequent coordinated
release gates, not claimed by the policy-only host consumer fixtures.

---
<!-- id: issue-20261008-001530-668-stable-packaged-consumer-compatibility-gates  file: issue-20261008-001530-668-stable-packaged-consumer-compatibility-gates.md -->
