# Observed HotSpot owned-drain repair

Subsequent [coupled JVM exec qualification](2026-10-19-hotspot-coupled-exec-evidence.md)
completes the controlled candidate gate. The initial rejected repair matrix below
is retained unchanged; candidate qualification is not production admission.

Continuation from `be1ac8ca` in the existing
`feat/kubescape-agentless-hotspot` checkout. The operator requested continued
execution; no branch/worktree, publication, external repository or admission
change is included. The operator's Codanna settings edit is preserved.

## Scope and root cause

The [original rejected qualification](2026-10-18-hotspot-observed-profile-evidence.md)
is frozen. Its observed profiler deliberately has no safepoint epoch target.
Tracing the real `AfterReadout` path found four strict-only conditions: resolver
definition-drain dispatch, resolver auxiliary stop, auxiliary-scope creation in
the profiler, and resolver Close's auxiliary join. Removing only the first guard
would leave callbacks without an owned scope and fail the next stop boundary.

Both supported internal HotSpot modes now acquire the same real auxiliary scope
and use the existing definition owner. Missing tracer/consumer/reader/scope checks,
source quiescence, explicit perf flush, held-callback acknowledgment, fatal causes
and the remaining two-second native stop budget are unchanged. No epoch target,
metadata/code lease or successful stop observation is invented for observed mode.
Legacy unowned-profiler context behavior is unchanged.

The qualification harness now executes each normal capture in its own tagged
test-binary process, with separate stdin, JSONL stdout and stderr. A returned
capture error can retain native owners; the parent therefore waits for actual
process exit before releasing the target. Error exits/cancellation remain failures,
not native retirement certificates. An unconfirmed process exit retains the actual
command, target owners and logs through the returned pending-owner error; it does
not release/remove the target or report successful cleanup.

This process entry is absent from shipped/default builds. The parent's live
decoder still requires native summary/finality, EOF and successful process Wait.
Process death alone cannot turn a failed native drain into a completed capture.

## Regression evidence

Evidence directory: `build/kubescape-dev/observed-drain/` unless noted otherwise.
Compiler/source/workspace/module inputs precede compilation, binary hashes precede
native invocation. No prepared-source or module-cache file was hand-edited.

- Behavioral RED on the original473 source plus test-only Go overlays:
  `../task-4/observed-drain-red.log`. Strict and legacy controls pass; observed
  scope creation and initialized definition-owner dispatch fail for the expected
  reasons. The retained first real native drain failure is the native-path RED.
- `green-native-source.log` passes the scope/dispatch regressions. A held callback
  prevents Stop from certifying retirement; missing native reader/scope/consumer
  state still rejects, rather than becoming a plausible successful observation.
- `source-regressions.log`:69 top-level race-test passes across tracer,
  periodiccaller, resolver, symbolizer, kallsyms and asynchronous map owners. One
  existing symbolizer goroutine-leak test skips; that is not a native success claim.
- `process-test-compile-red.log` records missing new process-helper symbols,
  explicitly a compile RED, not a behavioral native failure. `process-test-green.log`
  and `tagged-host.log` pass actual subprocess tests: a simulated returned capture
  cannot release its target while the child remains alive; exit0, exit7 and
  cancellation all require process reaping and preserve the original Wait failure.
- `experimental-host.log` passes the complete experimental Go host/race suite.
  `canonical-host-resumed.log` passes canonical capture, identity, model, preflight,
  qualification and tooling checks. An earlier experimental command incorrectly
  selected nonexistent matrix/report directories; its setup failure is retained
  separately and superseded by the complete `./...` check, not relabeled a pass.
- Actual full-byte/profile/pin controls execute in `actual-bundle-boundary.log`.
  Focused Kotlin asset/protocol/result compatibility and both ABI gates pass.
- Final connected-code review follows scope creation, resolver Close, definition
  quiesce/flush/consumer handoff, fatal callbacks, child Wait and target release.
  The reviewed load is one bounded explicit qualification matrix. It also prevents
  dispatching the exec control after an unresolved collector owner. The final
  candidate/driver race run (`final-tagged-host.log`) includes real-bundle controls
  and frozen473 prefix replay; source/SDK/admission boundaries are unchanged.
  That pending-only dispatch guard was added after the native run; actual native
  case/report hashes remain frozen rather than claiming a rebuilt capturing binary.

## Source and reproducible artifacts

New source fingerprint:
`89b3599935df5206ff710292574e4480a09da535f8529b4b0462b8c29a7e0f50`.
The six changed native inputs are identical in canonical and experimental trees.
Both normal preparers apply zero-fuzz overlays and rebuild both architectures.
`artifact-parity.json` confirms generated support/types.go, amd64 and arm64 object
bytes equal the original473 artifacts in both owners. Source regression checks
and artifact parity precede updating all14 live pin consumers; the frozen473
native-prefix replay test deliberately keeps its historical provider fingerprint.

Two independent locked builds (`bundle-a`, `bundle-b`) produce identical strict
and observed bundles: all10 paths, sizes, modes and full file bytes match for each
profile. `bundle-reproducibility.json` records every member, not only the helper.

|Artifact|SHA256 / identity|
|---|---|
|Canonical candidate-workspace collector, both builds|`0e401e0062006ded251c04f55ade4e96c89a020c284b2ae2235cc5c22e6e56c2`|
|Strict manifest, both builds|`397d75afec0177a208c78070f58678d614fb476078ed81bfc72fa8aaca745e59`|
|Observed manifest, both builds|`0206db26c00d6fe8a794a8dbf520e7f5646443b7603fbcaf05bb798e831aa137`|
|Gadget OCI digest, both builds|`sha256:4ec354d5ec6c38f60d89192fb0f90a3a25bb7974c240854199ed6934d41182b4`|
|Actual static matrix/collector-child binary|`b3ac25679b41af4deb62da1a3f068fd42f8a1cfc4c09b1df21edb0e595533030`|
|Actual new rejected matrix report|`83b479bcb8b6cbefd8d45e44269420ed4e17c4d71448ff69b33f8c18ac7fc669`|

The helper compiler is locked Go1.26.0. The static matrix/child binary was compiled
by host Go1.26.8 with CGO disabled; executing it in the locked Go image does not
change its compiler. These builds explicitly select the local candidate SDK with
one workspace per native owner. Immutable published SDKv0.1.0, dependency pins,
upstream revisions and images are unchanged; no format3 release is claimed.

## Actual new native outcome

One new complete matrix follows the ownership repair. Its assets and executable
are read-only for the entire controller lifetime; only its separate output subtree
is writable. Actual Docker29.9.1, privileged host PID/cgroup namespaces, host/sys
visibility, UID/GID65534 targets and the pinned Temurin25 image match the prior
controlled qualification. No agent, forced interpreter/GC, pacing or buffer-budget
change is supplied to ordinary workloads.

|Case|Marked raw/terminal|Useful routes|Actual outcome|
|---|---:|---:|---|
|Ordinary tiered/G1|400/400|384|Completed SDK capture and native retirement|
|Compilation/code-cache churn|400/400|380|Completed SDK capture and native retirement|
|Class loading/unloading|400/400|376|Completed SDK capture and native retirement|
|Target exit|400/400|No completed capture|Rejected; original target wrapper137 retained|
|Permission denial|No marked work released|Not applicable|Original real native permission rejection retained|
|Exec control|Not a JVM capture|Not applicable|Real shell exec poisons; JVM/full-readout coupling still unqualified|

The three completed cases have zero wrong application routes, all four real
definition stop flags, physical event resources_retired and complete selected
accounting. Each collector child is reaped with exit0 before target release.
The failed target-exit collector is also actually reaped (exit1), without replacing
its absent successful native summary. All actual container cleanup receipts have
no pending resources; intentional target137 remains a recorded cleanup failure.
Controller absence and the empty qualification-label query are independently
recorded in `native-summary.json` and `native-with-cli/controller-retirement.json`.
Container/process retirement is distinct from native graceful retirement.

The initial controller setup attempt lacked the Docker executable and failed
before creating a JVM. Its log/unknown-acquisition receipt remains in `native/`.
The corrected explicit command restores the historical read-only Docker-client
mount and verifies client/server versions before the new native attempt. This is
not a retry of a completed runtime case until green.

**Overall A-OBS remains rejected.** The new report is not rewritten to hide the
target-exit and JVM/exec gaps. Definition/enrichment/wire/retention global lanes,
metadata/code reclamation and virtual-thread ownership remain unproved; every
observed row is policy-ineligible. The separate permission-control Docker process
uses a distinct checked remote owner in the subsequent correction below; broad
failure-path promotion is not established by the local capture-child tests alone.

## Remaining acceptance boundary

The [drain/lifetime issue](../internals/backlog/resolved/issue-20261018-212457-115-observed-hotspot-capture-rejects-native-definition.md)
records this source fix and the subsequent qualification. At this initial checkpoint,
actual JVM/full-readout exec and revised native permission-control custody were
still pending; the later coupled-exec record completes them without erasing the
original rejected outcomes. Operator admission and publication remain separate.
No admission source, SDK tag, provider handoff or Kubescape deployment is changed.
The actual newly built canonical CLI still refuses a valid schema3 request before
acquisition: exit2, empty stdout and the A-OBS/operator-review diagnostic
(`cli-refusal.json`).
The separately compiled `GOWORK=off -modfile=build/experiment.go.mod` CLI also uses
the actual unchanged SDKv0.1.0 without a workspace replacement and refuses3
(`released-sdk-cli-refusal.json`). That separate CLI was compiled by host Go1.26.8;
it is compatibility evidence, not an additional locked release or SDK publication.

## Root verification checkpoint

The first `./gradlew build` hit the external600second tool timeout without a failed
task marker. Its original log remains `full-build.log`. Before cancellation it
actually executed the strict public-native suite:8tests,0skipped,0failures,0errors,
246.565seconds, timestamp2026-10-19T01:28:20.709Z. Its XML is frozen under
`quality-attempt-1/`; a partial overall build is not called successful.

The same full gate is resumed with a longer external tool timeout, unchanged
Ubuntu OpenJDK25, both rootful endpoint aliases, native acceptance enabled and
the new verified `bundle-a/assets-strict`. Native readiness/session/stop/enrichment
budgets are unchanged. The resumed full build passes in8m8s, with228actionable tasks
(33executed/195up-to-date), recorded in `full-build-resumed.log`. The previously
executed eight public-native cases remain valid for the unchanged native graph;
cached task reuse is not represented as eight new live captures.

## Permission-control custody continuation

After the initial native run, three host regressions reproduced the old remote
helper returning before confirmed absence, losing cleanup permission causes and
leaving retirement unconfirmed after a failed Docker client. They fail in
`permission-custody-red.log` and pass in `permission-custody-green.log`.

The permission helper now has a separate `ContainerOwners` scope. It completes
checked removal/absence queries and writes `permission-collector/cleanup.json`
before returning to target cleanup. The private cleanup error exposes its actual
pending-owner state to the candidate caller; this does not expose mutable receipts
or change a public MazeWall API. Unconfirmed remote retirement retains the JVM
target and actual owner/error objects and stops the matrix. Confirmed absence does
not erase the original permission/client failure. Failed native permission
assertions still reject; they are not downgraded to successful enrichment.

These changes do not alter the native source fingerprint or the three previously
completed cases. Their native permission control will be executed with the next
revised qualification; this paragraph is host fault evidence, not a claim that
the old capturing binary contained the later remote-owner correction.
