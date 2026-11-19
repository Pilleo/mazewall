---
title: "Expose and validate aggregate Portal capacity before worker startup"
severity: "MEDIUM"
status: "open"
priority: high
dependencies: []
component: "portal-runtime"
target_modules:
  - ":portals:runtime"
target_files:
  - "portals/runtime/src/main/kotlin/io/mazewall/portal/PortalRuntimeConfig.kt"
  - "portals/runtime/src/main/kotlin/io/mazewall/portal/PortalRuntime.kt"
  - "portals/runtime/src/main/kotlin/io/mazewall/portal/PortalCapacityPlan.kt"
  - "portals/runtime/src/main/kotlin/io/mazewall/portal/PortalCapacityBudget.kt"
  - "portals/runtime/src/test/kotlin/io/mazewall/portal/PortalCapacityPlanTest.kt"
  - "portals/runtime/src/test/kotlin/io/mazewall/portal/PortalRuntimeTest.kt"
  - "portals/runtime/src/test/kotlin/io/mazewall/portal/PortalWorkerProfileTest.kt"
  - "tests/consumers/portal-plugin"
  - "docs/guides/portal-service-isolation.md"
target_symbols:
  - "PortalRuntimeConfig"
  - "PortalRuntime"
open_questions: false
---

# 🟡 [Severity: MEDIUM]: Expose and validate aggregate Portal capacity before worker startup

**Context:**
Portal developer-experience plan P12: per-group worker and cgroup limits do not expose the total cost of all lazy groups. Developers need a pure capacity report and an opt-in pre-launch aggregate process/configured-memory budget, with unknown memory limits explicit and no new process owner.

**Needed:**
1. Derive `PortalRuntimeConfig.capacityPlan()` from the existing immutable config/validated registry. Count all configured lazy groups once: singleton=1, stateless=`limits.workers`. Use checked Long arithmetic for process counts and summed per-worker cgroup ceilings.
2. Return owned immutable group entries and a closed `PortalWorkerMemoryTotal.Limited(bytes)` / `Unbounded(groups)` result. Restrict report construction; a missing cgroup limit is not zero or an estimate from Xmx.
3. Add `PortalCapacityBudget(maxWorkerProcesses, maxConfiguredWorkerMemoryBytes = null)` and Java-static `PortalRuntime.open(config, budget)`. Reject invalid/excess budgets or unknown finite-memory requirements before broker acquisition; preserve every old entrypoint/default bridge.
4. Keep the report pure and the existing runtime the only process owner. Document configured versus observed memory, per-runtime scope, host/sibling/swap headroom, and separate readiness/health evidence.

**Plan and scope:**
- [Implementation plan P12](../../superpowers/plans/2026-11-03-portal-developer-experience.md#p12--report-and-validate-aggregate-capacity-before-startup) and [memory/capacity contract](../designs/portal/portal-developer-experience.md#11-memory-model-and-aggregate-capacity).
- Independent of new generated APIs; P02 consumes the report in examples and P13 qualifies actual process counts. `PortalCapacityPlan.kt`, `PortalCapacityBudget.kt` and `PortalCapacityPlanTest.kt` are planned new files.
- P12 consumers use current explicit registration/configuration and `PortalWorkerProfile()`. They must compile before P02; neither its conventions nor `loadBaseline()` is a verification prerequisite.
- No global allocator, dynamic pool resizing, implicit trust-group merging or cgroup fallback. A configured-limit sum is not a physical-memory reservation or an exact RSS ceiling.
- Existing launch attaches the child after process acquisition and before guest readiness. Linux cgroup migration does not move existing memory charges; parent limits/measurement must cover bootstrap allocations. The report must not advertise child ceilings as complete lifetime accounting.

**Acceptance:**
- Two services in one two-worker stateless group plus one singleton group count three workers. With 128 MiB per-worker ceilings the sum is 384 MiB; it is not multiplied by the service count.
- Exact budgets accept; one-over, invalid values, conflicting/missing profiles and arithmetic overflow reject. Unbounded groups are named and reject a requested finite configured-memory budget.
- Reporting/open rejection has no guest initialization, launch/preparation events or new broker owner. Existing public open/config bridges remain usable from Java/Kotlin.
- Ordinary reporting does not sample OS/environment values, inspect payloads or initialize arbitrary application configuration. Actual cgroup installation/readiness remains fail-closed at launch.

**Verification:**
- [Junior implementation walkthrough: P12](../../superpowers/plans/2026-11-03-portal-developer-experience-execution-guide.md#p12--pure-aggregate-capacity-and-pre-launch-rejection) includes complete manifest/config test builders, an exact-budget/unbounded/overflow matrix, Java constructor details and P02-independent consumer ordering. Metadata-only cgroup paths prove no delegation; retain real P13 startup proof separately.
- Run `PortalCapacityPlanTest`, relevant runtime/profile tests and independent Java/Kotlin consumer compilation using existing explicit registration/configuration. P02 later reuses those cases; P13 supplies real child-count/delegation qualification.
- At implementation completion run `./gradlew build`, `./scripts/adkw check-backlog`, reference checks and `git diff --check`.

---
<!-- id: issue-20261103-101536-305-expose-and-validate-aggregate-portal-capacity-befo  file: issue-20261103-101536-305-expose-and-validate-aggregate-portal-capacity-befo.md -->
