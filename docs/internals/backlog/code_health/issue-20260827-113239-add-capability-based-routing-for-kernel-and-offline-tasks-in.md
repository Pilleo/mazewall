---
title: "Add capability-based routing for kernel and offline tasks in ComponentRouter"
severity: "LOW"
status: "open"
priority: medium
dependencies: []
component: "orchestrator"
target_modules:
  - ":tools:orchestrator"
target_files:
  - "tools/orchestrator/src/main/kotlin/io/mazewall/orchestrator/ComponentRouter.kt"
  - "tools/orchestrator/src/main/kotlin/io/mazewall/orchestrator/HybridSupervisor.kt"
needs_kernel: false
core_lock: false
effort: "medium"
autonomy: "supervised"
open_questions: false
review_verdict: approved
has_side_effects: true
paperclip_issue_id: 3e7d4466-653e-4bfa-84a5-f91d220cb727
paperclip_identifier: MAZ-695
---

# 🟢 [Severity: LOW]: Add capability-based routing for kernel and offline tasks in ComponentRouter

**Context:**
The current `ComponentRouter` implements purely component-based routing via a static map of component names (`enforcer`, `profiler`, `orchestrator`, `docs`, `ci`, `testing`, `platform`) to Paperclip agent urlKeys, with all unmatched components falling back to the default adapter. However, the orchestrator tracks additional task metadata beyond component—specifically `needs_kernel` for seccomp/Landlock/BPF work and an implicit offline capability for tasks that require no external service access.

Without capability-based routing, kernel-requiring tasks risk assignment to agents lacking nested container privileges, and offline-only tasks may be dispatched to cloud-only workers.

**Needed:**
1. In `ComponentRouter.kt`: Add `capabilityRoutes: Map<String, String>` parameter to constructor, stored alongside `routes`.
2. In `ComponentRouter.kt`: Add `urlKeyForCapability(capability: String?): String?` method to resolve capability-to-urlKey mapping.
3. In `ComponentRouter.kt`: Add `capabilitiesOf(description: String?): Set<String>` method to extract capability tags from issue descriptions (`needs_kernel` frontmatter).
4. In `ComponentRouter.kt`: Modify `urlKeyFor(component: String?)` to check capability routes first before falling back to component routes.
5. In `HybridSupervisor.kt`: Update router configuration in `main()` to parse `PAPERCLIP_CAPABILITY_ROUTES` environment variable.
6. In `HybridSupervisor.kt`: In `tick()`, extract capabilities from `candidate.description` using `router.capabilitiesOf()` and route accordingly.
7. Add unit tests in `ComponentRouterTest.kt` verifying capability precedence over component routes.
8. Add integration test in `HybridSupervisorTest.kt` verifying `needs_kernel: true` tasks route to kernel-capable agents.
9. Run `./gradlew :tools:orchestrator:test` and `./gradlew :tools:orchestrator:checkBacklog`.

## Investigation
- tools/orchestrator/src/main/kotlin/io/mazewall/orchestrator/ComponentRouter.kt
- tools/orchestrator/src/main/kotlin/io/mazewall/orchestrator/HybridSupervisor.kt
- tools/orchestrator/src/test/kotlin/io/mazewall/orchestrator/HybridSupervisorTest.kt
- tools/orchestrator/src/test/kotlin/io/mazewall/orchestrator/ComponentRouterTest.kt
- tools/orchestrator/src/main/kotlin/io/mazewall/orchestrator/BacklogParser.kt
- tools/orchestrator/src/main/kotlin/io/mazewall/orchestrator/BacklogValidator.kt

## Important details
- Capability routingmust takeprecedence over component routing: anenforcer task with needs_kernel=trueshouldroute to akernel-capable agentifoneisconfigured, notto thedefault enforcer urlKey.
- Allcapabilitylookups must be case-insensitive and whitespace-tolerant,consistentwith existing component routing.
- Missingcapabilityroutes mustfailclosed (no silentEPERM/EACCES bypass).If noagent supportsarequired capability, dispatchmustabort with a clearerror message referencingPAPERCLIP_CAPABILITY_ROUTES.
- The existingPAPERCLIP_COMPONENT_ROUTESmechanismmustremain unchanged andoperational; capabilityroutes areadditive,not areplacement.
- Operator policy:loopwork remainsrestrictedto vibe/jules adapters plusPAPERCLIP_EXTRA_LOOP_ADAPTERS. Capability-basedagents must alsopasstheallowedLoopAdapters check inHybridSupervisor.tick().

## Side effects
- ABI change toComponentRouter constructor (newcapabilityRoutesparameter).
- Behavioralchange inHybridSupervisor.tick() routinglogic (capability-firstresolution).
- Newenvironment variable PAPERCLIP_CAPABILITY_ROUTES affectsruntimedispatch behavior.
- ExistingtestsinComponentRouterTest andHybridSupervisorTestmayneed updatesiftheyassumepurelycomponent-based routing.

---

**Verification:** `./gradlew :tools:orchestrator:checkBacklog` plus the `verify_cheap` commands above (if any).

<!-- id: issue-20260827-113239  file: issue-20260827-113239-add-capability-based-routing-for-kernel-and-offline-tasks-in.md -->
<!-- Agent: fill Context and Needed; add files/symbols if the impact walk missed them. Do not rename the file. -->
