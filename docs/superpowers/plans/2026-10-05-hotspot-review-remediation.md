---
document_type: execution_plan
base_revision: 8b1f8391df5321e606fef9636f45e68edb98b4e4
status: completed
---

# HotSpot Review Remediation Implementation Plan

Operator approved inline execution in the isolated
`fix/hotspot-review-remediation` worktree. This adapts the approved October 5
plan to the Go tooling introduced by `696ba718`; the retired Python tools are
not restored. The historical review baseline remains `6e0b34d6`.

**Goal:** Fix four reproduced qualification integrity/ownership defects and
revalidate evidence before extending production providers, runtimes or loss claims.

**Architecture:** Share one bounded schema1/session/process/pair validator and
exact method-name extractor. Acquire actual container receipts, confirm remote
absence and retain pending owners and original errors. Validate conservation per
CPU before aggregation. Reuse the Go candidate/driver packages and standard library.

**Spec:** [implementation review](../../plans/2026-10-05-hotspot-implementation-review.md)
(R1–R4) and [capability design](../../plans/2026-10-05-hotspot-performance-capability-design.md).

## Constraints

- Reject missing/unsupported schema/session/identity; qualification remains schema1.
- Production remains Linux-amd64/pinned Temurin25.0.4.1+1-LTS,
  `-Xint -XX:+UseSerialGC`, synchronous selected openat.
- Readiness ≤10s/session ≤60s/stop ≤2 remaining seconds/enrichment deadline1s;
  native budgets and Kotlin4096/33,554,432byte retention stay unchanged.
- No dependencies, breaking public APIs, new production provider/runtime whitelist
  or whole-collection completeness claims. Policy coverage remains incomplete.
- Preserve EPERM/EACCES and original errors. A joined Docker client is not remote
  retirement. Captured session/occurrence/PID birth/BOOTTIME is immutable.
- No basename-only, nearest-TID or later-stack identity substitution.
- Missing/contradictory/overflowing counters never become known zero values.
- Preserve user work; scoped local commits, no default-branch push or token handling.

## Current files

|File|Responsibility|
|---|---|
|`experiments/hotspot-agentless/internal/candidate/capture.go`|Shared bounded capture validation/index|
|`experiments/hotspot-agentless/internal/candidate/files.go`|Strict JSONL/oracle and independent identity boundary|
|`experiments/hotspot-agentless/internal/candidate/verify.go`|Runtime/virtual selection and exact application prefixes|
|`experiments/hotspot-agentless/internal/candidate/*_test.go`|Malformed evidence, method and ownership regressions|
|`experiments/hotspot-agentless/internal/candidate/runner.go`|Independent context, owned acquisition/retirement, result publication|
|`experiments/hotspot-agentless/internal/driver/process.go`|Existing command/Docker primitives; preserve other callers|
|`profiler/hotspot-agentless/internal/accounting/conservation.go` and test|Per-CPU equations before aggregation|
|`docs/plans/2026-10-05-hotspot-review-remediation-evidence.md`|Observed red/green checks and corrected evidence|

Tasks1/2 share validation and execute serially. Task3 precedes any live rerun.
Task5 depends on all four. Keep each fix independently reviewable.

## Task1: Complete evidence identity and pairing (R1)

- [x] Reproduce current Go acceptance of schema99 and truncated/blank JSONL.
- [x] Add complete valid fixtures, including valid event-before-attachment ordering.
- [x] Validate all rows before token filtering or carrier-only continuation:
  schema1, one nonempty session≤128bytes, session-prefixed occurrence IDs≤256bytes,
  matching nested IDs, independently expected PID/birth, positive host TID,
  supported BOOTTIME/event kind and explicit uint64 capture time (zero allowed).
- [x] Require one attachment, final summary, exact event/terminal pairing and
  counters, explicit true terminal accounting, typed payload/frame arrays≤4096.
  Unknown kinds, duplicate sides, records after summary and unavailable frames reject.
- [x] Bound newline-terminated UTF8 JSONL to1,048,576bytes/line and65,536rows;
  reject blank/truncated input and invalid numeric/boolean types.
- [x] Runtime uses manifest PID/birth/full paths. Virtual verification requires
  independent `identity.json` beside its oracle with PID/birth/fixture_root;
  duplicate oracle tokens and same-basename/different-directory events reject.
- [x] Validate native logical ID/mount uint64s and explicit object/parent booleans;
  mounted candidates require candidate_native source. Oracle substitution rejects.
- [x] Record PID/birth from live `/proc/PID/stat` in the runner before collection;
  reuse `preflight.ParseStartTicks`, never derive context from captured events.
- [x] Run focused candidate/driver race tests. Keep qualification flags false.
- [x] Revalidate actual packets and complete issue evidence under Task5.

## Task2: Exact application method names (R2)

- [x] Reproduce false usefulness for WrongFixture.leafCorrupt,
  Fixture.leafCorrupt and routeACorrupt; add reordered/malformed/native-only controls.
- [x] Parse exact ASCII fixture owner/method from renderer display
  `void Fixture.leaf(java.nio.file.Path)`, including constructor names.
  Require a single exact manifest owner and compare ordered application prefixes;
  never substring-match method/owner names.
- [x] Other owner/native-only stays application_unavailable; present wrong or
  reordered same-owner application prefix rejects. Preserve application_route_prefix
  scope and false lifetime/production qualification.
- [x] Run candidate/driver race tests, then retained C1/C2/G1 packet revalidation.

## Task3: Failure-preserving remote ownership (R3)

The migrated Go runner already joins removal errors. Check its remaining gaps
instead of reimplementing the retired Python cleanup.

- [x] Fault-test failed/timed-out removal/query, permission failure, ambiguous
  create, still-listed receipts, wrapper join/kill failure and a primary failure.
- [x] Acquire through named/session-labelled `docker create` and validate64hex
  receipts before start. Retain ambiguous acquisition requests; recover only by
  matching name plus session label, never remove an unrelated name.
- [x] Within bounded retirement, attempt every acquired remote owner and every
  local wrapper, even after another cleanup operation fails. Successful retirement
  needs a successful absence query, not only rm or local process exit.
- [x] Retain pending remote receipts/acquisitions and actual wrapper handles in
  memory; cleanup.json records IDs/PIDs/status and error details. Preserve original
  error objects with Go error chaining, including permission/cancellation causes.
  Later confirmed absence can clear pending ownership but cannot erase an rm error.
- [x] Publish case result only after retirement. Cleanup failure cannot mask the
  primary error or produce success. Preserve native/public task budgets.
- [x] Run deterministic host faults and explicit rootful named-container lifecycle,
  inspect actual absence and all exits before live candidate reruns.

## Task4: Per-CPU contradiction refusal (R4)

- [x] Reproduce contradictory tuples `(1,2,0)` and `(1,0,0)` cancelling into
  valid totals. Cover individual contradictions, MaxUint64, valid mixed CPU counts,
  cardinality/generation/sticky overflow and sum overflow.
- [x] After overflow refusal, reject `Submitted > Attempted` or
  `ReserveFailed != Attempted-Submitted` on each CPU before checked Add64 sums.
  Preserve the existing Aggregate API and whole-source Evaluate equations.
- [x] Run host/race tests in `profiler/hotspot-agentless` and actual
  `TestKernelOpenatCountersPressureAndOverflow` with mw_kernel tag, pinned Go image,
  privileged/rootful Docker and host PID namespace. Require512attempts, pressure
  and overflow rejection; no unconditional capability skip or production loss claim.

## Task5: Evidence, review and final gates

- [x] Record observed red/green outputs and current revisions for all four findings.
- [x] Revalidate retained runtime manifests/captures with exact method matching.
  Old virtual reports lack independent birth/path context: preserve as historical,
  rerun with new identity.json instead of deriving context from those events.
- [x] Run affected live harness after R3. Preserve cold native-only/unavailable
  and readiness failures; inspect summary pairing, all exits and remote absence.
  Missing captured lifetime/mount proofs remain unqualified.
- [x] Run focused checks, one connected-code review and final quality gates:

```bash
# experiments/hotspot-agentless
go test -race ./internal/candidate ./internal/driver
# profiler/hotspot-agentless
go test -race ./internal/accounting
# repository root
./gradlew :profiler:test --tests '*HotSpotRawJsonTest' --tests '*HotSpotWireReaderTest' \
  --tests '*HotSpotResultCompatibilityTest' :profiler:checkKotlinAbi :profiler:checkLegacyAbi
./scripts/adkw check-backlog
git diff --check
./gradlew build
```

Current Gradle build owns formatting/static-analysis gates; the old lint shell
entrypoint was retired by the baseline tooling consolidation.

- [x] Archive each review issue only after its negative regression and affected
  positive/native path pass. Update current backlog counts, preserve unrelated rows.
- [x] Prepare corrected evidence/plan; retain branch/worktree pending operator choice.

Completion evidence: [remediation record](../../plans/2026-10-05-hotspot-review-remediation-evidence.md).
Full build passes with native acceptance enabled: seven public tests, zero skips,
failures or errors. Canonical active index is138issues (137open/1deferred), with
702resolved; the separate benchmark and existing executable-mapping issues stay open.

## Completion and next boundary

All four defects must reject unsafe evidence or retain actual unresolved owners;
positive paths pass and old evidence is revalidated or explicitly provisional.
This does not authorize production AF_ALG activation, compiled/mount lifetime
leases, default Gadget-owner/all-source counter binding, whitelist changes,
larger budgets or a600operation guarantee. Those retain their separate plans.
