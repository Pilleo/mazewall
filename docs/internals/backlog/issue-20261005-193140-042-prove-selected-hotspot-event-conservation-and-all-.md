---
title: "Prove selected HotSpot event conservation and all-source loss inventory"
severity: "MEDIUM"
status: "open"
priority: high
dependencies: []
component: "profiler"
target_modules: [profiler]
target_files:
  - "profiler/hotspot-agentless/internal/accounting"
  - "profiler/hotspot-agentless/internal/identity"
  - "profiler/hotspot-agentless/internal/capture"
  - "profiler/hotspot-agentless/internal/tooling"
  - "profiler/hotspot-agentless/internal/qualification/jitgc"
  - "profiler/hotspot-agentless/patches"
  - "profiler/hotspot-agentless/README.md"
  - "experiments/hotspot-agentless/internal/accounting"
  - "experiments/hotspot-agentless/internal/identity"
  - "experiments/hotspot-agentless/internal/capture"
  - "experiments/hotspot-agentless/internal/tooling"
  - "experiments/hotspot-agentless/internal/qualification/jitgc"
  - "experiments/hotspot-agentless/internal/candidate/capture_audit_test.go"
  - "experiments/hotspot-agentless/patches"
  - "experiments/hotspot-agentless/README.md"
  - "profiler/src/main/kotlin/io/mazewall/profiler/internal/hotspot/HotSpotAssets.kt"
  - "profiler/src/main/kotlin/io/mazewall/profiler/internal/hotspot/HotSpotProtocol.kt"
  - "profiler/src/test/kotlin/io/mazewall/profiler/hotspot/HotSpotAssetsTest.kt"
  - "profiler/src/test/kotlin/io/mazewall/profiler/hotspot/HotSpotChildFixture.kt"
  - "profiler/src/test/kotlin/io/mazewall/profiler/hotspot/HotSpotProtocolTest.kt"
  - "docs/plans/2026-10-05-hotspot-loss-accounting-evidence.md"
  - "docs/plans/2026-10-17-hotspot-production-loss-evidence.md"
target_symbols:
  - "Evaluate"
open_questions: false
---

# 🟡 [Severity: MEDIUM]: Prove selected HotSpot event conservation and all-source loss inventory

**Context:**
Selected event loss needs actual quiescent counters; whole collection remains unknown until all producer and delivery boundaries are proven

**Needed:**
1. Implement overflow-safe conservation and source inventory, qualify actual native counters without promoting policy coverage

## Current disposition: 2026-11-16 — OPEN, observation branch stopped

This checkpoint supersedes the November 14 authorization to continue stock-kernel probes, timing retries, and production-hook replacement. It does not close the issue, weaken the November 8 denominators, or relabel the historical 17/17 selected-only qualification.

The agentless profiler already exists as a bounded observer. The qualified ordinary-JVM fixture saw 400/400 marked operations, 380 correctly attributed routes, and 176 compiled application frames. `coverage.complete` and `policyEligibility` stay false. That fixture is not all-source proof and cannot feed containment policy. Kubescape persisted-profile acceptance remains a separate gate.

The all-source proof is **blocked under current constraints**, not impossible on every stock Linux. On authenticated Debian `6.12.117-1`, recipient eligibility, the reader-lock interval, tagged/untagged routing, and the relay broadcast body are inlined into enclosing functions. Stock function entry does not observe those branches. Instruction-offset probes and a custom kernel remain forbidden. Consumed overlap and writer-order attempts stay UNKNOWN; another timing run cannot repair them.

Authenticated debug bytes were recovered and bound to the retained kernel, including build ID `b1d7a9114053a488ef493f85f85e9fc75ba9f632`. That closed a missing-input gap. It did not prove traversal admission, Q authority, or producer/callback closure.

Process custody does not finish the proof. When either HotSpot PID is set, the collector creates and polls the witness socket; `kobject.New()` is only the zero-PID legacy path. Sealing either socket can reduce userspace aliases. It cannot see suppression, routing, per-namespace iteration, relay rejection, or whether Q was eligible on a traversal. A helper socket, copied FD, or cgroup label is a different owner story, not the same Q.

The executed forward decision is **NO-GO**. Stop this research branch. Do not implement a seal, helper, or another probe unless a permitted observation distinguishes the failed cases, or the operator explicitly changes one named constraint. No such change is requested.

Evidence: [feasibility decision](../../../.superpowers/sdd/2026-11-14-issue-042-recovery-plan/observer-feasibility/evidence-enforcement/feasibility-decision.md), [forward decision](../../../.superpowers/sdd/2026-11-14-issue-042-recovery-plan/observer-feasibility/forward-decision/decision.md), and [forward plan](../../plans/2026-11-16-issue-042-forward-decision-plan.md).

## Production implementation checkpoint: 2026-11-14 — OPEN, stock-kernel hook blocker

Implementation/preparation/scoped testing is authorized under the [production completion plan](../../plans/2026-11-14-issue-042-production-completion-plan.md); the execution restrictions in the historical November 13 checkpoint below no longer apply. Work remains uncommitted and unpublished, with kernel-specific probes confined to the isolated stock-kernel VM. No Linux patches, custom kernel builds or custom modules are used.

The real pinned builder is restored with bounded independent timeout/owned-container handling inside the original 540-second stage budget. The current source genuinely prepares and compiles. Its private kobject count map is 256 bytes with four unconditional classic-entry counters; private binding ABI is 2, while public capture ABI and the separate 80-byte layout contract are unchanged. Production readout now retains earlier return-miss samples across GLOBAL, later entry-count/guard sampling and identity-only rechecks. The bounded signed-32 return policy has rejecting arithmetic/premise controls; native lifetime/epoch qualification remains unsupported, not inferred from a small count or successful barrier.

Actual stock Debian `6.12.117+deb13-amd64` testing established the inactive `nohz_full` representation `"(null)\n"`; the corrected production barrier adapter passes. A real BPF verifier rejection exposed a packed-depth frame-index bound lost during compilation; checked frame indices fix that observed rejection without changing fault/accounting semantics. Full original witness loading nevertheless remains blocked: **`kobject_uevent_net_broadcast` and `do_one_broadcast` have no matching function in either the guest's complete BTF function inventory or its readable kallsyms table, including suffix/clone searches.** The required fourteen-slot graph cannot attach unchanged on this kernel. This is a concrete kernel-target availability limitation, not evidence that all recent stock kernels are unsupported or permission to substitute another denominator.

Latest diagnostic candidate: `0a84c7bd09649a5d671f7fbf954692feb6943c4cccf965c8ebcb5dc9e4b66e54`. Collector/test compilation and focused host controls pass; full Gradle checks, full native epoch/reader lifecycle, all-source reconciliation and newer-stock-kernel qualification remain unfinished. The read-only inventory test passing means inventory collection succeeded, **not** hook capability or completeness. Historical 17/17 selected-only receipts remain evidence for their original frozen graph, not this candidate.

The operator approved redesign investigation and only Gate 1 of the [stock-observer feasibility plan](../../plans/2026-11-14-issue-042-stock-observer-feasibility-plan.md): a throwaway VM probe for delivery-independent original-socket enrollment. Broadcast eligibility inference, Cut/terminal correlation and accepting production-hook replacement are not approved or established by that gate. The source/design review explicitly leaves traversal membership, compiled coverage, observer lifetime and all-source proof UNKNOWN.

Gate 1 subsequently passed in one owned stock Debian Linux `6.12.117+deb13-amd64` VM: original Q enrolled independently of delivery, the actual other FD was observed and correctly rejected for Q, and the MODEL-only generation mismatch rejected without changing native evidence. Both callback returns were 12 and syscall returns 0; handles stayed retained with attachment quiescence UNKNOWN until terminal VM poweroff, and the original container retired with ownership verification. Evidence: `.superpowers/sdd/2026-11-14-issue-042-recovery-plan/observer-feasibility/gate1-vm-r2/parent-interpretation.json` and its preserved native receipts. This establishes only enrollment feasibility on that tested kernel; later gates remain unapproved and production qualification is still incomplete.

The operator then approved continuing with Gate 2's broadcast-argument/actual-return diagnostic. Its scratch implementation passed pure checks and independent source review, but the single stock-VM attempt stopped before broadcast attachment/stimulus: the BPF verifier rejects direct function-pointer argument access (`FUNC_PROTO`, context offset40/size8, original errno13). Failure receipts and full verifier log are preserved under the same ledger's `observer-feasibility/gate2-vm-batch1/`; the original owned VM container retired cleanly. Enrollment success and receipt transport are not Gate 2 success. Stock-compatible all-seven-argument observation is under read-only investigation; no weakened argument contract, unchanged retry, production replacement or later-gate execution is authorized by this result.

After exact-source investigation, Hydrogen replaced only the rejected argument reads with supported `bpf_get_func_arg` helper183; pure/ELF controls and Sol re-review passed. The operator explicitly approved one changed-candidate VM run. **Gate 2 r1 passed on stock Linux 6.12.117:** all seven arguments were observed in one depth-one entry/exit pair with zero faults, actual signed broadcast return `-3` (`ESRCH`) was kept separate from actual `sendmsg` return `16`/errno0, and owning resources stayed retained until terminal VM poweroff. Original container retirement was verified; the failed Batch 1 evidence remains preserved. Receipts and parent interpretation: same ledger's `observer-feasibility/gate2-vm-r1/`. This establishes controlled generic-broadcast observation only—not Q membership/delivery, uevent-source coverage, compiled/all-CPU coverage, attachment quiescence or production qualification. Later gates still require approval.

On the operator's next continuation, **Gate 3 r1 passed** in one stock Linux 6.12.117 VM: independent raw identity checks before/pre-broadcast/after confirmed Q protocol15 versus actual sender protocol2 in the same socket netns, supporting the matched-source protocol-indexed negative destination-domain predicate. Both sockets had port152, correctly distinguished by separate cookies/kernel socket bindings. One paired broadcast retained all seven arguments and separate actual returns; error-free immutable handoff and original owned-container retirement were verified. Evidence: same ledger's `observer-feasibility/gate3-vm-r1/parent-interpretation.json` and seven preserved guest receipts. This is bracketed source-grounded negative-domain evidence only; it does not prove continuity, traversal-time membership, D/E zero, uevent-source completeness, quiescence or production qualification. Cut and terminal gates remain unapproved.

The operator then authorized Gate 4's single actual broadcast/Cut attempt. After Grok design challenge, Hydrogen implementation and Sol safety re-review, the stock 6.12.117 VM observed actual joined→dropped Q membership with correlated outer/callback returns0 and a separate paired broadcast. **The overlap control was NOT EXERCISED:** the native Cut callback ended 2416ns before broadcast entry, so the preserved result is `Interval=NotExercised`, `Overall=UNKNOWN`, not PASS. All eight guest receipts, timestamps and parent interpretation are under the same ledger's `observer-feasibility/gate4-vm-r1/`; the original owned VM container retired with verification. Native instrumentation/Drop feasibility does not substitute for triggered overlap, traversal ordering or production proof.

The operator then approved one entry-ordered replacement attempt: Cut starts only after a mmapable broadcast-entry signal, with the workers pinned to CPU 0 and CPU 1. Clang 18 rejected a C11 releasing store; the operator approved one atomic-exchange publish instead. That attempt also ran once. The signal matched broadcast entry ktime and the Drop was observed, but the callback still started 385ns after broadcast exit. The preserved result is again `Interval=NotExercised`, `Overall=UNKNOWN`, not PASS. Evidence is `observer-feasibility/gate4-vm-r2/`. That approval is consumed. No further retry, delay, or publish-site change is authorized; terminal and production-replacement work remain unapproved.

A later read-only proof reassessment rejected using call overlap as the race proof and withdrew a BPF entry stall. The exact guest BTF, recovered from the retained kernel image without a new VM, contains `netlink_update_socket_mc` and `netlink_update_subscriptions` as static functions with usable arguments. `netlink_table_grab` and `netlink_table_ungrab` exist but have no arguments, so they cannot alone distinguish allocation from mutation. Inline reader boundaries and `do_one_broadcast` remain absent. Attachment of the mutation helpers is unconfirmed and is not authorized by that finding.

The operator then approved one passive stock-VM check. It attached `__sys_setsockopt`, `netlink_setsockopt`, `netlink_update_socket_mc`, and `netlink_update_subscriptions`. One original-Q group-1 DROP produced one clean nested route: the socket-membership helper saw group 1 and `is_new=0` on Q's enrolled socket, and the subscription helper saw subscriptions 0, with independent membership readback changing from joined to clear. The result is `ObservedMutationRoute` only. It does not prove traversal order, Q eligibility during traversal, E/D, continuity, global lock ownership, the exact bit-write instant, or all-source coverage. Evidence is `observer-feasibility/gate4-passive-mutation-vm/`. The owned VM retired with verification. No retry is authorized.

On November 16, the separately approved source-assisted writer-order diagnostic ran once after implementation and independent review. The broadcast finished 48056ns before the correlated membership-mutation interval; no broadcast endpoint fell strictly inside that interval. The predeclared result is `Unknown`, not an ordering-witness PASS. Actual sendmsg16/broadcast0/DROP0 and joined→clear membership evidence are retained under `observer-feasibility/gate4-writer-order-vm/`; original owned-container retirement was verified. This approval is consumed, with no retry or timing adjustment. Original overlap controls and all production conservation/coverage obligations remain unfulfilled.

The [single execution ledger](../../../.superpowers/sdd/2026-11-14-issue-042-recovery-plan/progress.md) records exact source/binary/kernel identities, failed and successful component observations, original ownership receipts, an earlier worker cleanup/provenance incident and the operator-authorized fresh baseline. The later unstarted watchdog orphan and failed preparation artifacts remain preserved. No full-candidate conservation or all-source acceptance is claimed.

## Historical source-remediation update: 2026-11-13 — all-source proof remains OPEN

The original [production-accounting plan](../../plans/2026-11-08-hotspot-production-accounting-proof-plan.md) remains binding; no lane has been removed. The operator restored its remaining capture, owner-source/Gadget and Kotlin file scopes after the narrower kobject checkpoint.

Two original-unit repairs have source-only review approval: enrichment preserves the earlier fatal lookup/recovery cause when later terminal delivery fails, and client retention independently counts admitted/discarded payload bytes while preserving the retained-ledger check. See the [enrichment review](../../../.superpowers/sdd/2026-11-08-hotspot-production-accounting-proof-plan/reviews/task-3b-enrichment-first-fatal-preservation-review.md) and [retention-byte review](../../../.superpowers/sdd/2026-11-08-hotspot-production-accounting-proof-plan/reviews/task-3d-retention-byte-conservation-review.md). Their regression sources were written but not executed; payload-byte conservation is not wire-prefix or native-source authority.

Retained-owner lifetime, original source projection/producer coverage, real admission context and receipt-backed document continuity remained work in progress at this checkpoint. Genuine native qualification, independent enabled-source authority, the then-unchanged strict64/classic-int32 constraint, ordinary-RCU/counter-lifetime guarantees and the original production campaign were unresolved. Compilation, tests, preparation and native execution were prohibited at that time; the operator subsequently authorized the bounded work described in the November 14 checkpoint above. This historical update is not aggregate conservation, all-source acceptance, or permission to close this issue.

## Latest checkpoint: 2026-11-08 — 17/17 current selected cases qualified

The selected-case qualification ledger is now **17/17 CURRENT**, replacing the
previous mixed-provenance checkpoint: **one capless embedded ABI selection and
16 non-ABI selections**. All used four freshly compiled binaries from the same
frozen source/module graph, with canonical fingerprint
`7e09e472bd37c2c7b03aeb14dad509a0b56485bcfef2ddcb56e0891a2c08c7f9`
and the separately declared native fixture layer. No historical binary or
nonqualifying diagnostic was promoted into this result.

| Selection | Current qualification |
| --- | --- |
| `abi` | ABI PASS |
| `n1` | NATIVE PASS |
| `n2` | NATIVE PASS |
| `n3` | NATIVE PASS |
| `n4` | NATIVE PASS |
| `n5-reader-tail` | NATIVE PASS |
| `n6-map` | NATIVE PASS |
| `n5-original-root` | NATIVE PASS |
| `n6-lifetime` | NATIVE PASS |
| `n5-joined` | NATIVE PASS |
| `n5-direct-drain` | NATIVE PASS |
| `n5-held-tail-deadline` | NATIVE PASS |
| `n7-close-first-0` | NATIVE PASS |
| `n7-sample-first-0` | NATIVE PASS |
| `n7-concurrent-0` | NATIVE PASS |
| `n7-concurrent-1` | NATIVE PASS |
| `n7-concurrent-2` | NATIVE PASS |

Each selected test exited 0 with exact expected Go event coverage and no skips.
All execution receipts show complete streams, clean owned retirement, no failures
or held resources, removed helpers and verified capability absence. All eight
registered JVMs closed. Original selectors, capability policies, counter
predicates and deadlines were preserved; conversion followed retirement.
The N1 normal case uses production sizing `20` at unchanged 8 MiB reader memlock;
the separate 8192-emission pressure test remains unchanged. This sizing does not
enable a 20 Hz CPU sampler.

Evidence below is under `/tmp/opencode/master-merge-mr5fp6z4/`:

- Actual invocation: `inspection-9nx17p27/primary-invocation.json`, SHA256
  `bbb7cbca50a2832e97113d5e79446efbecd06f940a84d0890a455e1136bae67c`.
- Current per-case execution, converted-event and qualification receipts:
  `inspection-9nx17p27/qualification/`. Matrix result SHA256
  `8e3c355f364737e671d88766eb7a865983225e343a23eec418c9d78de07c3b14`.
- Independent acceptance: `inspection-3i8iwo0d/qualification-acceptance.md`, SHA256
  `0b2b23d9534122c6b1348b90f382d1ab8eb23811dce174e492302813248e1a90`.
- Primary verification and post-run pinned-input checks:
  `inspection-4sjbhvbu/primary-outcome-verification.json`, SHA256
  `ac47a4d6d81017d8c5060aa46de5467a787024f0dd382529124cbc51f1c55270`.

The successful `native-runtime-selection-final7e-qualification-03` authority is
consumed and non-reusable. Earlier preparation scopes and failed receipts remain
retained: scope01 stopped in read-only namespace validation; scope02 rejected a
stale ABI recipient literal before attempt creation, helpers or Go launch. The
namespace observation now holds and closes an owned FD while preserving every
metadata equality check; the first scope01 mismatch's exact cause remains
unproved. No old runtime scope was reused or cleaned up.

**Issue 042 remains open.** Selected-case qualification is not aggregate event
conservation, reconciliation, all-source completeness, resolved-method-name
proof or installed running-kernel-image identity. Those claims remain separate
and unproved. The full project build passed for this canonical candidate; no
commit, push, merge or worktree is part of this qualification.

## Historical checkpoint: 2026-11-06

The single fresh `n5-held-tail-deadline` native case passed execution, primary
verification and independent outcome review. The qualification ledger is
**11/17 with mixed-receipt provenance**: ten preserved historical passes plus
this fresh held-tail pass, not a fresh full-matrix qualification. Review:
`inspection-za9b2eeg/single-held-tail-native-outcome-review.json`, SHA256
`8a3f5579b4b2a421bc7eb1a7f1fd9211cf2ee053a779d444c645aa41532fd8b0`.

The reviewed timeout warning is the expected held-tail cancellation branch, not
an unhandled native error. All24 Wait/close proofs,48 stream EOF/close proofs,
127/127 source closures and grant absence were verified. Only this attempt was
consumed; the other16 attempt paths remain absent. The one-shot authority is
non-reusable, and no further native run, retry or cleanup is authorized.

This issue remains **open**: five N7 cases and the separate N1 mmap case remain
unqualified. Aggregate counter readout, reconciliation and all-source completeness
are unproved; the full project build remains unproved after the preserved timeout.
Historical missing-Wait/source-closure/producer-EOF failures and their UNKNOWN
cause remain unchanged. Nothing here establishes collection or policy coverage.

The operator subsequently authorized one fresh `n7-sample-first-0` scope. Its
source-bound primary509-refusal host gate and separately Grok-reviewed capless
preparation/postcontrol passed. Primary verified115 outputs and17 inactive,
unconsumed, capless selections in fresh root `native-runtime-selection-5s9i_azd`,
with all attempt paths and approval absent:
`inspection-ufbruynq/sample-first-capless-outcome-verification.json`, SHA256
`0027549c09cd3798dfa89bf6d2e6d803dce3f23b185550d4178ea93afa3e571f`.
Fresh ABI/sample-first passives and the unchanged host-tamper suite subsequently
passed under exact-source Grok effect review, with all17 states unchanged and a
shared fresh parent binding (not the held-tail parent replayed):
`inspection-bk7w8u04/sample-first-host-tamper-outcome-verification.json`, SHA256
`7d104d33ed6a7d1a84b0e9be9df53348e8ee4f15cf1e39d64ce7b37de067e92f`.
The rebound28-case request controls then passed one host-positive decision and27
targeted refusals, independently verified against all original labels/guard lines:
`inspection-d28h7k7c/sample-first-actual28-outcome-verification.json`, SHA256
`f88b1ade0b22b004fed47161fa8737e7537380006e9181a5ea39124d958869f9`.
The authorized single `n7-sample-first-0` invocation consumed its authority and
stopped after incomplete retirement; it qualified no case. Invocation:
`/tmp/opencode/master-merge-mr5fp6z4/inspection-n81gaj_m/single-sample-first-native-invocation.json`,
SHA256 `95eacdde272b19a771ea16047eb72392d4ee6ccbaec30d572a21a64e1d44b12f`.
Read-only evidence:
`/tmp/opencode/master-merge-mr5fp6z4/inspection-lzu9_v7k/stopped-sample-first-native-evidence.json`,
SHA256 `001f1f3f4d2bab603719c3e0aaca205627548f3075d93cd4f8a305bd4ab61f4a`.
Grok's exact-outcome review returned `STOP_INCOMPLETE_NOT_QUALIFIED`:
`/tmp/opencode/master-merge-mr5fp6z4/inspection-lwqo6zrs/grok-stopped-sample-first-outcome-review.json`,
SHA256 `dc463df4bd954016c31df0fbb2bdcae264f9dfd1f9cbb88b45964e066e50cebb`.
The ledger remains 11/17 mixed-receipt provenance, not 12/17. The explicit
matrix stop preceded conversion and qualification; the underlying and historical
causes remain UNKNOWN. Five N7 cases and N1, aggregate conservation,
reconciliation, all-source completeness and kernel-image proof remain unproved.
The recorded PIDs and unremoved helpers may still be live; no live probe was
made. No retry, cleanup, signals, grants, deadline change or new native call is
authorized.

The subsequent static trace returned `STATIC_TRACE_ONLY_ROOT_CAUSE_UNKNOWN`:
`/tmp/opencode/master-merge-mr5fp6z4/inspection-0k8oycxv/stopped-sample-first-static-source-trace.json`,
SHA256 `04a101cc993432937daabb46d84233801456a2353b8ba028da21c138e085d928`.
The deadline label is not proof of mutex blocking; `heldComposition` did not
return and `ArmSample` was not reached. This attempt did not exercise the
intended sample-first race. The cause remains UNKNOWN and no fix, deadline
increase or predicate weakening is recommended.

## 2026-11-03 operator-authorized definition-source increment

The operator requested continued delegated work with explicit uncertainty
reporting. Start with aggregate native `trace_events` emission and reader-handoff
observations, independently bound to the capture-owned target lifetime and
original maps. The accepted scope and review evidence are under
`.superpowers/sdd/2026-11-03-hotspot-definition-accounting/`.

This is one definition subsource, not a denominator for semantic definitions.
An unchanged payload lacks universal attempt identity: count equality can conceal
duplicate/missing substitution. Do not promote this observation into definitions,
collection completeness, coverage or policy eligibility. Enrichment, wire and
retention remain separate. No public payload/schema/CLI change or dependency
upgrade is authorized by this increment.

The scoped reach above includes initial-PID-namespace/lifetime validation,
constructor forwarding, actual native counters/readers, original-owner stop
ordering, paired overlays and their real provenance consumers. Changed native
inputs require a measured new patch ID and genuinely prepared artifacts before
publication; old snapshots, bundles and consumed receipts remain historical.

The shared parser's unchecked custom-label count is tracked separately as
issue-20261103-222740-448. Its bounded count11 crash was reproduced and a minimal
guard passes the four boundary cases and existing tracer race tests in an
isolated source copy. The integration checkpoint below supersedes the earlier
pending build status; neither issue is closed or a new native capture qualified.

### 2026-11-04 source and host-control checkpoint

At that checkpoint, the genuine113-input normal digest was `383f2170…`. Normal preparation, both
profile-specific bundles, Go race checks, the full JVM build, and installed-JAR
verification passed. The latter compared all1041 compiled classes and all nine
installed JARs; the53 affected synthetic-provider tests also pass. The transient
enforcer startup timeout and an incorrect verifier class-family assumption retain
their original failed receipts alongside the subsequent acceptance.

The final isolated native overlay compiles into five exact test binaries. Its
source controls pass10 nodes; all11 real Go/Python ownership cases pass, including
SETTLED before delayed actual Wait and genuine close-error/partial-owner cases.
A negative case exposed premature coordinator close on HOLD; the corrected reader
retains SOURCE_CLOSED facts, preserves failure and passes a targeted RED/GREEN
control plus23+6 runner/helper regressions. Independent scoped reviews found no
remaining static blocker in the reviewed paths.

Evidence and exact identities are recorded in
`.superpowers/sdd/2026-11-03-hotspot-definition-accounting/progress.md`.
Native N1–N7 runtime qualification, final recipient/grant approval and the fresh
bounded observation remain pending. Keep **open**: aggregate accounting still
does not prove per-attempt reconciliation or all-source semantic completeness.

### 2026-11-04 post-Start runtime correction

A fresh observation reached401 received/397 resolved/4 unavailable but failed
finalization: Gadget Start intentionally releases its CollectionSpec/BTF, while
the new shutdown/readout paths incorrectly reused admission-time validation.
That attempt remains REJECTED with its grant removed and owned resources retired.
The preceding probe-access failure and its independent collector-retirement
uncertainty remain preserved.

The corrected admission/runtime split reproduces the failure on old source and
passes53 focused race nodes on the new source. Independent review found no
concrete blocker. Genuine normal113-input digest `22f66bf5…5035` is measured,
live consumers are repinned, and fresh collector/tool, Gadget image, and strict
and observed bundles have been genuinely rebuilt. The related host AST-projection
fixture correction passes its focused check and the full106-node capture package;
the normal runtime digest is unchanged by that test-only correction.

Exact receipts are in the progress document above and the fresh root
`/tmp/opencode/definition-accounting-poststart-final-44q0oojx`. The full build,
94 affected JVM tests, nine installed JARs and1041 compiled-class comparisons pass.
Fresh observation `fresh-normal-observation-fjlikhj9/attempt-once` also passes:
401 received/397 resolved/4 unavailable,400 marked,388 useful,176 compiled-event
IDs, complete finalization/physical drain and successful close. Its capability
grant is absent and no owned resources remain held. Coverage/collection-loss
completeness and policy eligibility remain false.

Refreshed native N1–N7 qualification remains incomplete. The operator-approved
17-recipient selection `native-runtime-selection-vhmckbyy/` completed with `abi`,
`n2`, `n3` and `n6-lifetime` passing and13 rejected cases. All processes and streams
retired, helpers and grants were removed, and held resources were zero. Every
attempt remains preserved in `approved-native-matrix-auu5cw20/` under the final root.
Eight composition failures exposed a SerialGC fixture launch incompatible with the
required observed G1 profile. The exact two-mirror flag correction has passed source
preservation, formatting, five-binary compilation, fresh binding checks and review.
The first fresh G1 retry passes profile admission but blocks waiting for the
fixture's consumer condition. The matrix stops before launching the other seven;
its required Wait/finalization evidence remains missing and the attempt stays
BLOCKED. Later process absence and grant absence are recorded separately from
qualification. A separately authorized180-second handoff removed the two stopped
owned helpers and passed the operator's independent physical-inventory boundary;
it did not repair the old frozen Wait/finalization gate. The five separately
approved expanded-policy cases then completed with full retirement and grants
absent: `n5-reader-tail`, `n6-map` and `n5-original-root` pass; `n1` and `n4` remain
rejected. N1 now fails loading `kprobe_unwind_stop` with EINVAL. N4 fails its
`short743` expectation. A fresh diagnostic run now records743 input bytes received
and handed off past the744-byte reader guard; exact raw length remains unmeasured.
Joined passes fresh native qualification. The remaining run stops at direct-drain
with incomplete retirement; the other six composition cases were never launched.
The separately authorized180-second handoff removes its two stopped owned helpers
and passes the independent physical-inventory boundary. The failed attempt remains
BLOCKED with missing Wait unproven. N1 retains two perf-array references in the failing kprobe program, but
its summary-only verifier log leaves the exact EINVAL cause unconfirmed.
The reviewed joined and isolated N1/N4 diagnostics are installed as native-only
assets. Their exact thirteen-operation source reconstruction and five-binary build
pass, alongside13 focused host race nodes and11 real Go/Python ownership cases.
The strict rebinding factory,172 source rejection checks and fresh ownership/mount
controls pass. All three diagnostic attempts fully retire with grants absent.
N1's fixture preparation differs from production; a single-variable causal probe
and the measured N4 expectation correction pass external patch/format checks and
pass independent source review. The direct-drain diagnostic extension also passes
source review. All eight follow-up assets are applied with exact identity checks;
the fresh2480-input/13-operation reconstruction and five-binary build pass.
Fresh13-node host race checks,11 real ownership cases and221 source rejection checks
also pass. Factory review and fresh binding checks pass, but the first admission
rejects at the namespace consistency guard before approval or any case launch.
A read-only probe does not reproduce it. A fresh binding and scoped delta review
then pass with the guard unchanged. The fresh native run qualifies N4, bringing
the retained matrix evidence to nine of17 cases. N1's map-only retry succeeds, while
its deliberate diagnostic failure keeps the case unqualified. The reviewed production-parity
fixture correction and bounded existing-output metadata are now applied in six mirrored
paths. Full protected-identity checks and the fresh2480-input/13-operation/five-binary
build pass, together with17 focused host race nodes and11 real ownership cases.
The corrected strict factory passes14 control groups with377 rejection checks;
independent factory/binder review and fresh17-recipient capless binding checks pass.
Exact fresh-binding admission review passes. The new run qualifies N4 again, while
N1 completes all five ordinary program loads, then fails CPU0 perf-reader mmap with
`operation not permitted`; the precise denial cause remains unproved.
Direct-drain again blocks before its intended drain assertion; its bounded cached
metadata records eight zero-reference events with `missing_reference` enrichment.
The new attempt remains BLOCKED. Its separately bound180-second handoff now clears
the physical inventory without repairing original Wait/finalization or qualification. The
producer-level cause and whole-run production remain unproven. Accepted reader
behavior and normal113 inputs remain unchanged.
The next reviewed measurement successor is applied in the same six assets. It adds
non-atomic N1 pre-reader facts and bounded existing-record timestamp/HotSpot details;
an unsafe same-owner reader retry is omitted. Exact source/application checks pass.
Its fresh2480-input/five-binary build,21-node host/race checks and11 real ownership
controls pass. Corrected strict-factory source controls pass18 groups/477 rejections;
independent factory/binder review and fresh17-recipient capless binding checks pass.
Exact fresh admission passes. The measured run again qualifies N4, rejects N1 at
CPU0 mmap and stops on direct-drain's incomplete retirement. New N1 pre-call facts
show an8MiB memlock limit and a calculated32MiB data ring; the exact denial cause
remains unproved. The new direct attempt stays BLOCKED. Its own bound physical
handoff removed both stopped helpers with verified CLI retirement and PID/grant
absence; that separate inventory result does not repair the original unknown Wait.
The recorded XanMod release source is now identified, but running-image provenance
and the exact mmap denial branch remain unproved. Direct-event epoch-field absence
is expected for Observed25; first-burst versus asynchronous PID-discovery ordering
is the leading unproved producer hypothesis.
The reviewed next observation uses at most two borrowed-map PID-page0 lookups at
existing snapshot points. Its four-asset proposal passes59 private host/race test
nodes, normalized-source checks and independent code/build-envelope review. The exact
four assets are applied and verified, with normal inputs and Git state preserved.
The clean native build,21 metadata host nodes and11 real-ownership cases passed.
Runtime-source review also passed. Operator-approved runner/manifest stabilization
passed independent review:23 host regressions, genuine-input validation,477 inherited
rejection cases and32 supplemental observer controls passed through current-action
receipt ownership checks. Fresh capless17 preparation, its postcontrol, all17 genuine
passive bindings and host-tamper/mount controls passed. Exact binding review accepted
the one N4/direct invocation, which is now consumed: N4 passed, direct remains
BLOCKED_OR_INCOMPLETE_RETIREMENT and the aggregate is INCOMPLETE. The held-reporter
fixture failed before the direct-drain branch; an eBPF shutdown deadline error occurred
with58.5 seconds still remaining on the test context. Neither output cap was reached.
PID-page0 was ABSENT before hold and PRESENT after burst; those samples do not prove
membership during the burst or completed readiness. Zero nonfinal counters are not
a final readout. Retirement remains unproved; separate recovery authorization is
required before physical cleanup or retry. The completion `./gradlew build` passed;
native qualification remains9/17.
Connected-source investigation found Run's separate one-minute hard event deadline,
consistent with the failure timing; the first cancellation source and missing
producer/reporter handoff remain unproved. A native-only boundary diagnostic passed
seven host race-test nodes and strict patch/source reconstruction; independent review
accepted the four assets, which are now applied. It preserves unordered context/error
observations before deferred cancellation, without changing deadlines, workload or
PID-page0 lookups.
Fresh read-only inventory found all three recorded processes absent, the recipient
capability absent and both exact helpers exited0. A separately bound cleanup proposal
passed33 host-only controls and independent review. After exact operator authorization,
one bounded execution removed both helpers, retaining late identity/absence checks,
ten retired CLI commands and twenty closed EOF streams. Original Wait/join evidence
remains incomplete; that cleanup authority is consumed. A fresh five-candidate build,
three diagnostic-publication tests and three real process-ownership controls passed.
Fresh capless17 preparation and actual postcontrol passed; a tuple/list handoff
assertion failure remains preserved, with exact audited-receipt recovery and no
preparation replay. Two passive bindings and the unchanged host controls passed.
Independent review then accepted one direct-only invocation under the existing
standing continuation, without increasing deadlines or workload.
The fresh direct-drain case passed with a genuine reporter handoff and retained
original root, exit0, actual fixture Wait/source closure, full NativeJoin retirement,
both helpers removed and the capability grant absent. Its authoritative aggregate is
`approved-native-run-boundary-direct-6b5offxi/result.json`, SHA256
`eee502d6942f56ee8166d88d847d1af1f983328aa08115b6fff9ebed6559de3c`.
This qualifies the direct-drain refusal scenario; qualification is now **10/17**.
No dependent tail/N7 cases were run. The earlier cancellation/missing-handoff failure
did not recur, and its cause remains unproved; no failure-boundary diagnostic was
emitted in this successful run. Historical failed/consumed receipts remain intact.
The operator then continued with a separately reviewed fresh binding for held-tail
deadline and five N7 cases. Preparation, seven passive bindings and28 request
controls passed. The single serial invocation stopped at its first case:
`approved-native-remaining-six-e_aw85e7/result.json`, SHA256
`10247b30399352af12b9a675322cf7a2738dabfccb2de63672c515e12b18ba8c`, is INCOMPLETE;
held-tail is BLOCKED_OR_INCOMPLETE_RETIREMENT, with no N7 launch or new qualification.
It failed the common reporter-held-mutex prerequisite before the mode-specific
held-tail assertions. Original test/fixture Wait, source closure and logical stream
EOF remain unproved. Two exited helpers are recorded unremoved; no current physical
inventory, retry, signal or post-deadline cleanup was performed. That whole admission
is consumed, not permission to run its unused rows. Qualification remains **10/17**;
the first cancellation source and missing handoff cause are still unproved.
Independent outcome review confirms that blocked classification; focused document,
link, backlog and approved-asset checks passed. Further native work cannot use the
consumed batch or old cleanup authority to bypass its unresolved ownership boundary.
The issue remains **open**; per-attempt reconciliation, all-source semantic
completeness and the earlier collector-retirement uncertainty are not promoted.

### Earlier selected-event baseline (unchanged)

Pure models and isolated actual kernel counter qualification implemented. Filtered
openat512attempts/255submits/257reserve failures/255received conserve; native sticky
overflow and all12possibleCPU slots tested. Production counter binding/definition
source denominators and global attestation remain unproved; keep open. See
[evidence](../../plans/2026-10-05-hotspot-loss-accounting-evidence.md).

---
## Repository-review qualification — 2026-11-04

The [review evidence](../reviews/2026-11-04-repository-review-evidence.md#point-in-time-drift-check)
records concurrent canonical/mirrored definition-accounting test edits; all were preserved.
The current selected native graph must prove final sampling occurs only inside the original
attachment's retirement authority, after root detach/in-flight exclusion and reader/consumer
joins. Direct drain, aliases, concurrent stop, timeout and permission failures need negatives;
synthetic readout tests cannot prove no native writer remains. HS-02 owns actual overlay test
selection. New HS09-03 separately owns strict Kotlin acceptance of missing/contradictory finality;
do not duplicate that host correction here or treat it as native/all-source qualification.
Historical selected-conservation acceptance remains scoped to its original source/artifact.

---
<!-- id: issue-20261005-193140-042-prove-selected-hotspot-event-conservation-and-all-  file: issue-20261005-193140-042-prove-selected-hotspot-event-conservation-and-all-.md -->

## 2026-10-17 Plan04 Task2 Step1 proposed-contract boundary

Decision: **`not_authorized_pending_operator_review`**. The
[selected accounting contract](../../plans/2026-10-17-hotspot-selected-accounting-contract.md)
proposes `selected_counts` PerCPUArray/key4/value32/entries1, acquired
session/PID/birth/map-ID/possible-CPU binding, real detach/flush/receiver and
auxiliary joins before final read, owner-mutex publication/deep copy, and an optional
schema1 selected-only diagnostic. It records actual current patch/bundle/image
identities and deterministic future provenance derivation with all nine pin
consumers; no next patch/image identity exists before approved source edits/builds.

This is contract preparation only, not native implementation approval. Independent
review and explicit operator approval must resolve acquisition timing (current
prepare precedes collection creation), identity/API/publication locking, failure
and pending-owner paths, exact map/schema/provenance compatibility, and the full
negative/pressure/live/public proof gates. Native/BPF/Go/Kotlin/ABI/schema/pins,
generated graphs and historical evidence remain unchanged by this docs-only work.
Existing delivery budgets and production profile stay fixed. Selected accounting
completeness is neither zero loss, global collection completeness nor policy
eligibility. Keep this issue open; host Task1 proof is not production binding proof.

## 2026-10-18 operator-requested scoped implementation

The operator explicitly requested continuing corrected Plan04 Task2's exact reviewed
selected-map/optional-schema/provenance scope; controller approval is recorded by
reference. The former `not_authorized_pending_operator_review` label is historical.
Disposition: `operator_requested_scoped_implementation_qualification_pending`.
Early strict target parsing/options copy and immediate post-acquisition binding
precede publication/startup; actual native/live acceptance remains required. This
is no broader runtime authority and never promotes global completeness or policy.
The all-source inventory/qualification issue remains open.

Scoped implementation now supplies the real acquired selected-map binding and
optional final diagnostic, with host/race, rootful map/owner/pressure controls and
actual compiled public acceptance evidence. Accepted patch:
`270a89f6dafb03afe2131b219f98673e30be79223eb68a11a5703b66d8f6a127`.
Locked repeated helper/OCI builds match and full graph verification passes. The
contract records exact helper/image/manifest identities and unchanged global
refusal. This does not close the definition/all-source denominator gap, promote
dense readout, repair historical native identity failures or confer runtime lifetime
authority. Keep open for the remaining source inventory and independent review.

## 2026-10-18 Plan04 Task3 structured production inventory

The controller-authorized optional raw-summary `unproven_collection_sources` now
uses structured Binding/Final/Counts lane evidence at full owned finalization.
Only Task2's actual independently expected selected-map binding, final native
rows/parent and checked delivery can prove `selected_events`. Invalid/missing
selected proof retains that unknown lane. Healthy native/public runs report exactly
`definitions`, `enrichment`, `wire`, `retention` as unproven; reader joins, zero
loss and selected terminal counts cannot prove their independent admission.

Fresh installed public proof preserves802 selected occurrences/12CPUs, selected
complete=true, globalfalse and checked retirement. Actual definition-drop,
held-reader/auxiliary, broken native stdout, retention0 and target-exec controls,
host/race and Kotlin ABI/copy/refusal checks are recorded in the
[production inventory evidence](../../plans/2026-10-17-hotspot-production-loss-evidence.md).
Two locked Go1.26.0 helper builds and complete existing-OCI bundles match; authored
patch270a remains unchanged. Keep **open** until actual all-source admission and
delivery proof exists. This diagnostic does not promote global completeness,
policy eligibility, dense readout or runtime lifetime/identity qualification.

## Readout-action deadline independence — 2026-11-05

[Recursive review D-02](../reviews/2026-11-05-state-complexity-review.md)
adds a source-confirmed lock/deadline schedule to the definition-source increment.
`profiler/hotspot-agentless/patches/gadget-additions/pkg/operators/ebpf/definition_readout.go.txt:43–65`
holds `eventStopOwner.mu` across the admitted readout action; deadline return and
snapshot require that same mutex in `event_finalization_owner.go.txt:163–177,192–195`.
The real action samples original native maps. Hold it inside a valid native-Join
window and expire stop: pending/error reporting cannot return before the effect
releases the lock. No ordinary map hang or historical blocked-run cause is proven.

Separate short locked admission/publication from the readout effect, retaining an
explicit in-flight **original-root/map** lease/count. Simply unlocking the action
would lose resource exclusion. Timeout/window closure cannot retire its resource
lease; reject newly expired admission and revalidate before final publication.
Late completion must not promote an expired capture to successful finality.

Add a channel-gated authored host/race test through actual fixture/native Join:
hold the **inner** admitted WithDefinitionReadout action, cancel/expire the owner,
and require both stop's partial/error result and snapshot before release with
resources unretired. Include a duplicate stop waiter, refused expired readout,
original permission-error identity and eventual goroutine cleanup. The existing
outer-callback TryLock test and immediate-return cancellation control do not cover
this inner-action lock schedule.

Preserve target/map/native-Join validation, typed native errors, budgets and all
original authority negatives. Actual map/link exclusion and retirement remain
native qualification, not host proof. Current passing observations and native
matrix status above are unchanged historical receipts; do not attribute their
blocked cases to this schedule without causal evidence or increase deadlines as
a substitute. This review authorizes no native run, artifact repin or publication.

## Held-tail failure diagnostics — 2026-11-05

Qualification remains **10/17**. The remaining-six invocation stopped in the
common reporter-handoff prerequisite, before held-tail-specific assertions;
all five N7 cases remained unstarted and that admission is consumed. Current
physical absence/capability checks do not repair its missing Wait, source closure
or producer EOF.

The canonical/mirror native-composition controls now enable the existing
failure-only logger and published early-Run-completion checks for the exact
held-tail leaf. The live diagnostic/PID-probe gate, output collector, workload,
deadlines, capabilities, BPF/maps/ABI and retirement rules are unchanged. A meaningful
host regression was observed RED, followed by five passing diagnostic/publication
nodes under Go1.27.3/race. These are host diagnostics, not native qualification or
proof of the original handoff cause.

The exact four-asset application is recorded in
`inspection-kcg0_dax/held-failure-four-asset-application.json`, SHA256
`3358f76610ac20fd3d85e4f35bca9425dd584a8a3c42bafd66c2e7752c6398be`,
under the task's `/tmp/opencode/master-merge-mr5fp6z4/` evidence root.
Fresh reviewed source/build, rebinding, actual passives and a separate runtime
gate are still required before one diagnostic held-tail attempt. Original failed
receipts and audit deviations remain preserved in the task progress record.
N1's CPU0 perf-ring mmap refusal remains separate; a memlock/resource-contract
change is not authorized by this diagnostic correction. Aggregate positive
readout, per-attempt reconciliation and all-source completeness remain unproved.

### Fresh build and separate recovery — 2026-11-06

The corrected source-preparation driver subsequently produced a fresh source and
five candidate binaries; its build receipt is explicitly
COMPILED_CANDIDATES_NOT_RUNTIME_APPROVED. Actual five-node timestamped host
metadata and three host ownership modes are available. They do not qualify a
new native run. The successor catalogue and composed source passed the actual
read-only preflight: 16,893 catalogue inputs, the 2,491-file current source closure,
five metadata nodes and three stored ownership modes were verified. Its receipt
is explicitly `CURRENT_PREFLIGHT_NOT_ADMISSION`, with no materialization or native
qualification: `inspection-irvs6w6f/action-receipt.json`, SHA256
`ad76bf01fb7e86438c365811625de94614ef90c1dd5baab8407f08d7e2495dca`.
The earlier schema-rejected catalogue and failed preflight remain preserved.
The subsequent inherited host-control gate stopped at a stale historical positive
fixture in `g1_factory_controls`; three preceding groups passed, but the full gate
has not passed. Its failed receipt is preserved as
`inspection-fhd62biw/inherited-host-controls-invocation.json`, SHA256
`84f208d6ea26450b4b7b5622c3c26c16636b5a76091aebf3fc61b65da24300f5`.
After reviewed fixture updates, all18 inherited groups passed477 refusals, but the
successor invocation stopped in supplemental observer controls on a stale builder
alias. Its failed receipt is preserved:
`inspection-7qyv91tz/successor-host-gate-invocation.json`, SHA256
`d09cbf2f426eff9df529019eac010e6647520b30c7845a6fcab1e4ca2b1185b8`.
After the supplemental fixture correction, a fresh complete host gate passed all
18 groups/477 inherited refusals plus32 observer refusals. Primary verified every
retained result and the `HOST_ONLY` action receipt:
`inspection-p09ridb2/full-host-gate-outcome-verification.json`, SHA256
`71728d2a810e824158777b2ec2c05e39bb874b77c4f8bc7c0154c9d6c9f0d301`.
Earlier failed gates remain preserved and consumed; this host PASS grants no
native qualification. Actual capless preparation, passives and a separate runtime gate remain
outstanding; the full project build also remains unproved after its recorded
timeout.

The subsequent reviewed capless preparation stopped on an uncatalogued OCI input
after19 retained outputs, before post-control or native execution. Its failed
receipt and partial root are preserved without cleanup or reuse:
`inspection-na7pclmq/capless-preparation-invocation.json`, SHA256
`e3a48d23e03ee93365917b26f86349b11f772b6210c543272b37cf401dac6acc`.
A read-only closure audit found eight OCI files and three pinned design documents
missing from the catalogue. A successor adds only those11 input identities; fresh
effect review is required before any new preparation. No17-row success is claimed.
A subsequent fresh reviewed preparation stopped on OCI parent mode0775 under the
host umask; the permission guard was not relaxed. That failure and its second
partial root are preserved (`inspection-xuxfpi_3/capless-preparation-invocation.json`,
SHA256 `d1f0177855865cb0d2f5124dfc5776774f158b5847cf921e3b7f1614c9b2b1a6`).
A minimal factory correction creates each new OCI parent with explicitmode0700;
source review and a fresh complete host gate precede any new preparation scope.
The explicit-private-parent factory then passed a fresh509-refusal host gate and
a separately reviewed preparation/postcontrol. Primary independently verified all
115 retained outputs and17 capless, inactive, unconsumed selections in fresh root
`native-runtime-selection-y8ez1qkd`, with all attempt/approval paths absent:
`inspection-w72ukjpe/actual-capless-17-row-outcome-verification.json`, SHA256
`6f5c584049a01bf47c93cfaba0e1628987ba467847f9710172f2073b6e44b290`.
Only held-tail is planned. Fresh passives, request controls and a separate native
effect gate remain outstanding; preparation itself grants no runtime authority.
The exact ABI and held-tail passive checks subsequently passed with matching parent
bindings and unchanged17-row state:
`inspection-vqrclj9b/two-fresh-passives-outcome-verification.json`, SHA256
`5f4fa276f5b18999c473a1c6dcb478c05c6085fccf4d9aed2d84e5797a462d4a`.
These are passive checks, not execution of the native ABI runner. Actual-data
request controls and the separate native effect gate are still required.
Fresh unchanged host-tamper controls then passed with the same parent binding.
The reconstructed actual-data request suite passed one host-only positive case
and27 targeted refusals, preserving all original28 labels and checking intended
rejection lines rather than counting unrelated schema failures:
`inspection-pww4ei85/actual28-request-controls-verification.json`, SHA256
`6d7ce1a0771ff979dca2b3bbf9df70514ce7e654934bf9c47557365ad57bffb1`.
No request or approval was retained by those tests. A concrete held-tail-only
executor/continuation proposal is under separate native-effect review; native
qualification remains10/17 and no aggregate positive proof is inferred.
The separately reviewed single held-tail native invocation then returned exit0
and `NATIVE_CASE_QUALIFICATION_PASS`. Primary outcome verification checked24 Wait/
close proofs,48 EOF/closed streams,127/127 source descriptors closed, fixture Wait,
grant absence and the other16 attempt paths remaining absent:
`inspection-bx4bmo5n/single-held-tail-native-outcome-verification.json`, SHA256
`a88cbc2f2b261f404469f5911c22e77650cee64350662762780185d7a31f2396`.
Its one-shot authority is consumed. A final read-only outcome review is checking
the receiver-deadline stderr warning and qualification-ledger scope. Native test
stderr is retained, not discarded; converter diagnostics are empty. Historical
failed receipts remain unchanged. No aggregate counter readout, reconciliation or
all-source completeness proof is established by this one native case.

The two exact stopped helpers from the blocked held-tail attempt were removed in
one separately reviewed call, with ten actual command Wait/retirement proofs and
twenty closed EOF streams. Independent outcome review accepts only the recorded
separate physical recovery:
`inspection-i7gs20iw/p7-actual-cleanup-outcome-verdict.md`, SHA256
`0582a0c78e2e9fe9ea4d056d76a4eaec948228e0673621f3c3062f2e593cf0f9`.
That cleanup authority is consumed. The old native Wait, source closure and
producer EOF remain unproved; the original failure is not reclassified. Issue042
remains open at **10/17**, with handoff cause and aggregate positive proof still
unresolved. No further cleanup, signal, grant or native run is authorized by that
outcome review.

### Latest checkpoint: partial inventory and host-only diagnostic

Issue042 remains open at **11/17** mixed provenance; no new case or aggregate
proof was qualified. The historical timeout's root cause remains UNKNOWN.
The approved one-time read-only inventory did not prove resource clearance:
PID 787702's proc path was missing at observation time only; PID 787849 was
readable but identity remains unproved without its historical boot ID, despite
matching recorded start tick and mount namespace. Both helper containers are
`UNKNOWN_SCHEMA`: stdout bytes were not retained, so their state cannot be
recovered from hashes. An offline correction established that the executed
format matched the approved newline template; the schema failure cause remains
unknown. No retry or cleanup was performed, and `native.test` was not opened.
Historical capability observations do not prove current retirement.

The bounded timeout-retention diagnostic passed its offline host checks and an
independent `ACCEPT_HOST_ONLY_RETENTION_PATCH` review. It retains already
collected relevant timeout diagnostics within a 16 KiB body and 256-byte header;
the existing 8 MiB output guard can still drop them. Host checks do not exercise
`runtime.Stack` or a real reporter timeout; no new native binary, native run,
source/binary closure, qualification or full build was produced. The old
420-second timeout is not green. A further native attempt or cleanup requires
separate approval; the consumed authority is not reused.

Receipts under `/tmp/opencode/master-merge-mr5fp6z4/`:
`inspection-ibilkqjk/sample-first-readonly-inventory-result.json` (SHA-256
`ee53951a901dc0b40a1b460c89965eaa03931298acf0ccdeab783ae9ddcc9ff1`),
`inspection-2xuu7cap/sample-first-container-inventory-offline-interpretation.json`
(SHA-256 `a433188c6613954a5b995b981f9b3f1abd9fbac81ee979cd723b546e3656de29`),
`inspection-sm_q4ene/timeout-retention-host-verified-result.json` (SHA-256
`2ba8d26ee9aa9ab42fe5bfd2783db4219605465d79c084f68ff0501e3fb977de`), and
`inspection-artihfof/grok-timeout-retention-implementation-review.json`
(SHA-256 `223b8a17bbf695558dc3e0ac8ada8eba5cd38aa51cad585da2fa881a596cb699`).

### Latest checkpoint: fresh container-only observation

At 2026-11-06T17:46:21Z, the separately reviewed fresh inspection observed the
exact grant and revoke helpers as `exited`, not running, PID 0, exit code 0;
both CLI calls exited 0 and full IDs, names, owner and roles matched. Both are
`OBSERVED_PRESENT_TEMPLATE_FIELDS_MATCHED_DAEMON_UNPROVEN`: historical daemon
identity, retirement and resource clearance remain unproved. Raw stdout was
retained before parsing and independently verified; stderr was empty and not
retained as raw bytes. The previous `UNKNOWN_SCHEMA` result and correction
remain historical: its missing raw stdout prevents determining its cause.

PID 787702 was previously observed missing and PID 787849 previously readable
with identity unproved; neither was re-probed and neither describes current
state. Root cause remains UNKNOWN, the ledger remains 11/17, and five N7 cases,
N1 and aggregate proof remain unqualified. The diagnostic build recipe is only
read-only preparation; no new binary, full build or ADK validator was run. This
observation authorizes no further probe, native run or cleanup.

Receipts under `/tmp/opencode/master-merge-mr5fp6z4/`:
`inspection-i9xien7b/grok-container-only-inspection-effect-review.json`
(SHA-256 `69b77f7216c5f8c7f8bf64b17f644869830f255d9fee79d544ffc0e8f9e5260f`),
`inspection-1xrzxul_/container-inspection-result.json` (SHA-256
`bab557f49bcb3ee65b4194902cb0e739dcfcc1a0ad6abc88c90acf5935d97b8b`),
`inspection-1xrzxul_/container-only-inspection-invocation.json` (SHA-256
`80b5899d49876d879cb38dbe3e366464b0d13fca8352e504a3b37c71b6bac02b`), and
`inspection-xb2ycvae/primary-container-observation-verification.json` (SHA-256
`ecd32b61621301dd2e3efe1b58fb690fbff55f5d949942b9785efd50ea88a206`).

### Latest checkpoint: diagnostic candidate post-validated

The fresh diagnostic candidate compiled and was separately post-validated,
but has not been executed or qualified. The original compile transaction
remains blocked: module verification and compilation passed, then a bounded
whole-file acquisition rejected a four-byte binary read before build-info
checking. The post-only validation passed without recompiling or executing the
candidate; independent review accepted that evidence for preparation only. This
harness read failure is not the historical timeout root cause. The new binary's
SHA-256 is `b8f1ba26073367cf5bfad995ab435f022127910a48979408b74f2240307bda29`.

The v2 orchestrator's source-bound host checks passed 13 tests; a separate
receipt corrects the earlier test-source pin. A fresh native diagnostic plan is
not an execution: a source-only adapter and critical plan review are in progress.
No new native attempt, qualification or ledger advance occurred. Historical
source, build, diagnostic and applied-operations identities remain distinct;
the old applied-operations JSON is not proof of a compiler overlay.

The stopped sample-first authority remains consumed, with historical Wait,
EOF and source closure unproved. Earlier helper and PID observations do not
prove clearance. Root cause remains UNKNOWN and the ledger remains **11/17**;
five N7 cases, N1, aggregate conservation, reconciliation and all-source
completeness remain unproved. The old 420-second timeout is not green. No full
project build or ADK backlog validator was run; its transitive effects were
previously uncertain. No automatic cleanup permission follows.

Receipts under `/tmp/opencode/master-merge-mr5fp6z4/`:
`inspection-apq7loa2/compile-transaction-result.json` (SHA-256
`814ec3d63c3d139be7754f8f9eb50b952384a8a11345afacafde2491e9883d34`),
`inspection-lindtui_/post-validation-receipt.json` (SHA-256
`3cfba50b3e7d142cfe2a04b024e7dcb84d08edc704e2ceb2e5512a5e981f8963`),
`inspection-ybswtfho/grok-diagnostic-binary-postvalidation-review.json`
(SHA-256 `8ee2d1da7fddabc10cfdf83b5784202b179492a936fad79e05020323b975b849`),
`inspection-08_8kizi/host-integration-v2-source-bound-results.json` (SHA-256
`863b8ab22d4b8d1211699fcbc129960bca428845de9e2419359fc96f4fa2599d`),
`inspection-utq5tb0j/fresh-retention-native-diagnostic-plan.json` (SHA-256
`81632752f1f3f9ab337702f43da816640f6241572f4722b843e3580f580d4739`), and
`inspection-integration-antigravity/test-v2-pin-attribution-correction.json`
(SHA-256 `fe82c639ffcf58f94b69f8a37ce3f297b213ead0a6b4e3a1af8b5a43dc721c10`).

### Retention diagnostic follow-up

The consumed `n7-sample-first-0` diagnostic at root `c297b1c9` did not qualify:
its complete 12-goroutine snapshot had `matched=false`, no relevant reporter,
and no captured Run result. The timeout label is not proof of mutex blocking;
the OCI context-deadline stderr is a close warning, not the Run return. Root
cause remains UNKNOWN. Retirement is blocked, stream EOF is missing and helper
removal is unproved; the ledger remains **11/17**. The failed stale alias index
and foreign NNP evidence remain preserved. The preceding 28 real host controls
passed without granting native qualification.

The host-only diagnostics correction observes the existing completion channel
after an unmatched snapshot and reports the published Run return, including nil;
an open channel is included in timeout metadata. The reporting loop, predicate,
timing, allowlist, caps, deadlines and ABI are unchanged. This is not a fix or
proof of the underlying timeout. Focused host tests recorded RED then GREEN,
with source hashes pinned before and after. Nine retained output lengths and
hashes were verified in `inspection-pw689k0b/nine-output-verification.json`
(SHA-256 `4a8c5a945637eeff5c3885c45260f314c64130cb7119e83351863272c8d71d1d`).
The full forensic report is `inspection-76j7nm3j/issue042-forensic-report.txt`
(SHA-256 `e557cc99f7ec1c35a9125cecdc954a9bb680586ad31fadfa50321ee9a85aa67c`);
RED and GREEN stdout are `inspection-hn4dbqc4/red.stdout` and
`inspection-w7pz7id6/green.stdout`, under `/tmp/opencode/master-merge-mr5fp6z4/`.
No native compile, retry, cleanup or qualification occurred for this correction.
The full Gradle build remains outstanding; the old 420-second timeout is not green.

#### Subsequent host-only completion checks

Grok accepted the Run-completion diagnostic correction in
`inspection-l24d9m3y/grok-run-completion-diagnostic-review.json` (SHA-256
`fcf401cab58901463110045db33dc9ed1bdcb81cd1deebab22723f24b532786d`).
The subsequent `./scripts/adkw check-backlog` and `./gradlew build --no-daemon`
both exited 0. The build took 344.04 seconds, executing 32 tasks and reusing 201
up-to-date tasks; this is not evidence that all tests ran afresh. Receipts:
`inspection-kkxqkd65/backlog.json` (SHA-256
`e5cc76d2d0ea35414d02d02de2efb76725086dad49e7e82120dee5f223c77fa1`) and
`inspection-kkxqkd65/build.json` (SHA-256
`65036cb4349f6ab22a3be7ce25b58b9e0cefc0bf2a85b4307121765403bf162c`).
Their four output streams and receipts were independently hash/length checked.
Paths are relative to `/tmp/opencode/master-merge-mr5fp6z4/`. These later checks
resolve the outstanding host gates, not the historical failed receipt or native
qualification. No native rebuild, retry or cleanup occurred; root cause,
retirement, reconciliation and aggregate conservation remain unproved. The issue
stays open at **11/17 mixed-receipt provenance**.

### N7 close-first diagnostic follow-up

The genuine DIAG sample-clean `mygb` result does not qualify a native case.
The fresh six-case N1 attempt was rejected on `mmap` EPERM; an RLIMIT cause is
not established. Separately, the N7 close-first case failed inside
`heldComposition`, before the close/sample branch. Its Run return includes a
context deadline and owned-readout-pending/drain-incomplete errors, consequences
of the 60-second context rather than an established initiating cause. Retirement
was incomplete, four cases were unattempted, and the root cause remains UNKNOWN.
Consumed authorities, including `c297`, `ff53`, `a6` and N7 root `3b917`, remain
held and must not be retried. No native case passed; the ledger stays **11/17**.

The source-only failure diagnostic now takes one existing tracer snapshot while
the consumer hold remains held, then logs `snapshotErr`, `handedOff`, `received`,
`sourceError`, and `readerJoined` before the unchanged Run fatal. A failed
snapshot is logged distinctly: its zero result is not evidence of zero handoffs.
No timeout, predicate, readiness, BPF, allowlist or native protocol changed.
This host-tested addition has not been native-compiled or run, and does not fix
the underlying timeout or establish map validity after Run.

Evidence under `/tmp/opencode/master-merge-mr5fp6z4/`: raw outcome
`inspection-lrf3uvck/n7-close-outcome.json` (SHA-256
`43371341484ec1a42f9d1dbf78dcff8662547196f25f56c4fe5bd899da2e1169`),
forensics `inspection-k9872m5z/issue042-n7-root-cause-report.txt` (SHA-256
`ec8f5a175511fe8a49ff52e50048543d03f47c79a9535c03e019d6efed6c08e9`),
critical review `inspection-tx5814tp/grok-n7-close-first-root-cause-review.json`
(SHA-256 `e104d90d06e5f87efed9a015c950f253aff267f6651c77c9eacd37d02ba97104`),
and lock-safety addendum `inspection-fgz6kuvl/grok-n7-close-first-root-cause-addendum.json`
(SHA-256 `18d460b9fe7dc83428da381d22abebd7b05fdf2cb7724e9a407a1f900aa68b73`).
Focused host RED `inspection-mnm_q94e/red.stdout` (SHA-256
`063aca4c2d6c8b82fa68f5e94c0c6182592693afd40a3e7385076a54f9befbf8`),
receipt `inspection-dtjm26iu/red.json` (SHA-256
`a9f64e29b6fbffdf3c5c4dfbad0fc6bee35f3b3a86bbbe9b5231f0e6faa30fb8`);
GREEN `inspection-iykgxmjq/green.stdout` (SHA-256
`17fc731b17ffd50847ed6a1edf123d6442194ffbee784149abba876de74fbe1b`),
receipt and before/after source pins `inspection-iykgxmjq/green.json` (SHA-256
`9945ba858411ee7e400f9ee0694f3084aa72c488a740a6f9ed963be3601809d5`).
The full build and native qualification remain separate future gates for this revision.

#### Prepared follow-up; independent review provider blocked

The failure-only handoff snapshot passed Grok review
`inspection-dfuedwx8/grok-n7-failure-handoff-snapshot-review.json` (SHA-256
`7d5786765264713e5e8e3c1cc3a6906605f8c1c4bf7861a645875579dbf87209`).
A new capture binary `59a30b48d45265c11c8ddd970a6f777c867a7647b9e6b4859e5416119d212e7a`
was compiled once, without execution or qualification. Compile receipt:
`inspection-b9e2xzdu/compile-transaction-result.json` (SHA-256
`3c660415266196f24078c843ca021f83e099dac21616f3bf3392a1b4f08ca57e`);
independent validation: `inspection-hfzpg4og/independent-validation.json`
(SHA-256 `4aad408f8094117d99fc579c75de384226b1033ba870e26a6d9d9bb46a1287ff`).
The earlier independent reader-limit failure was retained and corrected without
recompilation. Source changes are only the controls diagnostic and module paths.

An independent N1 child-memlock setup patch is host-tested but remains inert:
`inspection-tf9wyy2_/n1-reader-memlock.patch` (SHA-256
`0a339438f9a76f6301d63638aaf08f90b37d14f5fe23f91fb2010f73ef67e868`).
It proposes no new capability, ring reduction, parent-limit change, or permission
fallback. It is a controlled experiment, not a demonstrated fix for `mmap EPERM`.

The next preparation review is blocked by provider credit/subscription refusals
on both Grok routes. No model substitution, billing change, or further privileged
attempt occurred. Qualification remains **11/17**; failed receipts and consumed
authorities remain intact. Evidence paths are under
`/tmp/opencode/master-merge-mr5fp6z4/`; see progress for the new binary path.

#### Checked bootstrap implementation; qualification unchanged

The operator subsequently authorized Sol/Terra/Antigravity reviews. The fresh
snapshot-aware N7 attempt stopped on close-first before the actual race, with
`snapshotErr=nil`, `received=0`, `handedOff=0`, `sourceError=nil` and
`readerJoined=true`; retirement remained incomplete. Outcome:
`inspection-9xxgzo59/snapshot-n7-outcome.json`, SHA-256
`d7bf7ced0c8bbc48980d6e59960305e158fdf304e876fad482b547345b91909e`.
The remaining four cases were not attempted.

The canonical source now fixes the confirmed startup-ordering gap: the bound
strict HotSpot lane completes checked PM synchronization and actual interpreter
readiness before `NewTracer` returns and PID workers start. The existing target
lifetime is validated before and after synchronization, before commit. Exact
acquisition rollback and constructor-owned cleanup preserve original causes and
unresolved ownership; ordinary/legacy behavior remains unchanged. Open issue 849
tracks the prerequisite executable-map ownership repair.

Sol accepted the focused corrections in
`inspection-3xagjsn3/sol-pm-tracer-bootstrap-corrections-rereview.json`, SHA-256
`a0aec15db301a660b3027f61e23114eb5cea6bd071f9fe24a77bc3f3614f6245`.
Canonical overlay postimages match the tested host source
(`inspection-o5br_jrj/overlay-equality.json`). Four host packages, targeted race
regressions, constructor checks and preparation identity checks pass; receipts
are listed in the progress document. The new preparation fingerprint is
`96d1ce7879a42727bc008fb8eeadf9e38f7e575733814505bf02d6269ebc7d02`.
The latest complete project gate `./gradlew build --no-daemon`, after the
asset-admission pin correction, passed in 233 seconds (32 executed, 201 up-to-date):
`inspection-zwz9slpj/full-build.json`, SHA-256
`477ee5016d129e4a1f88862f380b2952f84f623915449378c8793b1972080af1`.
Fresh source-bound native evidence is still pending. No deadline, stimulus,
capability policy or runtime agent was added.

Qualification remains **11/17 mixed-receipt provenance**. This host fix does not
prove the exact earlier failed branch, aggregate conservation, reconciliation,
all-source completeness or kernel-image identity. N1's child-memlock experiment
remains inert and separate. All failed receipts and consumed authorities remain
preserved. Inspection paths are under `/tmp/opencode/master-merge-mr5fp6z4/`.

Fresh native preparation is now environment-blocked, not review-credit-blocked:
the configured Docker API socket `/run/user/1000/podman/podman.sock` is absent,
and the local Podman CLI is unavailable. Read-only receipts:
`inspection-il47wt28/builder-preflight.json` and
`inspection-dq99a7sj/rootless-builder-preflight.json`. No container, pull,
installation, privilege fallback or native attempt occurred.

A verified exclusive source copy is ready at
`/tmp/opencode/definition-accounting-poststart-final-44q0oojx/bootstrap96-preparation-source-01`
(272 files, 1,196,114 bytes; `inspection-qew6c3ep/source-copy.json`, SHA-256
`ee30f882541c253d6d82d6df165490f858d10e78693c3b019610cef57913a766`).
The asset-admission caller now pins `96d1ce78…` too; its old-bundle rejection
regression and existing asset/provenance checks pass (`inspection-o497yych`),
and Sol accepted the correction (`inspection-qlxk7jjq`). Restore the reviewed
build-engine endpoint, then perform actual fresh preparation/image building;
do not combine the old image with the new graph by changing manifest pins.
The separate N1 experiment remains inert, and qualification stays **11/17**.

### Subsequent C39 preparation and compile milestone

The checked bootstrap fix is implemented in source fingerprint
`c39ce3f723e2a707fadf154e55c6fb39ba532f3280022bf09cb350bafdabf4f4`.
Scoped independent review (`d909…`), host and race checks, and the full build
passed; the build receipt SHA-256 is
`0fd3ed644e3b5e25e37d314e221df575a850855099a7655166074d25b0869fce`.
Actual rootful C39 preparation took 47.8 seconds and its owned container was
absent afterward. A freshly compiled CLI built the image once and retired its
owned container; independent OCI verification confirmed the same prior root
digest `sha256:3a436e0448238645c9cd7c40fdf8d95d8e1791e2b577c04bd0bf8a63f1313710`,
fresh build evidence rather than kernel proof. A fresh compile-only capture ELF
is 101,843,896 bytes, SHA-256
`a1920debee11574c2dbb9621225181338e58e045d7bd76c24adc27eafd3cc798`;
independent verification decoded C39 from the ELF and checked 2,538 source
files. No profiling retry has occurred.

Receipts under `/tmp/opencode/master-merge-mr5fp6z4/`:
`inspection-om7l6t10/prepared-source-verification.json` (SHA-256 `a136347c1cc66dc99cf49994fb56868bdaf39999b4a75cf60911b7ef17e283a6`),
`inspection-19fr5j81/image-cli-compile.json` (`8ced48c937b7c795d7b327d772a9a79d83779df86784abc2d344b4dc4d6db663`),
`inspection-didz4z5m/image-build-result.json` (`1c6c333ac26cefaec5ff572ba2c30ff6f432ab18c7dd0a6238defd68b0788953`),
`inspection-0da7gfjv/oci-graph-verification.json` (`b81bb017388b779fd06ec147b5a6bb1f14e4dbfb70ce4dc5bf8916a996414f08`),
`inspection-7oktrgcr/compile-transaction-result.json` (`fdd093c35d1b1f13054a3d9d6241d951eaa2111969a4ed04878d521ad6ccc886`), and
`inspection-2uia6k9k/capture-independent-verification.json` (`b1cf4a6fc28ac6e07dd42c0d94061dd2ca5635344c4c1ebfba0edbe81e8609fb`).

Qualification remains **11/17**; future case bindings are still in progress.
The exact cause of the kernel zero remains unproven, incomplete retirement is
preserved, and consumed roots are not reused. All-source aggregate conservation,
reconciliation and kernel image proof remain separate. The older 96 native
attempt failed close-first before the race, and its other four cases were not
attempted or requalified.

### Current blocker: first C39 native entry

Fresh C39 `n7-close-first-0` entered once and was **REJECTED** with direct
exit 1: fatal `UpdateStackDeltaPages` batch-update `EINVAL` occurred in strict
observed HotSpot synchronization before capture readiness. The bootstrap fix
executed and failed closed with the actual error; outer ownership retired
cleanly with zero held resources. Serial execution stopped and the other four
cases were not attempted. This is neither a completed capture nor a qualified
race; the ledger remains **11/17** without pass promotion.

The precise kernel cause is not established. Investigate the map contract,
flags and marshalling in source only; no new native execution or blind retry
is authorized. Retain consumed selection, authority and result roots without
reuse, probing or cleanup. All-source aggregate conservation, reconciliation
and kernel image proof remain separate.

Independent outcome review:
`/tmp/opencode/master-merge-mr5fp6z4/inspection-qetedto0/c39-first-entry-independent-outcome-review.json`
(SHA-256 `09a5065e841df509c48fddf49a76510a0777d8b6fe2014dc2b91ef182cd4aa9d`);
actual entry invocation:
`/tmp/opencode/master-merge-mr5fp6z4/inspection-zgw22pao/c39-first-entry-invocation.json`
(SHA-256 `c0336d1906aec6d7e9af51570343958daca9b45474aa1142337a5e11d0feb6e9`).

### Stack-delta page ownership repair

Source verification of the recorded XanMod release shows that its generic hash
batch update rejects `BPF_NOEXIST` before mutation. The installed kernel image
and the exact live rejection branch remain unproven. Independently, the old
batch rollback could delete an identical entry inserted by another writer after
its prelookup. The stack-delta page path now uses atomic scalar `UpdateNoExist`
and rolls back only successfully inserted pages, preserving insertion and
cleanup errors. Existing lifecycle serialization remains required; this does
not establish ownership across an external delete-and-reinsert ABA race.

The ownership regression failed against the prior code and the focused host
race checks passed against the patched source; no kernel or native test was
run for this repair. The earlier component attempt did not retain A/B/C
results or prove grant absence. Qualification remains **11/17**; no case or
native bundle is promoted by this change. Current preparation identity:
`dfff04b46286bc9a7385d9ad945362403434a2253543c7fb8cfebbe414d748cd`.

### Normal admission fixture parity

The normal N1 admission fixture now uses the pinned Gadget's buffer-sizing
value of 20 rather than its historical, unexplained 1024. This does not add a
continuous 20 Hz sampler: the production path is event-only. The separate
8192-emission overflow and recovery pressure test is unchanged, as are the
normal fixture's producer and reader predicates. The historical 1024 witness
remains distinct; the 20-value native diagnostic has not yet been qualified.
The new preparation identity is
`7e09e472bd37c2c7b03aeb14dad509a0b56485bcfef2ddcb56e0891a2c08c7f9`;
this source correction alone does not promote the qualification ledger.
