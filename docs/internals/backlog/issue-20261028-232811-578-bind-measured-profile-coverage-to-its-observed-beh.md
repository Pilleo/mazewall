---
title: "Bind measured profile coverage to its observed behavior"
severity: "MEDIUM"
status: "open"
priority: high
dependencies:
  - "issue-20261028-232809-819-reject-contradictory-completeness-in-imported-prof"
component: "core"
target_modules:
  - ":profiler"
target_files:
  - "profiler/src/main/kotlin/io/mazewall/profiler/api/ProfilingResult.kt"
  - "profiler/src/main/kotlin/io/mazewall/profiler/api/ProfilingCoverage.kt"
  - "profiler/src/main/kotlin/io/mazewall/profiler/api/ProfileDocument.kt"
   - "profiler/src/main/kotlin/io/mazewall/profiler/api/BillOfBehavior.kt"
   - "profiler/src/main/kotlin/io/mazewall/profiler/api/ProfilerSession.kt"
   - "profiler/src/main/kotlin/io/mazewall/profiler/api/Profiler.kt"
   - "profiler/src/test/kotlin/io/mazewall/profiler/ProfileDocumentTest.kt"
   - "profiler/src/test/kotlin/io/mazewall/profiler/ProfilingCoverageTest.kt"
   - "profiler/src/test/kotlin/io/mazewall/profiler/ProfilerExecutorSnapshotTest.kt"
target_symbols:
  - "ProfilingResult"
open_questions: false
---

# 🟡 [Severity: MEDIUM]: Bind measured profile coverage to its observed behavior

**Context:**
Host-only reproduction: copy a complete USER_NOTIF result while replacing behavior with a copy containing an unobserved filesystem path. Coverage remains the same complete object and toPolicy generates a filesystem grant. Public constructors and BillOfBehavior conversion also accept behavior and coverage independently. Production collector misuse was not established; this is provenance and future-caller hardening.

**Needed:**
1. Make conversion consume coverage bound to the observed snapshot; behavior edits must lose measured completeness while value-only changes may retain it. Cover constructors, copy and direct behavior conversion consistently, preserve HotSpot structured-owner restrictions, and require approval for breaking API changes. Add regressions for edited/merged behavior, ordinary measured results and value-only transformations.

**Design-review follow-up (2026-11-03, finding 6):**
- `ProfilingResult.kt:71-90` copies behavior independently of coverage and passes both to conversion. `BillOfBehavior.kt:102-132` checks the supplied coverage before compiling the supplied paths, without binding the two snapshots.
- Own behavior and measured coverage in one immutable aggregate with controlled transformations. Value-only changes may preserve evidence; behavior edits/merges must lose measured completeness unless fresh evidence validates the new snapshot.
- Coordinate with the [coverage consistency issue](resolved/issue-20261028-232809-819-reject-contradictory-completeness-in-imported-prof.md): internally consistent coverage and coverage belonging to this behavior are separate invariants. Preserve HotSpot incompleteness and unsigned-document semantics.

---
## Repository-review revalidation — 2026-11-04

[EVID-03](../reviews/2026-11-04-repository-review-findings.md#evid-03) freshly reproduces
an unobserved path retaining the original complete coverage. Cover constructor/copy/merge,
direct BoB conversion and export path normalization, not only one result facade. Preserve
valid value-only transforms, HotSpot structured ownership and approved Java/Kotlin APIs.
Consistency819 is a real prerequisite; unsigned operator policy authoring remains separate.

## Collector-snapshot follow-up — 2026-11-05

[Recursive review C-02](../reviews/2026-11-05-state-complexity-review.md)
adds producer-side evidence to this owner. `ProfilerSession.result()` derives
observations at line 160, reads environment at 175, rereads logs for diagnostics
at 177 and compiles behavior from the mutable log owner at 157/179.
`ProfilerExecutorWrapper.recentLogs` aliases that owner at `Profiler.kt:99`.
Independent COW traversals can describe different histories even after collector
closure. This is a source-confirmed schedule, not a new runtime reproduction.

- Capture one immutable **producer-owned** evidence snapshot and derive
  observations, behavior, diagnostic facts and coverage from it in one pure
  assembler. Acquire environment outside the assembler. Preserve actual
  retirement/cleanup prerequisites; a snapshot is not a physical-end proof.
- Compatibility log edits must not introduce or erase measured behavior while
  retaining completeness. Detach those mutations from measured history or
  conservatively invalidate completeness without breaking approved public APIs.
  One local `toList()` alone does not establish producer ownership.
- Add a deterministic host test whose injected environment supplier replaces
  resolved history A with B between observation inference and compilation. A
  result cannot contain complete A observations with B grants. Also edit history
  before snapshot creation to distinguish ownership from traversal coherence.
- Retain post-snapshot immutability, ordinary complete collection/value-only
  transformations and incomplete-active/failed-installation controls. Preserve
  public Java/Kotlin signatures unless a migration is explicitly approved.
- No kernel execution is needed for the assembly regression. Real producer and
  retirement qualification, focused owner tests and the implementation completion
  gate remain separate. Existing consistency819 dependency is unchanged.

---
<!-- id: issue-20261028-232811-578-bind-measured-profile-coverage-to-its-observed-beh  file: issue-20261028-232811-578-bind-measured-profile-coverage-to-its-observed-beh.md -->
