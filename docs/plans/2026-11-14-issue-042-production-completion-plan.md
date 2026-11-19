# Issue 042: Production Completion Plan After the Linux 6.12 Experiment

> **Plan only.** Execute one concrete job at a time through native Antigravity. Hydrogen implements; Argon handles a specific difficult proof question or one connected-change review. Keep coordination and acceptance with the parent. No kernel patches, worktrees, commits, publishing or unrelated cleanup.

**Goal:** Turn the accumulated WIP into one buildable production candidate, implement the stock-kernel counter/readout method, finish its real lifecycle, then qualify the original all-source result.

**Architecture:** Reuse the existing native object owner, counter map, global-barrier helper, scope finalizers, report reader and receipt transport. Add no new accounting framework or public API. A successful counter-bound check is not an epoch or completeness certificate.

**Tech stack:** Existing Go 1.26.10 collector/pinned SDKs, C/eBPF, Cilium and Kotlin reporting. Existing dependencies only.

**Spec:** Original acceptance remains `2026-11-08-hotspot-production-accounting-proof-plan.md`. Stock-kernel design and evidence are in `2026-11-14-issue-042-vanilla-kernel-plan.md` and `.superpowers/sdd/2026-11-14-issue-042-recovery-plan/native-rcu-experiment-result.md`. This is the current execution sequence, not a new acceptance definition.

## Fixed scope

- HotSpot agentless target: **unmodified Linux 6.12+**, initially the existing Linux-amd64 packaging. No older-kernel fallback or custom kernel/module work. This does not change the separate seccomp profiler's existing Linux 5.0 requirement.
- Preserve every original selected, per-attempt independent reconciliation, definition/auxiliary/report/PID/store, enrichment, wire/EOF and client-retention obligation. Global completeness remains their original conjunction.
- Preserve original identities, absolute budgets, first causes, immutable first receipts, nonrenewable scopes and actual-return ownership. Never translate EPERM/EACCES into unsupported/success.
- Preserve four classic pairs, three fentry/fexit pairs, fourteen slots, independent socket Q, actual multicast Cut, CAS64/OR64, ordinary global membarrier, active-nohz_full rejection and default-invalid 80-byte `kw_layout`.
- The proposed exception to blanket classic-return `missWidth == 64` is **only** the qualified entry-bounded nonwrap method. Zero metadata, a width cast, a small elapsed time or the six successful prototype trials cannot qualify it alone.
- Application changes and exact registered mirrors move together. Preserve existing WIP, partial generated trees and historical selected qualification. No blanket copying of the eight missing broader mirror files.
- Builds and read-only source downloads are normal work, not invented operator approval gates. Runtime probes for this feature run in the owned stock-kernel VM, not the working XanMod host.

## What is established—and what is not

**Established:** both standard patch chains compose; the corrected bounded model rejects the relevant bad protocols; the stock Debian 6.12.117 experiment observed ordinary GLOBAL waiting for a held PMU BPF callback, with QUERY returning while it was held and original IDs retained.

**Not established:** the accumulated production candidate compiles; its new global all-entry counter exists; current production readout uses the tested order; a genuine qualified Open/Fence/hot-retirement path exists; or the original all-source result holds.

Source-grounded blockers:

- `scripts/rebuild-profiler-ebpf.sh` currently always exits `INCOMPLETE`.
- The existing Go builder's `main` leaves its image ID empty and endpoint/watchdog qualifications false.
- `kw_counts` is still 224 bytes and has no unconditional entry counts.
- `readoutWithCalls` reads counts before guards and has no between-samples barrier.
- Native `Open` and `Fence` explicitly remain unsupported; retirement only handles never-opened graphs.
- The report reader has accounting methods but no connected production Flush/drain request.

## Milestone 1 — Build the real candidate before adding behavior

### Job 1: Make the existing builder genuinely runnable

**Files:** `profiler/hotspot-agentless/scripts/rebuild-profiler-ebpf.sh`, `scripts/rebuild-profiler-ebpf/{main.go,main_test.go}`, `internal/tooling/{prepare.go,prepare_test.go}` and their existing mirrors.

- [ ] Replace the unconditional shell stub with strict forwarding of the existing `(provider_dir, absolute_deadline)` contract into the existing Go builder. Reject invalid/missing arguments before creating a container. Remove `main`'s missing/non-positive-deadline `now + 540s` fallback; the launcher may not renew a caller's budget.
- [ ] Derive the concrete runtime configuration from the configured local Docker endpoint and exact pinned builder digest/image ID. Keep the existing owned-CID/nonce/spec verification and uncertain-cleanup behavior. Do not simply set `approved`/`qualified` constants true.
- [ ] Establish the actual PID-1 timeout behavior in that immutable image with a deliberately overlong payload and a detached/killed client control. Verify the container exits without relying on the client staying alive. Checking only that a string equals `/usr/bin/timeout` is insufficient. Use a dedicated test-only payload seam against the same digest/image ID; keep production `nativeBuildPayload` locked.
- [ ] In `prepare`, capture one `overallEnd := time.Now().Add(540 * time.Second)`. Derive the builder's end, retirement reserve and bounded client-return allowance from it; the outer context uses `overallEnd`, not another `now + budget`. Account conservatively for Unix-second rounding and the existing `WaitDelay`.
- [ ] Fix the existing double subtraction of the retirement reserve (`executeHardenedBuild` and `constructContainerSpec`). Deduct that reserve once and explicitly budget preflight, payload, timeout kill-after, terminal inspect, retirement and client return **inside** the original 540 seconds. Do not change 540 seconds to 545 or renew the cleanup budget. Tests must verify the actual start/inspect/cleanup deadlines, not merely that a context exists.
- [ ] Test missing deadline, wrong image/digest, unavailable endpoint, timeout, uncertain create/start and failure to confirm retirement. None may publish a success marker or remove an unverified container.

Use the module-declared Go 1.26.10 toolchain from this first job; normal acquisition is allowed. Focused command from the collector directory:

```bash
go test ./scripts/rebuild-profiler-ebpf ./internal/tooling
```

**Done:** builder tests pass; one actual pinned build retires its exact owned container, and the overlong-payload control fails honestly within its original budget. No new container-management framework.

### Job 2: Assemble, compile and test the current WIP

**Files:** existing tooling/build wiring and only concrete compilation defects in the actual registered patches/additions. No counter or lifecycle feature work in this job.

- [ ] Use Go 1.26.10 required by the collector; the experiment's host Go 1.26.8 is not the production toolchain. Acquire the required toolchain normally if absent.
- [ ] Run actual standard preparation through the repaired entrypoint. Use the explicit local Docker endpoint without changing the user's default context. Retain fresh source/native hashes and manifests.
- [ ] Preserve failed/incomplete preparation artifacts. Do not fabricate markers, overwrite an unresolved writer's tree or reuse stale assets as fresh input.
- [ ] Compile the collector and the actual affected SDK test packages, then run focused pure controls. Audit selected tests for native effects; anything kernel-executing belongs in the VM.

From `profiler/hotspot-agentless`, use the generated standard modfile:

```bash
go run ./cmd/mw-collector-tools prepare
go build -modfile=build/experiment.go.mod -o build/issue042-collector-baseline ./cmd/mw-hotspot-collector
go test -modfile=build/experiment.go.mod -c -o build/issue042-tracer.test go.opentelemetry.io/ebpf-profiler/tracer
go test -modfile=build/experiment.go.mod -c -o build/issue042-scope.test go.opentelemetry.io/ebpf-profiler/periodiccaller
go test -modfile=build/experiment.go.mod -c -o build/issue042-capture.test ./internal/capture
```

Existing focused controls include `TestKobjectCounterWireAndCopy`, `TestKobjectCounterRejections`, `TestKobjectGlobalBarrierFakeEffects`, `TestKobjectGlobalBarrierWaitsForNativeReturn`, `TestOwnedScopeExpiredFirstStopCannotRenew` and `TestOwnedScopeFinalizersBeforeLifetimeRelease`. `go test -run '^$'` is not compile-only; use `-c` for that purpose.

**Done:** the actual assembled production candidate compiles and the selected controls pass. If it does not, fix the concrete baseline defect before adding the next feature. Source-review approvals do not substitute for this milestone.

## Milestone 2 — A connected production counter/readout slice

### Job 3: Add all-entry counters and update the private wire format

**Files:** `patches/profiler-kobject-witness.patch`, `patches/profiler-additions/tracer/{kobject_witness.go.txt,kobject_witness_native.go.txt,kobject_witness_native_test.go.txt}`, affected existing tests and exact mirrors.

- [ ] Append `u64 classic_entries[KW_STAGE_COUNT]` to `kw_counts_value`. The count map becomes **256 bytes**; existing offsets 0–223 remain unchanged. Go readout gains `classicEntries [kwStageCount]uint64`, separate from admitted stage facts, decoded at offsets 224, 232, 240 and 248.
- [ ] Count each valid classic entry before layout, correlation and admission filtering, including calls after the epoch gate closes. Reuse existing `kw_increment` and its persistent arithmetic fault; do not add another atomic-counter helper or return early and drop an already admitted frame when the diagnostic increment faults.

The intended insertion in `kw_stage_enter` is structurally small:

```c
struct kw_counts_value *counts = kw_get_counts();
if (counts && (u32)stage < KW_STAGE_COUNT)
  kw_increment(counts, &counts->classic_entries[stage]);
/* Existing binding/layout/admission/frame handling follows unchanged. */
```

- [ ] Update map-spec validation, initializers, decoder buffers, C static asserts and all 224-byte fixtures together. Reject old/truncated/wrong-map data. Set the private kobject binding ABI to **2** (`KW_BINDING_ABI_VERSION` / `kwBindingABIVersion`) and update all readers/writers. Keep public capture ABI unchanged and preserve the separate 80-byte kernel-layout encoding/version; these are different domains.
- [ ] Tests must distinguish all entries from admitted `attempted`: closed gate and unrelated/unregistered calls still increment only the new count. Test overflow/CAS exhaustion fault behavior, original field offsets and rejection of old size/version.

**Done:** actual generated BPF and Go decoding agree; existing conservation counters retain their meaning. This job does not qualify any native guard or global completeness.

### Job 4: Put the proven read order into the real native owner

**Files:** `kobject_witness_native.go.txt`: `kwEpochReadoutCalls`, `readoutWithCalls`, guard readers and the existing global-barrier helper; `kobject_witness.go.txt`: readout fields/validation; existing native/witness tests and mirrors.

- [ ] Add the needed private barrier callback to the existing effect seam, using the original stored first-stop context. Keep public signatures unchanged and retain the existing operation borrow until every actual call returns.
- [ ] Replace count-first sampling with:

```text
validate original owner and eligibility
read original classic return-link miss snapshots
complete ordinary global membarrier / RCU grace period
read later all-entry counts and entry miss/recursion guards
revalidate original identities and eligibility
publish immutable sampled facts, or preserve the first failure
```

- [ ] Split the current single-pass guard reader. Before the barrier, retain all four original classic exit `kwLinkInfo` snapshots. After it, read counts, all fourteen program records, four entry-link records and six tracing records. Re-query the four exit links to verify unchanged link/program/target identities, but retain their earlier `missed` values for the bound. A post-barrier full guard pass must not overwrite the earlier samples.
- [ ] Preserve EPERM/EACCES, short info, wrong IDs and failed/expired barriers. An expired context whose `Done` channel remains open starts no next effect. Cancellation while a syscall is held does not release the graph before actual return.
- [ ] Fix the barrier adapter's current `ctx.Err()`-only eligibility checks: use the captured absolute deadline before/after the nohz read, QUERY and GLOBAL steps. A held QUERY returning after expiry with `Done` still open must not start GLOBAL. Do not add a replacement context or timer to obtain this check.
- [ ] Exercise the existing production `performKobjectGlobalBarrier` adapter, including its nohz_full file parsing, in the stock VM. The prototype tested the raw syscall, not that adapter. Verify actual inactive-file representations against kernel semantics; preserve active-nohz_full and malformed/error rejection rather than guessing from the prototype's success.
- [ ] Add the private pure bound decision with the model's cases: entry guard reliable/zero, original continuous lifetime, return representation justified, `N < 2^31`, no faults. Test `2^31-1`, `2^31`, signed cancellation, wrapped zero, missing entry guard, wrong generation and reordered/failed fence.
- [ ] Exclude `classicEntries` from `runFinishSequence`'s intermediate/terminal equality comparison; unrelated calls may increase it. Keep existing equality checks for the separate admitted stage/recipient/root facts, and independently validate each readout's earlier-miss/barrier/later-entry tuple.
- [ ] Until Job 8 establishes the whole original observer lifetime, epoch Fence and safe hot retirement, keep native guard `lifetimeBound=false` and an explicit `errors.ErrUnsupported` in every guard's error. Do not clear these errors or relabel a 32-bit component as 64-bit merely because the bound or barrier succeeds. Pure arithmetic/policy fixtures stay MODEL-only.

**Done:** the existing `kwNativeEpoch.Readout` path executes the correct production sequence, with real callers and rejecting controls. Not an unused alternate adapter or an accepting certificate.

### Job 5: Exercise that production slice in the stock VM

**Files/artifacts:** existing native-composition tests and production generated SDK/assets; reuse the owned stock-kernel VM fixture from the result report.

- [ ] Reprepare changed sources and run the production owner/map/decoder/readout code—not a copied prototype algorithm. A cold diagnostic run may return factual data plus explicit qualification errors; do not fake `Open` success to exercise it.
- [ ] Do not relax epoch-state checks just to reach the test. If the complete `Readout` entrypoint cannot run before Job 8, exercise only the shared lower-level production sampler as factual component testing and defer the full entrypoint case to Job 8. Mark that distinction explicitly; an unexecuted path cannot pass this milestone as a complete epoch.
- [ ] Generate a known guest uevent stimulus while admission is closed; require the relevant all-entry count to advance while admitted stage counts do not. An empty all-zero snapshot is not this test. Verify original map/prog/link identities and source/native hashes against the built candidate.
- [ ] Verify the held-call, missing-guard, failed-fence and original-deadline controls through actual production seams. Keep MODEL-only controls separate from genuine guest observations.
- [ ] Reuse the successful raw membarrier experiment rather than rerunning it to create another label. If extending its temporary helper, first fix its documented hold-argument/error-path defects; do not import that diagnostic helper into production.

**Done:** one compiled production counter/readout slice is exercised on stock 6.12 with precise remaining unsupported reasons. Review this connected slice once. Do not call 042 complete.

## Milestone 3 — Complete the real stop and epoch lifecycle

These are separate assignments, not one “finish lifecycle” prompt.

### Job 6: Connect one-shot drain to the actual report reader

**Files:** `reader_health.go.txt`, `reader_health_test.go.txt`, `profiler-auxiliary-ownership.patch` and mirrors.

- [ ] Complete the existing `reportReaderControl` drain request using one Flush on its already-bound reader.
- [ ] The actual read loop must distinguish requested same-loop `ErrFlushed` from unsolicited/error/canceled return and call the existing accounting transitions.
- [ ] Keep original absolute stop budget, first terminal result and actual Flush/read/callback return ownership. No second reader, worker, timer or renewed context.

**Done:** production calls—not declarations/tests alone—show one Flush, same-loop completion and rejecting held-call/expired-context controls.

### Job 7: Gate disposal and connect the covered final readout

**Files:** existing report closer/ordinary producer hooks in `profiler-auxiliary-ownership.patch` and `profiler-kobject-witness.patch`; existing `definition_owner`, `auxiliary_stop`, `pid_mailbox` additions; `periodiccaller/owned_scope.go.txt` and corresponding tests/mirrors.

- [ ] Gate the existing closer on actual Flush/read/callback returns and normal-job joins. Unknown borrowing retains the original graph; it does not permit disposal or a literal `retired=true`.
- [ ] Retire the real ordinary report producers before requesting reader drain; keep those resources distinct from the fourteen witness slots.
- [ ] Sample actual producer and PID residual facts in existing covered finalizers: **after normal-job join inside `Scope.Stop`, before lifetime release**. Wire the real `readPIDResidual` caller.
- [ ] Finish remaining acquisition-failure cleanup through existing owners after actual borrower return. Preserve the narrow failed-startup quarantine exception; no new global registry or retrying FD closes.

**Done:** a controlled trace shows retirement → reader drain → job join → original-resource final sampling → disposal. Held detach/Flush/read/callback tests prevent early close. The finalizer path is connected to actual receipt forwarding.

### Job 8: Replace unsupported epoch transitions only with genuine qualification

**Files:** `kwNativeEpoch.Open/openWithCalls`, `Fence`, `Retire`, `readKobjectNativeGuardsWithCalls`, `runFinishSequence`, current factory/owner tests and mirrors.

Dispatch independently in this order: **8a hot retirement**, **8b epoch Fence**, **8c qualification and Open activation**. Open must remain rejecting until safe hot retirement and the epoch boundary exist. Do not create an opened graph that still has only cold cleanup.

- [ ] Establish supported kernel/layout/hook identities, original observer lifetime and independent 64-bit nonwrap premises. The new small observed count cannot prove its own observation was complete. Missing evidence remains UNKNOWN.
- [ ] Open performs its actual one-shot activation on the original graph only after its prerequisites hold. Never convert the existing unconditional unsupported return into a successful default.
- [ ] Epoch Fence proves the covered epoch boundary—actual socket Cut, closed admission, roots/deferred covered work and actual callback returns—before the miss sample being certified. The numeric between-samples RCU barrier is not a substitute for this boundary.
- [ ] Hot retirement uses actual child-first detach/return facts and existing scope ownership before releasing programs/maps. `PERF_EVENT_IOC_DISABLE` is not callback quiescence. A blocked native operation retains ownership and the first failure under the original budget.
- [ ] Tests must include late covered work after the first miss sample: it must prevent completeness. Also test ID replacement, failed/held detach, active-nohz_full, unsupported metadata and permission errors.

**Done:** the ordinary production constructor can complete one genuine Open → covered epoch → Fence/readout → hot-retirement trace on stock 6.12, with failing controls. If a named lifetime/boundary premise cannot be established, return that concrete blocker; do not replace it with another metadata helper or kernel-patching proposal.

## Milestone 4 — Finish and qualify the original outcome

### Job 9: Reconcile the current candidate across all original lanes

**Files:** existing Gadget finalization/cached envelopes; `internal/capture/{selected_accounting,definition_accounting,definition_local,inventory}.go` and tests; actual Kotlin accounting/retention/document callers and their existing tests.

- [ ] Forward the final actual original-owner snapshots through existing bounded caches without delaying native outcome publication or resampling dead resources.
- [ ] Reconcile independent selected attempts, every enabled auxiliary/report/PID/store source, actual enrichment delivery, exact wire prefix/hash/EOF and client admitted/kept/discarded receipts.
- [ ] Missing/extra source, sink failure, truncation, overflow, discard or unavailable native authority must defeat the appropriate complete claim.
- [ ] Resolve broader mirror omissions from actual callers and layout rules, not blanket copying. Preserve historical 17/17 selected-only evidence unchanged.

**Done:** one current end-to-end candidate passes the original positive and rejecting oracles. Static source inventory and SOURCE_ONLY approvals are not runtime evidence.

### Job 10: Run the final checks and record one acceptance decision

- [ ] Focused tests first, then one complete `./gradlew build` in the configured test environment. Keep general repository checks separate from stock-kernel HotSpot qualification.
- [ ] Run the qualified production case on stock 6.12 and at least one newer stock kernel; containers sharing the working host kernel do not count as different kernels. Record actual source/native/kernel/config identities and which tests actually ran.
- [ ] Refresh final generated assets/manifests from this exact candidate. Record original criterion → actual evidence → rejecting control → PASS/UNKNOWN/FAIL in the existing ledger.
- [ ] Close 042 only when every original acceptance row passes. Otherwise leave it open with its precise remaining criterion. No reduced closure for “collector runs,” a successful model or this single native premise experiment.

## Execution discipline and first assignment

One application writer, sequential dependencies, no replacement-agent queue. Each prompt contains one job's files, concrete change, required tests and done condition; not this entire history. Argon reviews a complete connected change or a specific difficult question. Let a running assignment finish. Reuse checks for unchanged relevant inputs.

**First assignment:** Job 1 only—the real builder launcher/runtime qualification and its timeout/ownership controls. Then Job 2 establishes a green baseline. Do not start Job 3 while the production preparation path is still a stub or the candidate is uncompiled.

No new permission ceremony is required for normal preparation, source downloads or scoped tests. Stop for a material scope/risk change, a genuine unsupported proof premise, or an operation such as installing a kernel or publishing—not for bookkeeping approval.
