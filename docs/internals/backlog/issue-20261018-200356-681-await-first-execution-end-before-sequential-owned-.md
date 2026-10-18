---
title: "Await first execution end before sequential owned scoping submissions"
severity: "MEDIUM"
status: "open"
priority: high
dependencies: []
component: "enforcer"
target_modules: [":enforcer"]
target_files:
   - "enforcer/src/integrationTest/kotlin/io/mazewall/OwnedScopingNativeTest.kt"
   - "enforcer/src/test/kotlin/io/mazewall/execution/OwnedRuntimeTest.kt"
target_symbols:
   - "verifyCapturedScoping"
needs_kernel: true
core_lock: false
effort: "small"
autonomy: "supervised"
open_questions: false
has_side_effects: true
---

# 🟡 [Severity: MEDIUM]: Await first execution end before sequential owned scoping submissions

**Context:**
Post-merge master CI `39159510604` at `35c23c3be2c566a9d2f17e9d7ff99edb9e789089` fails `OwnedScopingNativeTest.verifyDenialAndFatal` at line 96 with `Domain admission limit reached`. The fixture uses `DomainLimits(1, 1)`, discards its first `InvocationHandle` and waits only for result delivery before submitting lookalike denial work. `OwnedInvocation` completes result before its finally releases admission and completes `executionEnd`, as documented. Runtime admission is correct; this fixture assumes result equals execution end. All 18 `MmapProtectionTest` cases pass on the same failed run, and publication is skipped.

**Needed:**
1. Add a deterministic fixture regression for the first result becoming visible while its single admission remains held; preserve the one-worker one-admission domain and do not rely on an unchanged timing retry.
2. Retain the first InvocationHandle and await its bounded executionEnd before submitting denial/fatal work; preserve captured authority, genuine network denial, cause/fatal identity, rejection-after-fatal and final drain/worker retirement assertions.
3. Run the complete native fixture, owning suite and full gate with scoped review, then require exact-head CI. Do not change runtime completion order or limits, increase capacity, add sleeps/retries, weaken assertions or alter policies.

## Evidence and mechanism

- [Default-branch run 39159510604](https://github.com/Pilleo/mazewall/actions/runs/39159510604) completes FAILURE. Optional integration acceptance succeeds; the main job fails `Run Privileged Kernel Gate and Assemble` in `:enforcer:integrationTest`. PIT/security/package publication are not established by this failed run.
- Retained main-job log `.superpowers/sdd/2026-10-18-mmap-protection-setup/master-main-job.log:3760-3778` names `RejectedExecutionException`, `OwnedDomain.submit:143`, `ManagedRuntime.submit:285`, `verifyDenialAndFatal:96`, and `verifyCapturedScoping:82`. The isolated child exits 2; its parent test records one failure, not a skipped native prerequisite.
- Artifact `master-diagnostics/enforcer/build/test-results/integrationTest/TEST-io.mazewall.enforcer.api.execution.OwnedScopingNativeTest.xml` in that workspace records one failed case. The same artifact's `MmapProtectionTest` XML records 18 passes, zero errors/failures/skips; this is not recurrence of the repaired executable-mapping setup crash.
- At failing base `35c23c3b`, `OwnedScopingNativeTest.kt:64,69-82` configures one worker/one admission, chains away the first handle into `.result().get(...)`, then calls the second submission. Its later denial invocation already waits for its own execution end before fatal work (`:107-112` at that revision); the first success phase lacks that synchronization.
- Read-only runtime chain: `OwnedInvocation.kt:148,156-163` delivers result before its finally calls `owner.finished(this)` and completes execution end. `OwnedDomain.kt:141-143,313-315` rejects exhausted admission and releases it only in `finished`. The documented separation is [containment design](../designs/enforcer/containment-design.md) at lines 521-527. A bounded first-handle execution-end wait follows the actual release ordering; result delivery alone does not.
- Related [Java synchronous metadata fixture issue010021-700](resolved/issue-20261016-010021-700-allow-bounded-result-and-execution-end-overlap-in-.md) handled the same documented distinction with two bounded admissions because that synchronous entry did not retain a handle. This native fixture already uses the handle-returning API, so preserve its single admission and await physical execution end instead of increasing capacity.
- Merge `35c23c3b` changes only the mmap fixture and its issue/index records. `OwnedScopingNativeTest` and the runtime implementation are unchanged; changed scheduling may expose the assumption, but no runtime-admission defect is established by this evidence.

## Scope and limits

This item is a separately scoped fixture correction, not authorization to edit production `ManagedRuntime`, `OwnedDomain`, `OwnedInvocation`, limits or completion semantics. Do not drain/reprepare between success and denial, replace the original domain, permit caller execution, manufacture kernel receipts, or weaken denial/fatal/retirement checks. Preserve all pending operator-owned ADK/cache edits. Local verification below qualifies the scoped candidate only; it does not reinterpret the failed master run, establish general data-flow/kernel/hardware guarantees or replace exact-head remote acceptance.

## Approved plan and inline execution

The local plan is `docs/superpowers/plans/2026-10-18-owned-scoping-execution-end.md`, based on the observed merge `35c23c3b`. On 2026-10-19 the operator approved inline implementation with TDD and one fresh final review. Source scope is the two existing test files listed above: characterize successful result/end overlap in the current mocked-kernel host harness, guard the actual native method's wait-before-resubmit ordering, and add the original-handle bounded end wait. The characterization passes with the correct unchanged runtime; the fixture guard supplies the actual RED. No production hook, new generic helper or quota change is added.

A bounded read-only sibling check found existing end waits, single submissions or deliberate two-admission headroom in the inspected neighboring fixtures; it is not a whole-repository audit. ADK blast-radius evidence remains PARTIAL/INDEX_MISSING, with exact local wrapper/declaration/phase call-site inspection used rather than a complete caller-graph claim.

## Local implementation and verification

Source commit `28df856c2d4fe61f594761243da8765e3128ddeb` on `fix/owned-scoping-execution-end` retains the original first handle, keeps its result-derived worker and awaits that handle's successful `ExecutionEnd.Finished` with five-second timed get immediately before denial/fatal work. One worker/one admission, profiles, PTRACE baseline, captured handler/authority, socket byte42, cause/fatal identity, rejection-after-fatal and final drain/dead-worker checks remain unchanged. No production/runtime code or completion order changes.

- **Actual fixture RED:** focused compiled-method regression exits1 on the old flow: expected one execution-end call, found0. It found the actual method; no compiler/setup failure is counted as RED. The corrected guard passes and checks result → end → timed get → denial entry. It is a direct compiled-method check, not arbitrary control/data-flow proof.
- **Controlled host characterization:** a body gate permits attaching a non-async callback before result delivery. Holding that callback on the actual worker proves result42 visible while end is pending and a second admission rejects without body entry. Release/end then admits result43 and complete retirement. It passes before the native correction because runtime behavior was already correct; it is not the feature RED or a kernel receipt. Both gates release in finally, the callback stage is observed and the existing mocked-kernel reset boundary is retained.
- **Focused/host checks:** exact guard command passes after the fix; the post-fix focused host command is up-to-date, not falsely counted as fresh execution. A separate whole `OwnedRuntimeTest` run actually executes and passes37 cases, zero skips.
- **Lint:** initial focused lint finds four introduced guard wrapping violations. Only that whitespace is corrected; both source-set checks then pass, with no formatter sweep or rule suppression. Failed output is retained.
- **Actual native class:** configured container task executes both `OwnedScopingNativeTest` cases;2 passes, zero failures/errors/skips, including real captured-authority/denial/fatal behavior.
- **Owning native suite:**197 cases,195 passes,zero failures/errors,two CET-related skips. Scoping2/0skip and the previously repaired mmap18/0skip both pass. No new skip logic; one CET XML lacks reason text and the other names the SHSTK runtime/loader prerequisite. No universal hardware qualification or fresh unchanged-suite skip baseline is claimed.
- **Commit completion:** task-done actually reruns the owning native suite after the source-only commit and exits0. The repeated command is an explicit execution-contract gate, not an unchanged retry to hide a failure.
- **Full gate:** `LC_ALL=C ./gradlew build --console=plain --max-workers=1` exits0 in7m45s:242 tasks,36 executed/206 up-to-date. Enforcer integration/fresh-JVM and root kernelCheck execute. Copied full-gate XML records scoping2, mmap18 and host37 passes, no skips in those classes. Local green is not remote/publication acceptance.

Exact argv/log/exit/XML and source-bound receipts are retained under `.superpowers/sdd/2026-10-18-owned-scoping-execution-end/`, including `implementation-report.md`, phase records, `task-1-tests.log`, `full-build.*` and `full-gate-receipt.json`. Existing Kotlin/Unsafe/Gradle/native diagnostics remain unsuppressed; the new characterization produces an occurrence of an unchanged BPF diagnostic site. No protection claim follows from its text.

**Integration gates:** fresh whole-branch review, operator-controlled PR/exact-head CI, approved merge and separate master/publication observation. No new remote run, publication, merge or automatic archive has been performed for this correction. Pending ADK/cache work remains excluded; keep this issue open until its acceptance requirements are met.

---
<!-- id: issue-20261018-200356-681-await-first-execution-end-before-sequential-owned-  file: issue-20261018-200356-681-await-first-execution-end-before-sequential-owned-.md -->
