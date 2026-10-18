---
document_type: execution_plan
base_revision: d5874513f5c2ee2b5a3c2351061daf87a09afd96
status: task2_assets_public_native_qualified_commit_checkpoint_in_owning_report
---

# Native Correlation Allocator Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development. Execute the two scoped tasks continuously; do not redispatch earlier completed loss-binding tasks or create another worktree.

**Goal:** Remove clock-only correlation aliasing while preserving Kubescape-facing identity, explicit missing stacks and native qualification boundaries.

**Architecture:** Replace miss-time clock allocation with one shared eight-byte,
bounded saturating atomic sequence. Keep cache keys, hit reuse, uint64 layouts,
BOOTTIME, membership pruning and fatal guards. Qualify the actual C/kernel code
and rebuild the complete pinned graph before any runtime promotion.

**Tech Stack:** Existing BPF C/Clang, Go/Cilium, pinned builder and Go images,
Kotlin/JUnit; no new dependencies.

**Spec:** docs/plans/2026-10-18-hotspot-correlation-allocator-contract.md and
docs/plans/2026-10-18-hotspot-correlation-identity-evidence.md; issue20261018-035018-153.

## Global Constraints

- JDK25 idioms; library compatibility floor JDK22. Supported host Linux-amd64.
- Production remains pinned Temurin25.0.4.1+1-LTS, `-Xint -XX:+UseSerialGC`, synchronous `openat_handler_entry` until a separately approved capability gate passes.
- Readiness <=10seconds, session <=60seconds, stop <=2 remaining seconds, event enrichment deadline1second; no larger worker/pending/native buffer budgets.
- Kotlin retention4096 occurrences/33,554,432 wire-payload bytes, max line1,048,576bytes, max wire rows65,536, max frames4096, max tracked occurrences32,768.
- Rehash every asset byte and validate the complete local OCI graph; no size/mtime/inode digest cache or trusted-helper self-verification shortcut.
- No dependencies or breaking public APIs; additive native/public interfaces require explicit approval and legacy ABI verification before promotion.
- No swallowed/downgraded EPERM/EACCES. Fallback is explicit operator choice only.
- Raw FFM remains behind NativeEngine/io.mazewall.ffi; confined arenas, immediate errno capture, acquired descriptor ownership and checked close.
- Captured occurrence/session/process generation/BOOTTIME identity is immutable; never substitute a later stack or infer logical identity from host TID alone.
- Physical drain, selected-stream accounting, whole-session collection and policy eligibility remain separate. Policy coverage stays incomplete for selected openat evidence.
- Never install seccomp from a virtual thread or change JVM coordination syscall floors; do not touch GITHUB_TOKEN.
- Native builds remain optional, reproducible and operator-invoked; no startup compiler/download/sudo or registry fallback.
- Preserve user work, scope commits to the relevant backlog item and use the orchestrator-managed review path; no default-branch push.
- Native enrichment workers4, pending events1024, pending payload16MiB, producer LRU256, frame cache16384, frame data3072words and perf data524288bytes/possible CPU remain fixed.

## File responsibilities and shared interfaces

- Canonical authored `profiler/hotspot-agentless/patches/profiler-epoch-capture.patch`:
  new allocator map/helper and miss-path integration. A small shared C header may
  hold the actual helper for production and a test-only kernel wrapper. Register
  a separate focused patch in `internal/tooling/prepare.go` only if necessary;
  never edit prepared deps/module cache. Avoid unrelated patch reorganization.
- Canonical `profiler/hotspot-agentless/tests/correlation/`: retained actual-C
  regression harness and a test-only kernel wrapper compiling the same production
  helper; no copied allocator algorithm or new generic test framework. Existing
  command/tooling conventions and pinned builder are preferred.
- Canonical `internal/qualification/correlation/`: optional meaningful Go kernel
  tests if required for shared-map multi-CPU execution/ownership. Existing tracer
  addition tests may instead prove actual loaded-entry map sharing without public
  APIs. Pick the smallest existing boundary; report exact chosen files.
- Canonical `patches/gadget-additions/pkg/symbolizer/otel/*_test.go.txt`: only
  necessary immutable resolver/reordered/non-time prune/recovery regressions.
  Preserve original guards and original errors; no resolver namespace changes.
- Exactly mirrored authored runtime/qualification/tooling/patch/test inputs under
  `experiments/hotspot-agentless`, retaining distinct candidate fixtures.
- Fourteen literal pin consumers: canonical/mirror tooling assets+prepare tests(4),
  canonical/mirror JIT/GC source audit+tests(4), experimental candidate audit test(1),
  Kotlin HotSpotAssets/HotSpotProtocol(2), assets/protocol/child-fixture tests(3).
- Contract/this plan/new allocator evidence/patch README/public guide/current
  allocator issue and necessary backlog index updates retain current scope/status.
  The node-agent repo remains read-only; frame content hashes are not raw IDs.

### Task 1: Implement and characterize the bounded native allocator

**Consumes:** approved contract above, existing actual-C alias RED and old graph
270a/helperdfe4953f. **Produces:** actual native allocator map/helper/miss path,
maintained regression checks, characterized source and derived new patch identity.
The final runtime qualification is Task2, not implied by passing host tests.

- [x] **Step1:** Read root/profiler instructions, quality standards, current
  contract, shared constraints and handed-off Kubescape source report. Inspect
  every affected caller/loader/map/recovery/pruning consumer. Run blast-radius for
  the core symbol and record unavailable index as incomplete; use exact callers
  to supplement it. Preserve all baseline assets/deps/research before changing.
- [x] **Step2:** Add the smallest durable actual-C regression around production
  `unwind_stop` and real layouts, reusing the existing diagnostic technique. Force
  equal clocks for distinct TIDs/frames/epochs with independent CPU slots and
  successful helper boundaries. Record the actual invariant RED from unchanged
  source (reuse valid receipted baseline, not a missing-header compiler failure).
  Same-key hits must reuse their ID with no extra definition. Set up edge checks
  for non-time/high IDs, exhaustion and deterministic contention.
- [x] **Step3:** Implement the contract's one-cell map and bounded helper. The
  essential native operation is compare/exchange, not fetch-add plus wrap check:

  ```c
  u64 current = *(volatile u64 *)last;
  for (int attempt = 0; attempt < 32; attempt++) {
    if (current >= 0x7fffffffffffffffULL) return 0;
    u64 previous = __sync_val_compare_and_swap(last, current, current + 1);
    if (previous == current) return current + 1;
    current = previous;
  }
  return 0;
  ```

  `last` is the looked-up sole `u64` array entry; a missing entry returns zero.
  Include no lock, time fallback or reset. On miss allocation failure leave/clear
  return zero and publish no new cache entry/definition. Normal existing hits
  remain valid. All programs sharing the LRU must share this acquired map.
- [x] **Step4:** Execute actual-C GREEN and a clock-assignment mutant RED. Cover
  1,2^53+1,INT64_MAX, invalid state, reserved zero, saturation,32failedCAS and
  insertion/output failures with explicit missing-reference behavior. Exercise
  real resolver guards/reordered definitions and existing non-time membership
  pruning/recovery/waiters. Retain meaningful guard-removal negatives and original
  permission failures; distinguish modeled helper faults from real kernel errors.
- [x] **Step5:** Compile the actual allocator into a test-only runnable eBPF
  wrapper with the existing locked builder and execute real shared-map multi-CPU
  uniqueness/boundary tests. Native tests must check actual map ID/layout and
  checked retirement. Check actual tracer entry variants reference that same map;
  don't present a separately reimplemented ASM counter as producer proof. Record
  supported synchronous kprobe context versus unexercised perf/NMI nesting.
- [x] **Step6:** Derive actual new ordered-input patch hash after all authored
  runtime additions/tests are settled. Prepare only through normal tooling;
  preserve incomplete owned destinations before retry. Regenerate both embedded
  architectures/Go layouts. Record source/compiler/image inputs BEFORE build and
  output hashes BEFORE execution, with `-buildvcs=false` and actual modfile.
  Re-run actual prepared compile-ID controls and both named guard mutants, inspect
  the changed source, then advance all14consumer keys. No guessed/blanket key update.
- [x] **Step7:** Copy exact relevant mirrors, update contract/issue as implemented
  but full qualification pending, and retain a durable source/kernel/RED/GREEN
  evidence document with explicit limits and Kubescape purpose. Stage the new
  contract and this execution plan deliberately. Run focused Go race/Kotlin
  compilation/ABI/copy/asset refusal/style/backlog/whitespace checks. Commit exact
  scoped files through the unchanged mandatory full hook and retain its raw log
  immediately. No duplicate full build; Task1 reviewer is controller-dispatched.

Task1 source/native/focused evidence is linked from
`docs/plans/2026-10-18-hotspot-correlation-allocator-evidence.md`. Step7's commit-hook
outcome is recorded after the actual attempt in the owning workspace's
`task-1-report.md`; its raw gate log is retained there. Task1 and its R1 fix passed
independent review at82bea3aa. Task2's actual qualification status follows.

### Task 2: Qualify full assets and public collection for the actual correction

**Consumes:** reviewed Task1 source/helper/tests/new patch and actual source audit.
**Produces:** reproducible current helper/OCI receipts, explicit public-native
qualified observations and accurate durable integration status. No new node-agent
adapter or schema translation is part of this task.

- [x] **Step1:** Read Task1 handoff and only its concrete remaining gates. Verify
  source/mirrors/14pins, supported profiles and old artifacts without redoing valid
  source/kernel checks. Finalize any already-identified source defect before builds;
  renewed substantive source changes require scoped regression review.
- [x] **Step2:** Build helper and complete OCI bundle twice from the same locked
  source/Go/compiler/image graph into fresh owned destinations. Capture actual
  selected source/toolchain inputs before each build and output/bundle hashes
  before execution. No metadata digest cache, startup build or registry fallback;
  normal build permissions/errors stay fatal. Verify full reachable OCI graph,
  every asset byte, old-pin refusal and actual equality. Preserve baseline bundles.
- [x] **Step3:** Run explicit public HotSpot native acceptance with both rootful
  endpoint variables and current asset path, unchanged interpreter/Serial bounds.
  Preserve every actual result, original failure and cleanup receipt. Check
  selected counters, terminal/drain/retirement, missing lanes, zero-retention
  conservation and global/policy refusal. Check observed raw IDs are positive
  non-time references and exact task identities never alias across threads.
- [x] **Step4:** Run one receipted multi-carrier experimental control using the
  existing candidate driver if needed to exercise the original cross-task shape.
  No retry-until-green, no reconstruction of missing historical clock values and
  no JIT/GC/Loom lifetime promotion. Reuse source/kernel proofs valid for this
  exact graph; default-skipped tests never replace actual native results.
- [x] **Step5:** Update allocator evidence, contract/plan dispositions, native
  patch review guide/public guide and existing issue with exact qualified scope.
  Record Kubescape's current agent-assisted completed-artifact contract and the
  separate future agentless bridge; no claimed node-agent test/deployment or
  mutation. Close/archive the allocator issue only if all its actual criteria
  have receipts, otherwise leave explicit remaining criteria open. Preserve the
  historical failed capture and source-linkage uncertainty regardless.
- [ ] **Step6:** Focused references/backlog/whitespace and actual affected checks,
  then exact staged commit through unchanged full hook. Preserve log/real skips,
  inherited endpoint-cleanup warnings and current ABI gates. Independent task
  review, one broad final review/consolidated fix/scoped re-review follow; retain
  evidence/worktree and operator data. User-directed fast-master integration uses
   the existing nondefault staging path only after these scoped gates pass.

### Task2 checkpoint (2026-10-18)

Starting HEAD82bea3aa; current89-input/14-consumer graph8212ee25 remains unchanged.
Two actual locked helper/full-OCI builds compare every byte and reachable descriptor;
helperd0cc2a1d…9f3f93c, manifestb4a0ae29…874831, gadget5c5791bb…051e9. Eight
public-native cases execute with zero skips/failures, including selected conservation,
zero-retention502=502/pending0, physical drain and native refusal. Both dense600
diagnostics reject and retain partials. One six-case experimental control includes
four carrier TIDs without observed reference alias; legacy rendered and serialized
pdata preserve1,2^53+1,INT64_MAX. No runtime source/API/profile/budget change.

The allocator issue remains open for full context/publication/eviction-interleaving
characterization; active perf/NMI and broader lifetime remain unqualified. Original
historical failure/source linkage, deferredMinorR2 and inherited USER_NOTIF cleanup
issue stay visible. Node-agent remains read-only; its completed agent-assisted Tier E
content-hash importer is not an agentless bridge. See the
[durable qualification evidence](../../plans/2026-10-18-hotspot-correlation-allocator-evidence.md#task2-full-assets-and-public-native-qualification).

Step6's actual commit-hook result is necessarily recorded after that attempt in the
owning `.superpowers/sdd/2026-10-18-hotspot-correlation-allocator/task-2-report.md`
and progress ledger. This tracked checkpoint records completed qualification;
it does not predeclare a hook result, task review, merge or final integration.
