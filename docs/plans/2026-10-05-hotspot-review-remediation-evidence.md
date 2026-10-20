# HotSpot review remediation evidence

Execution starts at `8b1f8391` in `fix/hotspot-review-remediation`. The approved
[plan](../superpowers/plans/2026-10-05-hotspot-review-remediation.md) is adapted to
the Go migration; the [original review](2026-10-05-hotspot-implementation-review.md)
remains historical evidence, not a description of retired Python tooling today.

## R1: Complete capture validation

- Baseline candidate/driver race tests passed12tests (`run-35bbca9ca640`).
- Current Go negative tests reproduced accepted schema99 and truncated/oversized/
  blank input (`run-b370bb16cd6c`,2failed).
- Explicit null/missing parent booleans also passed before the fix
  (`run-07c4f28f392f`); unsupported readiness schema/missing session/oversized input
  reproduced separately (`run-7326b9d24852`).
- Complete stream, process/session/occurrence pairing and counters now validate
  before oracle selection or carrier-only continuation. JSONL is bounded and
  newline-terminated; virtual verification requires independent identity.json
  beside its oracle. The runner reads target birth while alive, before capture.
- Focused `go test -race ./internal/candidate ./internal/driver` passed17tests
  (`run-12008fe47c6e`). These are host parsing/identity checks, not live lifetime proof.

R1 committed as `05c0503b`. Its full commit gate passes on installed Ubuntu
OpenJDK25 (`run-912f155266ca`). GraalVM hit the independently retained executable
mapping denial tracked by [the existing enforcer issue](../internals/backlog/issue-20261015-120935-494-diagnose-executable-mapping-eperm-during-enforcer-.md);
no policy, assertion or hook was disabled.

## R2: Exact application method prefixes

- Red `run-f74f8841c849` reproduced useful1 for a different owner, malformed
  renderer display, leafCorrupt/routeACorrupt and acceptance of mixed manifest owners.
- The shared matcher now parses an exact renderer owner/method (including
  constructors), validates one manifest owner and compares the ordered prefix.
  Other-owner/native-only frames remain application_unavailable, not useful.
- Candidate/driver race checks pass26tests (`run-c2aa8f32188b`).
- Retained native packets exposed legitimate empty function names on unresolved
  native frames (381/415/355 in C1/C2/G1). The boundary now distinguishes a present
  empty string from missing/null names, preserving native-only outcomes. Its red
  regression is `run-c1d299054b37`; all27candidate/driver race tests pass
  (`run-ed22107aac8b`).
- Hardened CLI revalidation on the prepared pinned Go graph preserves all40marked
  tokens: C1 useful28/unavailable12 (`run-1d8dc96bf25c`), C2 useful31/unavailable9
  (`run-424b43c207ba`), interpreter/G1 useful26/unavailable14 (`run-72f4d7b472f6`).
  All lifetime flags remain false. Original captures/manifests were read-only.

## R3: Checked remote and local ownership

- Current Go shared cleanup still accepted rm success without querying remote
  state; red `run-ed68353ada28` reproduced both still-present and denied-query cases.
  The migrated runner already joined rm errors, so that older Python defect was
  not reintroduced or treated as a new Go finding.
- Named/session-labelled create requests retain64hex receipts or pending ambiguous
  acquisitions. Recovery matches both exact name and label; an empty query after
  failed create remains unknown. Actual command handles are waited once and retained
  on timeout; every wrapper gets a cleanup attempt even after remote failure.
- Shared retirement now confirms remote absence, retaining rm/query/permission/
  deadline causes even when a later query proves absence. cleanup.json records
  receipts, wrapper PIDs, pending owners and failures; result publication follows
  successful retirement. Original primary errors remain in the Go error chain.
- Candidate/driver/connected benchmark race checks pass59tests (`run-8ba5f94d87df`).
  Explicit rootful target/helper create/start/wait/remove/absence test executes and
  passes (`run-18b29b45319f`), rather than skipping a missing endpoint.
- A failed wait without actual ProcessState also retains its wrapper handle
  (red `run-2475cadee9b5`); completion is confirmed independently of wait's return
  value. Final host/race checks pass61tests (`run-7dc588689a1b`), and the updated
  real lifecycle/receipt-report test passes (`run-0332488dd308`).

## Current native/live revalidation

The migrated checkout already accepts patch
`618c3d67d71e86dfb0befb13f73498402af6110f1f2fceb3784c53785b0da00c`.
The historical e908 bundle correctly rejects (`run-69aaf685f355`). Current canonical
build (`run-9495f27a1806`) and full graph packing/verification (`run-8388858f73f3`)
produce helper SHA `afb3fd5a0a994a06ed70e42689abe586b926b21fe2967db2c423c73a60c3907e`.
The pinned upstream revisions/images and gadget3ae6007b remain unchanged; no pin
or verification rule was edited. All artifact writes are inside the isolated worktree.

The real six-case harness passes (`run-2d32d65cfee1`), retaining artifacts under
`profiler/build/reports/hotspot-review-remediation-qualification/`:

|Case|Marked|Useful application prefix|Application unavailable|Carriers|Logical observed|
|---|---:|---:|---:|---:|---:|
|C1/Serial|40|29|11|—|—|
|C2/Serial|40|30|10|—|—|
|Interpreter/G1|40|31|9|—|—|
|Virtual parallelism1|256|—|—|1|0|
|Virtual parallelism2|256|—|—|2|0|
|Virtual parallelism4|256|—|—|4|0|

All target/collector/VM.flags exits are0. Each case and compiler cleanup report
has resources_retired=true, no pending owners/wrappers/acquisitions and no failures.
Virtual cases now retain independent PID/birth/full-root identity.json and validate
the complete carrier-only stream. Independent rootful label query confirms no
candidate containers remain (`run-2e5bc7f858b6`). Useful counts differ from the old
run and are recorded as observed; no lifetime, logical-parent or production proof
is inferred.

## R4: Per-CPU conservation

- Red `run-706a4240d8ad` reproduced five contradictory tuples becoming known,
  including impossible rows cancelling into valid aggregate totals.
- Aggregate now checks each CPU's Submitted≤Attempted and
  ReserveFailed=Attempted−Submitted after sticky-overflow refusal, before existing
  checked Add64 sums. Generation/cardinality and whole-source equations remain intact.
- Host/race checks pass6tests (`run-be6d78dd7298`), including MaxUint64 valid
  boundaries, mixed submit/reserve CPUs, sum overflow and the cancelling-negative.
- The actual tagged kernel test passes on the pinned Go image/rootful host-PID
  endpoint (`run-94ecaf8f3959`):512openat attempts,255submitted/received,
  257reserve failures/lost,12possible CPU slots; sticky native overflow rejects.
  This remains isolated selected-producer qualification, not the default Gadget
  owner binding or all-source completeness proof.

## Scoped connected-code review

Reviewed capture/file/manifest boundaries, both verifier callers, runner publication,
owned create/start/wait/retire and the shared helper's benchmark caller, plus native
Aggregate and its actual kernel consumer. Expected load is one explicit six-case
qualification invocation with bounded rows/frames and a small owned container set.
Codanna impact output was partial (index missing); direct caller inspection supplied
the connected-code evidence.

The scoped review caught empty openat pathnames being conflated with absent fields
(red `run-edcd231ffb1d`). Typed JSON presence now permits a present empty string,
while missing/null rejects; independently expected runtime token paths must remain
absolute (red `run-eccf5768b679`). New target context visibility is package-private.
The complete prepared experimental Go host/race suite passes158tests in18packages
(`run-6442fdc7539a`). Production HotSpot readout/wire/compatibility tests and both
ABI gates pass (`run-6cc465599fa5`); formatting/static analysis use the current
Gradle build because the old lint script was retired by the baseline migration.

The unchanged benchmark caller still loses ambiguous acquisition requests when
Docker returns an error; its known-ID cleanup benefits from the shared fix, but
acquisition needed the separate [registered follow-up](../internals/backlog/resolved/issue-20261016-225418-065-retain-ambiguous-benchmark-container-acquisition-o.md),
subsequently resolved by the benchmark ownership continuation below.
The existing GraalVM/late-executable-mapping issue also remains open. No additional
blocking defect remains within the four-finding remediation scope.

## Final verification (2026-10-17)

Final executable revision: `7a9f454d` (R1 `05c0503b`, R2 `de7f1807`, R3
`9389cd44`, R4 `34bfe418`, followed by the scoped pathname correction).

- Complete experimental Go host/race suite:158tests/18packages, all pass
  (`run-6442fdc7539a`). Canonical accounting:6host/race tests pass.
- Actual named-container lifecycle and actual512openat pressure/sticky-overflow
  checks execute and pass; they are separate from synthetic fault controls.
- Final parser CLI revalidation of all six current native captures passes with the
  table's exact counts (`run-5825d71b9970`), including independent virtual identity.
- Production readout/wire/compatibility tests and Kotlin/legacy ABI gates pass
  (`run-6cc465599fa5`).
- Full `./gradlew build` passes in7m34s (`run-1d6283f8b15d`), using installed Ubuntu
  OpenJDK25, both rootful endpoint aliases, MW_HOTSPOT_NATIVE_ACCEPTANCE=true and
  the verified current canonical asset directory. Formatting, static analysis,
  tests and configured coverage gates pass. No hook, assertion or policy was bypassed.
- Actual public acceptance XML records7tests,0skipped,0failures,0errors under
  `profiler/build/test-results/integrationTest/TEST-io.mazewall.profiler.hotspot.HotSpotPublicIntegrationTest.xml`.
  This includes real permission/startup/target-exit, retention and dense diagnostic
  outcomes; it does not turn a retained incomplete dense readout into success.
- The four review issues are archived with evidence. ADK backlog checks pass
  (`run-93d644366400`); final local link checks pass239references
  (`run-366102353513`). Canonical recursive metadata confirms137open/1deferred and
  702resolved; post-archive Gradle validateBacklogIndex passes
  (`run-c5814d3f51c6`). Whitespace checks pass.

At this original checkpoint the isolated branch/worktree was retained for
operator-controlled review/integration, with benchmark-acquisition and
executable-mapping follow-ups open; the subsequent continuation is recorded below.
Production lifetime/virtual-parent qualification and whole-collection policy
completeness remain false; wider providers/runtime/loss integration retain their
separate approval and proof boundaries.

## Subsequent integration/continuation

Operator-requested local integration fast-forwards fast-master to9e14bf8e with an
identical tested tree. The [benchmark ownership continuation](2026-10-17-hotspot-benchmark-ownership-evidence.md)
then shares the owner implementation through internal/driver and resolves the
separate acquisition gap with actual lifecycle/failure checks. Its full build and
seven public native cases pass. Independent full-benchmark epoch-zero readiness
refusal remains tracked; no original rejection or promotion boundary is weakened.
