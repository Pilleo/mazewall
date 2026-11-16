---
title: "B5 D1 retain counted owner during live capture"
severity: "HIGH"
status: "open"
priority: high
dependencies: []
component: "core"
target_modules: ["enforcer"]
target_files:
  - "enforcer/src/main/kotlin/io/mazewall/enforcer/api/execution/ManagedRuntime.kt"
target_symbols:
  - "ManagedRuntime"
open_questions: false
---

# 🟡 [Severity: HIGH]: B5 D1 retain counted owner during live capture

**Context:**
Deliver the accepted B5 D1 owner-bearing capture and same-manager retirement under the existing runtime gate; parent issue-20261103-085251-359. The platform-only increment remains separately tracked by issue-20261107-145307-348.

**Needed:**
1. Implement the reviewed 061 owner-bearing D1 unit: admission under the existing runtime gate before effects; exact captured environment snapshot; attach the original staging owner before enrollment/Seal and retain it in finally before quiescence; preallocate and atomically publish Ready with borrowed-to-owned resource transfer.
2. Extend the existing B4 same-manager drain/close path to preserve Pending, Unknown, original failures and physical retirement truth; never retire during construction, invent ownership, spin retries or lose borrowed originals.
3. Use the accepted private capture interpreter/afterReserve hook only with checked bootstrap on its production path and test-only reflection for synthetic effects. Test actual runtime races and owner retention with behavioral RED/GREEN; synthetic transport does not prove JNI or readiness.
4. Add the approved opaque public PreparedStackScoping/close boundary; independently inspect actual JVM and generated ABI delta and maintain the checked-in dump only after controller review. No new dependency or unreviewed default/provider change.
5. Run focused owning tests, enforcer host suite, ABI/static checks and a later separately authorized full build; retain XML/evidence and independent review. Preserve all dirty work and no staging, commit, archive or worktree.

**Constraints / exclusions:**
Parent broad issue-20261103-085251-359 remains open; the separate platform increment issue-20261107-145307-348 remains open. Operator approved the public class and necessary API evolution, but this issue does not release the D2 ManagedRuntime.prepare overload, claim/transfer, real native activation, helper readiness, B6 or qualification. Full060 applies only to its frozen bytes; issue752 retirement and rollback013649-338 remain unresolved. Implementation requires controller baseline review and an exclusive bounded release.

---
<!-- id: issue-20261107-200628-759-b5-d1-retain-counted-owner-during-live-capture  file: issue-20261107-200628-759-b5-d1-retain-counted-owner-during-live-capture.md -->

**Controller pre-publication test-seam ruling:** The binding
`b5-d1-ownership-preparation061/controller-prepublication-test-seam.md` adds a
private `beforePublish: (LiveSetup.Ready<PreparedStackScoping>) -> Unit` after
wrapper/result allocation and before atomic publication, with production no-op
and test-only reflective barrier/throw. Actual-runtime tests must prove drain wins
in that exact window and preserve the original thrown failure/owner with borrowed
resources. No hook or other effect runs after publication before direct return.
The successor `b5-d1-owner-capture-implementation063/task-brief-fixed1.md` binds
this ruling; no implementation release or broader native/D2 authority follows.
