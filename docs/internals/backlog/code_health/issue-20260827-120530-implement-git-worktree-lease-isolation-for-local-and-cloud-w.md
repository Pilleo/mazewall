---
title: "Implement Git Worktree lease isolation for local and cloud worker dispatch"
severity: "MEDIUM"
status: "open"
priority: high
dependencies: []
component: "orchestrator"
target_modules:
  - ":tools:orchestrator"
target_files:
  - "tools/orchestrator/src/main/kotlin/io/mazewall/orchestrator/HybridSupervisor.kt"
  - "tools/orchestrator/src/main/kotlin/io/mazewall/orchestrator/BacklogResolver.kt"
needs_kernel: false
core_lock: false
effort: "medium"
autonomy: "supervised"
open_questions: false
review_verdict: approved
has_side_effects: true
paperclip_issue_id: 1bcf49d1-9b98-47c3-80fd-80f2a107ba62
paperclip_identifier: MAZ-699
---

# 🟡 [Severity: MEDIUM]: Implement Git Worktree lease isolation for local and cloud worker dispatch

**Context:**
The current `HybridSupervisor` dispatches issues to Paperclip agents (Jules, Vibe) which execute in the shared `jseccomp` working tree. This creates a concurrency hazard: concurrent agent sessions or local developer edits on `master` can mutually corrupt each other's dirty state, stash stacks, or index locks.

Git worktree lease isolation addresses this by provisioning a dedicated, detached worktree per dispatched issue (`git worktree add --detach .worktrees/<issueId>`), ensuring each worker operates in a clean, isolated filesystem namespace with automatic lifecycle teardown upon issue resolution.

**Needed:**
1. In `HybridSupervisor.kt`: Add a `WorktreeLeaseManager` class with methods `acquire(issueId: String): Path` and `release(issueId: String)` that manages detached worktrees under `.worktrees/`.
2. In `HybridSupervisor.kt`: Integrate lease acquisition into the dispatch path (`client.startProgress`), passing the isolated worktree path to the worker.
3. In `HybridSupervisor.kt`: Track active leases and wire teardown hooks into issue completion/resolution in `BacklogResolver`.
4. In `BacklogResolver.kt`: Modify `resolveIfNeeded` to accept an optional `worktreePath: Path?` parameter to commit backlog resolutions inside the isolated worktree.
5. Add unit tests in `WorktreeLeaseManagerTest.kt` verifying:
   - Worktree paths are isolated from the repo root.
   - Worktree creation and teardown succeed.
   - Concurrent acquires for the same issue are idempotent.
6. Add integration test in `HybridSupervisorTest.kt` verifying dispatch with worktree isolation.
7. Run `./gradlew :tools:orchestrator:test` and `./gradlew :tools:orchestrator:checkBacklog`.

## Investigation
- tools/orchestrator/src/main/kotlin/io/mazewall/orchestrator/HybridSupervisor.kt
- tools/orchestrator/src/main/kotlin/io/mazewall/orchestrator/BacklogResolver.kt
- tools/orchestrator/src/test/kotlin/io/mazewall/orchestrator/StateHandlerTest.kt
- tools/orchestrator/src/test/kotlin/io/mazewall/orchestrator/BacklogResolverTest.kt
- tools/orchestrator/src/test/kotlin/io/mazewall/orchestrator/HybridSupervisorTest.kt
- docs/internals/backlog/code_health/issue-20260823-181013-isolate-paperclip-agent-worktrees.md
- docs/internals/designs/tools/paperclip-hybrid-migration.md

## Important details
- Worktrees must be createdasdetached HEADtoavoid branchnamecollisions and shellinjection vectors(branchnames cancontainmalicious characters).
- Worktree paths must usecanonical absolutepaths to prevent symlink escapeattacksand mustneverresolveto therepo root or anyparentdirectory.
- Thelease managermust be thread-safe: HybridSupervisor.tick() canbe called concurrentlyindaemonmode withPAPERCLIP_MAX_DISPATCH > 1.
- Worktree cleanup musthappenin a finally blockor equivalenttopreventorphaned worktreesoncrashesor timeouts.
- BacklogResolver must continuetowork forissueswithoutanactive lease(legacypath) byfallingback to repoRoot.
- Paperclip agent configurationmust receivethe worktree pathvia amechanismthat doesn't requireschemachanges (envvaroverrideor cwd parameter).

## Side effects
- Adds newpublicAPI surface(WorktreeLeaseManager) thatwillbe referencedby callers outside:tools:orchestrator ifadopted.
- Modifies BacklogResolver.resolveIfNeeded signature byadding optionalworktreePath parameter,affecting allcall sites.
- Introduces filesystemsideeffects:creationand removalof worktree directories underthe configuredworktree root.
- Changes the runtimeenvironmentfordispatchedagents byoverridingtheirworking directory,whichmay affect agentbehaviorsthat assume therepo root.

---

**Verification:** `./gradlew :tools:orchestrator:checkBacklog` plus the `verify_cheap` commands above (if any).

<!-- id: issue-20260827-120530  file: issue-20260827-120530-implement-git-worktree-lease-isolation-for-local-and-cloud-w.md -->
<!-- Agent: fill Context and Needed; add files/symbols if the impact walk missed them. Do not rename the file. -->
