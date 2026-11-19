# Issue 042: Vanilla-Kernel Compatibility Plan

**Execution update:** use `2026-11-14-issue-042-production-completion-plan.md` for the next concrete jobs. This document retains the design and completed model/native evidence; it is not a second active execution queue.

> **Plan only.** No Linux patches, custom kernel build, special kernel module, installation or application changes are authorized by writing this document. Implementation should use small native-Antigravity assignments: Hydrogen for implementation, Argon for a specific difficult proof question. The parent owns coordination and acceptance.

**Goal:** Support ordinary recent Linux/distro kernels while retaining honest production conservation and independent reconciliation claims.

**Architecture:** Discover actual kernel capabilities instead of demanding a private kernel implementation. Keep collection, observed accounting and proven completeness distinct. Replace the blanket classic-return counter-width rule only with an independently justified nonwrap proof; unsupported evidence remains UNKNOWN, never an accepting certificate.

**Tech stack:** Existing Go collector, pinned Gadget/profiler/Cilium sources, eBPF and Kotlin reporting. No new dependency or public API change is planned.

**Spec:** Operator requirement: “not linux patching for sure, it must work with almost any vanilla linux kernel of recent years,” clarified as “6.12 is good enough.” The supported kernel target is therefore **unmodified Linux 6.12+**. Preserve the original outcome and proof denominators in `2026-11-08-hotspot-production-accounting-proof-plan.md`. This document proposes replacing one implementation requirement—not reducing the original five-lane completion criteria. It supersedes the kernel-provider/width dead end in the earlier recovery sequence, not its completed work or ownership invariants.

## Decisions and limits

- No kernel patches or requirements for a special kernel build. Do not substitute another unsupported private probe mechanism.
- Target the currently packaged Linux-amd64 collector on **Linux 6.12, subsequent LTS and current-stable distro kernels**. No 5.x, 6.1 or 6.6 compatibility/fallback work is required. These are test targets, not a claim of support already established. Other architectures need their existing packaging and qualification coverage.
- Feature-detect BPF/BTF, attachment types, metadata availability and permissions. Distro backports make release-number checks insufficient. A normal kernel configuration may omit a required feature; report the precise prerequisite.
- Broad collection support does not mean every kernel can issue the strongest proof. Missing loss/lifetime/retirement evidence means UNKNOWN for that claim. It does not mean the data is complete, and does not close 042.
- Preserve EPERM/EACCES and other real failures. Optional unsupported proof metadata is not permission to swallow security, attachment or I/O errors or silently downgrade an explicitly requested proof.
- Preserve every original selected/definition/auxiliary/PID/enrichment/wire/retention obligation, independent per-attempt reconciliation, actual resource identities, absolute deadlines, immutable first receipts and actual-return ownership.
- Preserve four classic pairs, three fentry/fexit pairs, fourteen witness slots, independent socket Q, CAS64/OR64, actual multicast Cut, ordinary global membarrier requirements and active-nohz_full rejection for the existing witness claim. Unsupported combinations can collect only where the existing collection contract allows it; they cannot gain that claim by omitting a source.
- Keep the default-invalid 80-byte `kw_layout` contract. A new private counter changes its own map-value decoding/identity and generated assets, not that certificate's meaning or size.
- No dependency churn, exported issuer, new diagnostic framework, blanket fallback flag, worktree, cleanup, publishing or kernel installation.

## What is already finished

The standard patch chains compose exactly: 81 Gadget hunks plus 282 profiler hunks. Hydrogen's header-only fixes passed independent Argon review. Do not restart that work or discard WIP.

Both source files were retrieved at Ubuntu resolute revision `f53b359683af7918c5436499948763cb726d5082`: `include/linux/kprobes.h` stores `kretprobe.nmissed` as `int`; `kernel/trace/trace_kprobe.c` includes that component in return-probe missed counts. A wider exported aggregate does not widen the underlying storage. Evidence: `/tmp/opencode/issue042-pinned-provider-q04sgd16/acquisition.json` and the current `argon-provider-report.md` in the recovery workspace.

Current native guard code also explicitly reports width/lifetime as unqualified. Fixing the 32-bit issue alone will not establish Open, Fence, ordinary-RCU provenance, observer lifetime or all-source completeness.

## Recommended stock-kernel approach

Do not remove `missWidth == 64` and accept zero. Do not assume fentry exists for the classic targets or that observing another function proves their kretprobes never missed.

Investigate one narrower replacement: prove that a fresh return probe cannot have received enough invocations to overflow its signed 32-bit miss component.

For each classic entry/return pair, the proposed evidence is:

1. A fresh, zero-initialized observer generation. Nothing is reset or renewed while either attachment can still run.
2. An atomic 64-bit counter of **all** target entries, before correlation, admission and scope-gate filtering. Existing `attempted` counters cover admitted work and cannot serve this purpose.
3. Reliable entry-probe miss and BPF-recursion evidence for the whole window, including a justified lifetime/nonwrap bound for those observers themselves. An unavailable field, unexplained reset or guessed width rejects qualification.
4. Entry coverage begins before return attachment and remains through the relevant observations and actual return-probe retirement. Qualify an ordered readout while retaining the same objects: first sample return-miss aggregates; complete the existing ordinary global membarrier/RCU grace period; then sample the all-entry counts and entry guards. This can conservatively bound the earlier miss history without pretending the objects have stopped changing. Registration races, shared-site handler ordering and failed partial attachment need source evidence; attachment call order alone is not a certificate. The original epoch Fence must independently establish that the earlier miss sample covers all work being certified.
5. At most one `kretprobe.nmissed` increment per covered target invocation, established for the applicable kernel implementation. No inference from one Ubuntu revision to every stock kernel.
6. All-entry count `N < 2^31`, no counter overflow/fault, and a zero return-miss aggregate. Under the preceding conditions, the signed 32-bit component cannot wrap or become negative, so aggregate zero cannot hide misses through overflow/cancellation.

The proposed decision is conceptually:

```text
if any required identity, guard, lifetime, retirement or sampling fact is unproved:
    UNKNOWN(reason)
else if any observed miss, recursion loss or counter fault is nonzero:
    reject zero-loss claim
else if classic-return underlying counter is 32-bit:
    qualify only with independently established entry coverage and N < 2^31
else:
    apply the existing independently justified counter-width/lifetime rule
```

This is a proposed proof method, not production authority already obtained. `N == 2^31 - 1` is inside the numeric bound; `N == 2^31` is outside. Smaller conservative operational limits may be useful, but elapsed time or workload averages are not causal bounds. MODELed arithmetic tests must not mint a native certificate.

If the coverage argument cannot be established on a kernel, preserve UNKNOWN. Do not spend another implementation cycle hiding that gap in metadata.

### Pre-implementation paper/source evaluation

**Result: conditionally sound arithmetic; broad-kernel proof feasibility is not established.** No application implementation or test execution was needed to find the following obligations.

- If the complete number of target calls `T` is bounded by the observed all-entry count `C`, and `C < 2^31`, the return miss component cannot overflow. With a corresponding bound on the other aggregate component, a zero aggregate cannot conceal nonzero misses. The difficult premise is `T <= C`, not the comparison itself.
- A small readout is not evidence of complete coverage. Qualifying the entry observer's whole lifetime and its own nonwrap independently is mandatory; using its observed small count to prove it did not miss or wrap would be circular. Current native guard code deliberately leaves width/lifetime unqualified.
- A toy signed three-bit miss counter illustrates the sampling race: sample a small entry count, allow eight more calls while return probes miss, then read a wrapped zero miss count. The earlier entry snapshot is still below four, but is not a bound on the later history. Keeping attachment identities unchanged does not fix this race; final sampling needs actual quiescence and full-window coverage.
- Upstream **v6.12** has the needed `bpf_link_info.perf_event`/missed fields and `bpf_prog_info.recursion_misses`. Earlier 5.15/6.1 API gaps are outside the clarified support floor; do not build a legacy fallback. Full source observations remain at `/tmp/opencode/issue042-vanilla-uapi-p_dul84b/uapi-source-observations.json`.
- Native Argon's source inspection of **v6.12** found that `PERF_EVENT_IOC_DISABLE` does **not** freeze this PMU attachment's BPF callbacks or underlying miss counters. The event's profile registration and BPF program array remain active. Do not implement a disable-then-read protocol on that assumption. Relevant sources are retained under `/tmp/opencode/issue042-v612-disable-source/` and `/tmp/opencode/master-merge-mr5fp6z4/inspection-f_fm0gpq/`.
- The alternative numeric argument is conditionally sound: read return misses, finish an ordinary global RCU grace period, then read entry bounds. It relies on the paired entry/return target sharing a non-preemptible dispatch section, no earlier handler bypassing the entry observer, an unconditional atomic entry count, immutable object lifetime and independently qualified 64-bit nonwrap. Verify the entire relevant handler chain, not just our own program's lack of `bpf_override_return`. This establishes a bound on the earlier miss sample only; it does not prove completion of hooked function returns, Open/Fence or actual retirement.

**Pre-implementation check:** the bounded model below now tests ordering, delayed handlers, calls arriving during the grace period, entry-counter bypass, failed grace periods and overflow/cancellation arithmetic. Source facts must still establish the premises on stock 6.12, including immutable whole-lifetime identity, no reset and trustworthy nonwrapping guard metadata. Model success is not native qualification.

### Bounded interleaving model test: completed, reported review findings addressed

Final model: `/tmp/opencode/rcu_miss_bound_model/model.py`, SHA256 `bef5ccf0cd469dcd0573b5d64685f30f09ca56feb7e852159da9d789b0128839`. Initial/intermediate sources are preserved as `model.orig.py`, `model.v2.py` and `model.v3.py`. Parent independently executed the pre-final-oracle-fix model: exit 0, 107.559 seconds, receipt `/tmp/opencode/issue042-corrected-model-check-xio6tirr/`. Hydrogen executed the full final model with exit 0 and saved `/tmp/opencode/rcu_miss_bound_model/full_run.log`, SHA256 `04957df9b2aa522f45e51af46e35da4d910c09070833f5aa07fd2972462ab15c`. Parent verified the exact oracle correction, byte-identical worker/reader transitions and cohort stepping, current source identity, successful log and all positive/negative result rows (`inspection-n890olyq`, `inspection-xbc2z847`).

- Toy signed three-bit miss counter; at most eight concurrent/nine total callbacks. Explores miss-first and entry-first handler orders separately, with symmetry reduction.
- Correctly ordered variants, including explicit entry-bypass/metadata handling and grace-period failure: 6,207,432 states total, zero reported qualifying bound violations and zero false accepts. All variants together: 15,870,624 states / 54,468,751 transitions; broken variants intentionally produce false accepts. These are finite-model counts, not kernel operations or claims about arbitrary-sized histories.
- Negative controls found false acceptance when entry is sampled first, when a miss-first handler is not actually covered by a grace period, and when the count bound is omitted. A fake fence is not required to fail with entry-first handlers because that order already provides the tested bound.
- Entry-bypass negative control has a genuine prior-history witness: eight missed returns wrap the toy counter to zero, only three entries are counted and five bypasses appear in entry metadata. Ignoring that metadata accepts the bad history; requiring zero rejects it. This is distinct from post-sample metadata loss.
- Actual failed-GP transitions reject without proceeding to entry readout. Ordinary rejections with nonzero loss are separately labeled, not called failed syscall tests.
- Toy arithmetic uses a wider unsigned component and wider wire representation. Six additional vectors test one signed 32-bit return component plus one unsigned 64-bit component, including full wrap and signed cancellation. An intermediate two-signed-component checker was corrected before the final parent run.
- Post-sample misses remain possible after a successful sampled-history bound. The initial algebraic two-epoch helper assumed its answer and was removed; whole-epoch closure is explicitly not modeled or proven. Another counter read alone is not a replacement for real epoch retirement.

Native Argon reviews identified auxiliary-check gaps and one remaining oracle-labeling issue. All reported findings were corrected: genuine prior sampled losses are now distinct from missing entry premises/post-sample metadata; the ignore-entry-guard controls must expose actual earlier loss (64/68 false-accept states in the two orders); the combined count-plus-entry-metadata bound is checked for both ordered-GP variants. Parent verified this final narrow change instead of starting another broad review cycle. These are bounded-model observations, not Linux execution results. Mixed per-callback order within one run, object replacement/reset, guard-counter wrap and the real memory model remain assumptions/unmodeled cases. Production/kernel source is unchanged.

## Work order and concrete deliverables

### Isolated native premise check completed

The guest-only callback/global-membarrier experiment passed three QUERY controls and three GLOBAL trials on stock Debian **6.12.117**, using actual kernel entry/return timestamps and unchanged PMU program/link identities. QUERY returned while the callback was held; GLOBAL returned only after the explicitly released callback completed. No callback timeout occurred. Parent verified the raw results; independent Argon review confirmed the default-run oracle.

Full provenance, hashes, raw-result paths, reproduction and temporary-helper limitations are in `.superpowers/sdd/2026-11-14-issue-042-recovery-plan/native-rcu-experiment-result.md`. This validates one empirical ordering premise on one configuration, not the all-entry counter policy, whole-epoch proof, Open/Fence/retirement or all-source completeness. Production collector code is unchanged. Do not turn this result into an accepting native certificate or repeat the same default test solely to produce more review labels.

### 1. Establish the collection/claim boundary on stock kernels

**Files:** `profiler/hotspot-agentless/internal/tooling/prepare.go`, `profiler/hotspot-agentless/README.md`, existing collector startup/capture code, `internal/capture/definition_local.go`, `definition_accounting.go` and their tests. Read actual callers before changing startup behavior.

- [ ] Identify which existing unsupported witness/metadata results affect only proof and which prevent required collection. Keep the existing finite result/error types; do not introduce a capability framework.
- [ ] Add a focused test for a kernel with usable collection attachments but no loss-information field: the explicit unsupported reason survives and no complete claim is emitted.
- [ ] Add the separate permission-denied case: it remains an error, not “feature absent.” Preserve existing behavior when the caller requires that capability.
- [ ] Keep the collector free of an exact kernel-revision allowlist. Document actual required BPF permissions/configuration separately from optional proof capabilities.

**Done:** one testable compatibility decision path and accurate status propagation—not a promise that unsupported kernels satisfy 042.

### 2. Resolve the entry-bounded nonwrap argument before accepting it

**Files:** `patches/profiler-additions/tracer/kobject_witness.go.txt`, `kobject_witness_native.go.txt`, their existing tests, `patches/profiler-kobject-witness.patch`; matching `experiments/hotspot-agentless/` mirrors. Primary kernel source for supported families is evidence, not an application dependency.

- [ ] Argon answers one bounded question: do the actual entry/return registration, execution and retirement paths cover every possible increment of the return miss component? Include shared-site dispatch, recursion suppression, partial startup and in-flight handlers; name any counterexample.
- [ ] Verify the bound premise and available metadata across the representative stock source families; do not treat BTF presence, a version number or one successful attachment as lifetime authority.
- [ ] Define a small private qualification decision using existing guard/readout types. New evidence must represent established coverage, not an unchecked public boolean or a caller-supplied duration.
- [ ] Add arithmetic/policy controls: clean evidence below the bound; exactly `2^31 - 1`; exactly `2^31`; huge/wrapped-looking counts; nonzero loss; unavailable entry metadata; missing coverage; wrong generation and overlapping/reset lifetime. MODEL-only results stay MODEL-only.

**Done:** a reviewable argument and rejecting policy controls. If the argument fails, stop this proof mechanism—not all portable collector work—and state the exact original proof criterion still unavailable. Do not implement an accepting shortcut.

### 3. Wire the real all-entry counter and observation window

**Files:** `kw_counts_value`/`kw_stage_enter` in `profiler-kobject-witness.patch`; `attachKobjectNativeObjectsWithCalls`, `readKobjectNativeGuardsWithCalls` and native map decoding/readout in `kobject_witness_native.go.txt`; `validateKobjectReadout` and existing witness tests. Update exact mirrors and affected private map identities/decoders together.

- [ ] Add an atomic all-entry count for each classic target to the existing private native readout. Count before admission/correlation filtering; do not repurpose `attempted`. Persist an overflow/fault indication rather than permit an apparently small wrapped count.
- [ ] Make the real entry-observation lifetime enclose the return-observation lifetime, including partial failures and late actual returns. Retain original ownership; no replacement worker, second reader, renewed scope or timer.
- [ ] Record the actual direction-specific counter representation and qualified coverage. Entry `unsigned long` is not automatically 64-bit on an arbitrary architecture; all widths come from justified evidence.
- [ ] Connect the immutable copies of ordered return-miss/entry-bound samples and original identities to the private policy from Task 2. Do not describe live kernel counters as frozen or use perf disable as a quiescence barrier. Unsupported cases remain nonaccepting; do not mark width/lifetime qualified merely because the new counter exists.
- [ ] Add production-seam controls for unrelated/non-admitted calls, held in-flight entry handlers, partial attachment, failed detach, absent metadata and overflow. Demonstrate that the existing admitted-attempt count would give the wrong bound for the unrelated-call case.

**Done:** actual counter-to-readout wiring and guarded ownership on the same candidate, with no synthetic accepting native authority. Recheck full patch composition after body changes; refresh generated inputs before executable checks.

### 4. Finish the already-needed same-reader shutdown path

**Files:** `patches/profiler-additions/tracer/reader_health.go.txt` and tests, `profiler-auxiliary-ownership.patch`, `profiler-kobject-witness.patch`, existing `auxiliary_stop`/`definition_owner`/`pid_mailbox` additions, `periodiccaller/owned_scope.go.txt` and tests; exact mirrors.

Dispatch these as separate concrete jobs, not one “finish lifecycle” assignment:

- [ ] Wire the existing controller's one-shot Flush and requested same-loop `ErrFlushed` to the actual reader. A second reader or standalone accounting test is not the deliverable.
- [ ] Gate that reader's existing closer on actual Flush/read/callback return. Held work retains the resource; the original deadline/cause cannot be renewed or overwritten.
- [ ] Connect ordinary producer retirement and the existing covered final readout, including the actual `readPIDResidual` caller and actual final report counters. Sampling is after normal-job join **inside** `Scope.Stop`, before lifetime release.
- [ ] Route remaining constructor acquisition failures through their existing actual retaining owner after borrowers return. Preserve the narrowly scoped failed-startup quarantine exception; no new generic cleanup mechanism.

**Done:** one controlled end-to-end stop trace from actual producer retirement through the same reader to final original-resource readout. Held detach/Flush/read/callback and expired-but-Done-open controls must prevent premature close or fresh effects.

### 5. Reconcile one current candidate across every original lane

**Files:** existing Gadget finalization/cached-envelope additions, `internal/capture/{selected_accounting,definition_accounting,definition_local,inventory}.go` and tests, actual Kotlin hotspot accounting/retention/document callers and tests. Use existing source indexes to identify exact callers; do not send an implementer an entire repository inventory task.

- [ ] Forward the final original-owner snapshots through existing cached envelopes without delaying native outcome publication or resampling after lifetime release.
- [ ] Run current selected/per-attempt independent reconciliation, all enabled auxiliary/report/PID/store conservation, actual enrichment delivery, exact wire prefix/EOF and client retention controls. A static source list or zero count does not prove runtime coverage.
- [ ] Verify missing source, extra source, sink failure, truncation, discard, overflow and absent native authority each prevent the appropriate complete claim.
- [ ] Preserve the historical selected qualification unchanged. Keep issue 042 open until its original all-source conjunction is actually met; broad collection support is not substitute acceptance.
- [ ] Resolve the eight missing broader mirrors from their actual callers/layout rules, then make only individually justified mirror additions. Do not duplicate files just to satisfy a blanket equality assertion.

**Done:** one current candidate whose connected accounting is tested and whose remaining UNKNOWN reasons are precise. No parallel acceptance boards or new report per helper.

### 6. Verify on real stock kernels and publish actual support

**Files:** existing `tests/native-composition/` controls, qualification tooling and release/source manifests, `README.md`, original issue acceptance record and relevant profiler integration tests.

- [ ] Prepare the reviewed source and generate fresh native assets/manifests using the existing tooling; the current manifest is stale. Review real preparation effects, toolchain prerequisites and downloads as ordinary build work, not an invented operator approval gate. Do not install a kernel or mutate a production host.
- [ ] Use focused compilation and host controls first, then one `./gradlew build` at the complete-candidate checkpoint. Run the equivalent selected kernel suites in the appropriate isolated environment; do not rerun equivalent suites merely to generate more labels.
- [ ] Exercise actual kernels in VMs or existing isolated test hosts. Different distro containers on one host do **not** test different Linux kernels. Cover the target LTS families and current stable, representative distribution configs, BTF/metadata present/absent, permissions, attachment availability and forced-loss cases.
- [ ] Record separately: collection works; accounting reconciles; exact native/all-source proof is qualified or unavailable with reason. Include stock-kernel source/feature identities, not just release strings. No custom `CONFIG_*` rebuild requirement may be smuggled into broad support.
- [ ] Close 042 only when original current-production evidence satisfies every required row. If stock kernels still cannot supply a remaining premise, report it as a remaining product/proof limitation rather than demand kernel patches or claim completion.

**Done:** a tested compatibility statement and original acceptance decision backed by real current artifacts. Do not advertise the proposed kernel matrix as supported before these runs exist.

## Delegation discipline

Each assignment contains only the specific file/symbol, one required result, necessary safety constraints and its done condition. The parent retains historical context, cross-task reconciliation and this plan. Let a running agent finish; do not cancel/rewrite its job to adjust presentation. Native Antigravity only: Hydrogen implementation; Argon for a bounded difficult question. One connected-change review, not duplicate micro-reviews or automatic replacement-agent loops.

**First implementation job after plan approval:** Task 1's existing collection/claim decision and unsupported-versus-permission tests. The first hard question is Task 2's coverage argument. Do not dispatch the entire six-task plan to either agent.

## What this plan does not promise

It does not establish that exact all-source completeness is attainable on almost every recent kernel. It makes stock-kernel compatibility nonnegotiable, proposes a concrete stock-kernel route around the known counter-width obstacle, and preserves UNKNOWN wherever a proof premise is absent. If universal exact certification is also required, that is a stronger requirement than broad collection support; the remaining native premises must be solved, not hidden behind a “supported” badge.
