# Benchmark acquisition ownership continuation

## Scope and integration

Operator requested committing/merging the completed HotSpot remediation and
continuing inline. `fast-master` fast-forwards from8b1f8391 to9e14bf8e
(`run-6de82fa26652`); exact tree comparison passes (`run-30ef6948811a`). The
already-passing source/native quality evidence is unchanged by that merge.

The registered benchmark follow-up continues on `fix/hotspot-benchmark-ownership`
in the existing operator-authorized worktree. Production profiles, assets/pins,
wire contracts, attribution/retention limits and loss-completeness claims stay
unchanged. No dependency or public JVM API is added.

## Root cause and smallest shared fix

Target `docker run -d` and collector `create` errors discarded possible remote
receipts; requests had no name/session recovery identity. Cleanup only knew the
remaining strings. The runner also wrote run-report.json before deferred cleanup
could fail, and cleared its attached client before a bounded wait completed.

The existing candidate owner implementation moves to `internal/driver/ownership.go`
for both actual consumers. Only construction/create/start/wait/finish operations
needed across the Go packages are exported; receipts, cleanup state, kill/join
mechanics and mutable handles remain private. No second ownership state machine
or configurable fallback is introduced.

- Both benchmark containers now use named/session-labelled create receipts.
  Target start follows receipt acquisition; unknown outcomes retain the qualified
  request and recover only the exact name plus session label.
  Shared create20s plus detached start10s preserve the old30s target acquisition
  bound; collector create also stays within its previous bound.
- One owner keeps the actual attached command through wait/kill/join. Start refuses
  IDs that were not acquired by that owner.
- Bounded retirement checks actual remote absence and actual local ProcessState,
  retaining primary/cleanup causes and unresolved handles. cleanup.json records
  receipts, pending acquisition/container/wrapper state and failures.
- Benchmark and candidate reports publish only after cleanup and file closes
  succeed. Failed validation/cleanup returns no successful benchmark result.
- Target measurement windows and workload parameters are unchanged. Readiness
  timing includes the explicit create/start lifecycle, as its documented startup
  semantics already require; no performance equivalence claim is inferred.

## Regression checks

- Red `run-d1be10b17f5d`: actual runner plus an external Docker transport fixture
  loses both recoverable and query-denied ambiguous acquisitions (no cleanup record).
  The fixed path retires the recoverable receipt or retains name/session metadata,
  preserves exit13 and publishes no successful run report.
- Red `run-914d1973cae0`: shared start accepted an unacquired ID. The guarded
  factory now rejects it before the external start.
- Full prepared Go host/race suite passes160tests/18packages
  (`run-6d96eddb5d51`). Existing remote query/removal/timeout, late-acquisition,
  permission/failed-wait and pending-handle fault cases move with the shared code.
- Actual pinned-JDK benchmark and shared-container lifecycle tests execute and
  pass (`run-6f7699aee830`). The benchmark test proves both positive publication
  after real absence and no publication when real removal succeeds but its
  transport reports permission failure. The latter retains the original cause
  despite confirmed retirement; actual pending IDs are independently queried.

## Actual benchmark observations

Pinned helper/fixture preparation succeeds (`run-94977207fe7c`). The three-mode
run (`run-cd82fdd7f2a2`, artifacts `experiments/hotspot-agentless/build/benchmark.00931104/`)
records32successful baseline operations and32event-mode operations, zero missing.
Their observed throughputs887.19/859.62ops/s are measurements, not thresholds or
an overhead conclusion.

Full mode conservatively rejects its existing5second epoch-checked preparation
gate. Native packets show31unavailable epoch0 terminals plus1missing reference.
An explicit existing bounded allocation warmup100000/preparation64 run
(`run-a67ba18ed87e`, `build/benchmark.795d8920/full-0/`) still records63epoch0
unavailable terminals plus1missing reference. Neither run publishes run-report.json;
both retain the original refusal, a checked exit137 cleanup failure, resources
retired=true and no pending acquisitions/containers/wrappers. Independent rootful
label query is empty (`run-e0db1f49aa95`). Native refusal is preserved, not relaxed
to satisfy a benchmark. The separate epoch-zero investigation is registered as
issue-20261016-233030-441.

## Connected-code review and final checks

Reviewed all shared factory callers (candidate compiler/cases and benchmark),
create/recovery identity, attached wait/kill/join, primary error chains, file-close
ordering and report publication. Mutable requests/handles stay owned by one serial
invocation, with at most target/collector receipts per run. Codanna impact evidence
was partial; direct callers supplied the connected-code scope. The candidate uses
the same naming/session filters and publication ordering after extraction; no
blocking defect remains within acquisition/retirement scope.

Six actual candidate cases pass with the shared implementation
(`run-b2b3bddcd6d7`, `profiler/build/reports/hotspot-shared-ownership-qualification/`).
C1/C2/interpreter-G1 retain40marked tokens with30/31/33useful prefixes; virtual
cases retain256tokens,1/2/4carriers,0logical observations. Every case records2actual
receipts,2joined wrappers, all exits0, resources_retired=true and no pending owners
or failures (`run-66de9ee796c8`). Production qualification stays false.

Full `./gradlew build` passes in6m9s (`run-cefbc867369f`) with installed Ubuntu
OpenJDK25 and native HotSpot acceptance enabled on the explicit rootful endpoint.
The actual public acceptance XML records7tests,0skips,0failures,0errors; formatting,
static analysis and configured coverage gates pass. Independent label query is
empty (`run-78366a340668`). The benchmark ownership issue is archived with evidence;
the independent epoch-zero diagnostic and existing executable-mapping issue stay
open. Final canonical totals are138active (137open/1deferred) and703resolved.

The final explicit20s-create/10s-start bound is rechecked on the complete source:
160Go host/race tests pass (`run-d8041ee4f96a`), both actual lifecycle/publication
tests pass (`run-a2a42d6b0036`), and the complete native-enabled build passes again
in6m18s (`run-d3cdb04a1a64`). The final public XML independently confirms7tests,
0skips/failures/errors (`run-ace04c16c04d`). Post-archive ADK/Gradle index checks
pass (`run-e4ce03c2fe79`, `run-4abba6f2b1ea`), with235local links verified and
recursive active/resolved totals checked (`run-0dc3cd4c4124`).

Scoped changes are prepared for the operator-requested local integration. No
remote publication or wider runtime/lifetime/completeness promotion is inferred.
