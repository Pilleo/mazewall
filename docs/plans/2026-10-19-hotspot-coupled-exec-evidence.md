# Coupled JVM exec and asynchronous fatal-cause qualification

## Decision

**The revised A-OBS candidate gate is qualified for the controlled observational
corpus. Production admission remains closed.** Ordinary/churn/unload captures
complete; actual same-PID JVM exec, target SIGKILL and native read denial remain
failed captures with their original causes. Passing a negative control does not
produce a successful Capture or certify graceful native retirement on that failure.

This follows source/custody commit1728e661 under the
[coupled exec contract](2026-10-19-hotspot-coupled-exec-contract.md) and the approved
[A3 observational scope](https://github.com/Pilleo/mazewall/blob/a28cbe293289f2549858aa232d9e6ebd7c4756dd/docs/superpowers/specs/2026-10-18-kubescape-agentless-hotspot-design.md#a3-native-qualification-exit).
No operator admission decision, SDK release, storage/node-agent edit, cluster
operation, dependency, native layout or source-overlay pin change is included.

Evidence directory: `build/kubescape-dev/coupled-exec/`. Every capturing binary has
source/compiler/module/workspace receipts before compilation and an actual hash
before native execution. Native assets/executables stay read-only for controller
lifetimes; only each independently named output directory is writable.

## What changed

- The standalone `ObservedExecWorkload` wraps the unchanged400-call/four-worker
  baseline. A gated platform thread uses the pinned JDK25 FFM API to call libc
  execv and replace its own JVM with `/bin/sleep 4`, without an application agent,
  native library dependency or extra VM flag. Its private NativeEngine confines
  raw segments, uses3ADDRESS argv entries, C int32 and captured errno. Actual
  before/after process receipts preserve PID/birth/cgroup/mount and show the new
  executable; filenames or timing alone are not an exec proof.
- The shell-only matrix control is removed. All six cases now use the same
  supervised capture path. The canonical replacement path is resolved inside the
  independently associated container **before** the exec trigger, then compared
  exactly afterward. No same-basename fallback or self-derived expected identity.
- The child retains original typed exec/exit causes separately from JSONL in
  `collector-failure.json`. A qualification-only predicate exposes the existing
  private target-exit sentinel to this actual process-boundary caller; it is
  absent from shipped builds. Native pending ownership cannot become a summary.
- Intentional exit137 is classified as a passed negative control only after actual
  Docker exit-state, typed target-loss, failed SDK Finish and reaped collector
  checks. Its failed wrapper/cleanup receipts are retained unchanged. Mixed
  permission/timeouts/unknown causes and pending owners still reject. A negative
  control never gets a successful native Capture or policy eligibility.

## Root-cause correction, not a qualifier relaxation

The exact-path run still rejected exec: the actual process had changed, but the
capture error contained only cancellation and pending readout. An asynchronous
enrichment worker held its original exec cause until Finish, which that unsafe
pending-owner return must not call or block on.

The [registered cause-retention issue](https://github.com/Pilleo/mazewall/blob/a28cbe293289f2549858aa232d9e6ebd7c4756dd/docs/internals/backlog/resolved/issue-20261019-023327-138-retain-asynchronous-enrichment-causes-before-pendi.md)
adds a private constructor/error callback into the existing bounded fatal owner.
The public internal-package NewEnricher signature and its original one-shot cancel
contract stay intact. Lookup/recovery causes are reported before the shared
enrichment lock or terminal/recovery output; sink errors report when observed.
The existing fatal owner bounds storage and preserves first/EPERM/EACCES causes.
Pending return still emits no plausible summary or late error through an unjoined
writer. Joined cancellation cannot downgrade a permission denial to ordinary
unavailability.

## Observed RED/GREEN and retained attempts

- `fixture-red.log`: current compiler omitted the exec source and target-exec
  launched the ordinary main class. `fixture-green.log` fixes both; hashes for both
  maintained sources and actual compiled classes precede use.
- `controls-compile-red.log` and `transition-compile-red.log` explicitly label new
  helper compile REDs. Host controls exercise real failed subprocesses, typed
  cause requirements, missing readiness/reaping, forbidden summaries/accepted
  captures, PID/birth/cgroup/mount/container/image substitutions, missing Docker
  state and mixed permission/timeout cleanup errors. Message-only errors fail.
- The first actual exec case (`native/`) safely rejects an over-specific GNU path
  assumption: pinned Temurin uses `/usr/lib/cargo/bin/coreutils/sleep`. Its rejected
  report is retained as2213dfa6dc275ad796b2cf00055625c601f175307b8cb143dfb06844b381ef0a.
  The fix binds the independently resolved path before exec; it does not whitelist
  arbitrary basenames. New compile RED/GREEN controls cover the uutils path and
  reject a different/unknown/noncanonical replacement.
- The next exact-path run (`native-canonical/`) proves actual same-PID JVM exec but
  rejects missing typed cause. Its rejected report remains
  f7ed18431081f7dfaf7cffa88d6a0ad6e752e56ecb2dbc603098ef40b3a95014.
  Original completed useful counts across those two runs remain380/384/380 and
  376/380/384; availability variation is not erased or presented as a speed gain.
- `joined-permission-red.log` behaviorally reproduces EPERM being lost when joined
  with cancellation. `held-cause-compile-red.log` records the new private reporting
  boundary's compile RED. `asynchronous-fatal-green.log` passes both: original exec
  cause is visible while terminal output is held and Finish cannot complete;
  joined EPERM/EACCES remains fatal with the legacy cancel callback called once.
- Full canonical/experimental default host/race suites pass; canonical tagged
  capture suite and candidate/driver host controls pass. The native qualifier and
  its threshold/cause/summary refusals are not weakened to obtain the final result.

## Actual qualified candidate

Actual static matrix/collector-child binary:
`c65b1c36cea99a1bb25b8c2795b3b7ce8df26eb69bb6a60d73a6a7feda38c7be`.
It is compiled by host Go1.26.8, CGO disabled; the locked Go1.26.0 execution image
does not retroactively identify its compiler. Pinned javac25.0.4.1 compiles the
maintained sources before targets start. Actual runtime input arguments are empty;
compiler/tiered/G1 remain enabled. FFM's native-access warning is retained rather
than hidden by an additional application flag.

Final report `native-fatal-cause/result/report.json`:
`90dfc28166a1d936740b55d2ceefe69cbd81d5d00e2d37dad682daad2e2e9343`.

|Case|Marked raw/terminal|Useful application prefixes|Outcome|
|---|---:|---:|---|
|Ordinary tiered/G1|400/400|380|Completed SDK capture and native drain|
|Compilation/code-cache churn|400/400|380|Completed SDK capture and native drain|
|Class load/unload|400/400|381|Completed SDK capture and native drain|
|Actual same-PID JVM exec|400/400|No completed capture|Original typed exec poison; child exit1/reaped|
|Target SIGKILL|400/400|No completed capture|Original typed target loss and actual exit137; child exit1/reaped|
|Native read denial|No marked work released|No completed capture|Original actual EPERM/EACCES; separately checked remote child retirement|

All useful marked stacks match their independent token route and worker TID in
leaf-to-root order. The positive cases satisfy the unchanged400/360/zero-wrong
gate, true physical event/definition/auxiliary finality and selected accounting.
Negative cases produce no accepted SDK capture or successful summary. All actual
collector children reap before target cleanup. Permission control has a separate
actual `permission-collector/cleanup.json` with confirmed absence and no failures.
The target137 receipt stays visible; no other cleanup failure is treated as
expected. Final controller absence and empty qualification-label query are retained.

Definitions/enrichment/wire/retention remain unproved global lanes. No code/GC/
metadata reclamation lease, virtual identity, precise BCI or whole-stack/loss/policy
guarantee is inferred from useful routes or from successful negative controls.

## Rebuilt helper and release boundary

The external native source fingerprint remains89b3599935df5206ff710292574e4480a09da535f8529b4b0462b8c29a7e0f50:
the correction is collector Go error routing, not a BPF/ABI/layout change.
Canonical and experimental source mirrors agree. Actual default helper code does
change, so the old0e401e helper is not relabeled as containing this fix.

Two new complete locked-Go1.26.0 candidate-workspace builds match every path,
mode, size and file byte in both10-file bundles:

|Artifact|SHA256 / identity|
|---|---|
|Rebuilt canonical helper, both builds|`5ff618a4e8bcf481f780db296bc7d4f52ffdbfaa4fdc9d1406b4279b7ebafbad`|
|Strict manifest|`522956029f879b2b0db0f46b7741fe90cf86398685a4f6a575923153d4944fcb`|
|Observed manifest|`5f630e4d6028988db0b60f74a6cc2e6abec2213aec52b38a26316c64d978804f`|
|Unchanged Gadget OCI|`sha256:4ec354d5ec6c38f60d89192fb0f90a3a25bb7974c240854199ed6934d41182b4`|

Matching/wrong-profile/pin full-byte controls pass against those actual bundles.
Both rebuilt local-candidate and actual GOWORK=off released-SDKv0.1.0 CLIs reject a
valid observed request before acquisition: exit2/empty stdout/operator-review
diagnostic (`admission-refusals.json`). No SDK version is republished or invented.

This is an A4 candidate-review receipt, not A4 admitted-CLI acceptance or A5 provider
handoff. Operator review must precede any admission change; format3 SDK publication
needs its separate approval/new immutable release.

## Final verification

- Full root `./gradlew build` passes in6m20s with Ubuntu OpenJDK25, both explicit
  rootful endpoint aliases, MW_HOTSPOT_NATIVE_ACCEPTANCE=true and the new verified
  coupled-exec/bundle-a/assets-strict (`full-build.log`):228tasks,31executed and
  197up-to-date. No assertion, hook, native budget or policy was bypassed.
- The rebuilt shared error path executes the strict public-native suite:
  **8tests,0skipped,0failures,0errors**,172.826seconds,
  timestamp2026-10-19T02:45:38.695Z. XML is retained in `strict-public-native/`.
- Both native-owner default Go/race suites pass. Tagged capture/candidate/driver
  checks and actual native controls pass; Go formatting and source mirror checks
  pass. Backlog and scoped-reference checks accompany issue archival.
- Scoped connected-code review checks all enrichment constructors/callers, lookup
  and recovery errors, fatal-owner lock ordering, exec/exit classification, child
  reaping and remote cleanup. It finds no additional blocker at the fixed
  four-worker/400-operation qualification load. This is not production scale or
  a metadata/code lifetime certification.
- SDK has exactly bundle/wire packages. Bundle race tests pass; wire JSON log
  records423test/subtest passes,0skips, package pass in106.012seconds. The initial
  combined command exceeded its external120second limit; it is not called a
  successful full invocation. The retained longer wire check completes normally.
- Historical rejected matrices/assets remain unchanged. Native global completeness,
  metadata/code/mount leases, logical-thread identity and policy eligibility remain
  unproved/false. The two repaired issues archive with these receipts, but A4
  admitted-CLI and A5 release/handoff remain gated on separate operator decisions.
