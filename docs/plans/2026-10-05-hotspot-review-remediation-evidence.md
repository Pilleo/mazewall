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

R4 and final gates remain in progress.
Production qualification and whole-collection policy completeness remain false.
