# Tier E testing configuration and execution ownership

**Status (2026-09-26): Active execution is Kotlin-owned; optional node-noise
default-path qualification and Podman parity remain open.**

The operator chose a Tier E-first migration: one typed, versioned JSON
configuration and Kotlin-owned experiment and acceptance decisions. The
historical Python/Node implementations and immutable measurement archives
remain available for explicit reproduction. This does not change hybrid's
opt-in experimental status, `policy_eligibility=false`, or the operator's
`REJECT_DEFAULT_PROMOTION` decision.

## Configuration contract

Profiles live under `config/testing/tier-e/`. The initial v1 schema has
container (engine, URI, image), fixture (coverage, operation count, emission,
recovery), and measurement (fork count, two CPU selections) sections. A
profiler-local Kotlin loader rejects unknown/missing keys, invalid schema
versions, unsupported modes, invalid operation counts, and conflicting
aliases for the selected engine. A sole `CONTAINER_HOST` selects Podman,
while unrelated `DOCKER_HOST` is ignored for explicitly selected Podman;
conflicting aliases for the same engine are rejected. Explicit overrides
supersede a validated legacy environment adapter, which supersedes profile
defaults. The `:profiler:tierEResolveConfig` Gradle task and the developer
CLI `config` command use that same resolver. The short
rootful fixture writes the resolved non-secret JSON to its artifact directory.
Generic container endpoint discovery in root `sharedTest` no longer reads
`TIER_E_*` names; the profiler-local condition loads the selected profile
before passing an explicit endpoint to generic probing. Gradle forwards
the operator's endpoint for each JUnit worker invocation.

The short fixture's controller and both `run -d` requests coordinate through
one per-run flock. Kotlin closes a persistent admission state while holding
that lock before reconciling container names. A child queued behind the lock
must observe `closed` and refuse creation. A mid-wait interruption is restored
after independently attempting sentinel cleanup. An unresolved controller
retains evidence and only stops currently visible containers.

## Migration sequence and qualification

- Short rootful JUnit fixture consumes the smoke JSON profile and exports its
  resolved configuration. Its scenario controller and declared parameters are
  now Kotlin-owned; historical shell replay remains explicit.
- The split fixture's inline Node binary negative-envelope emitter and
  coverage, normal, fault, identity, and marker verdict checks invoke Kotlin
  tooling. Its registered-host-PID probe now runs Kotlin instead of the
  operational Python probe. The Python implementation remains reproducible.
- Active short matched/live operation verification and the short matched fork
  parser/completeness gate use Kotlin. The short historical 35-arm archive
  reproduced the legacy Node rows and exit codes exactly (35/35). Active
  short matched/live/fault source-fingerprint callers use the existing Kotlin
  fingerprint, with identical digest and file inventory on the same checkout.
- The active matched, live-comparison and ring-drop fault scripts now call
  Kotlin for process scheduling, per-arm rows and aggregate summaries. The
  fork-level fault parser reproduced all ten retained native-live fault rows
  exactly, and the retained 30-arm ring-drop rows matched all historical
  columns (the later optional `native_live_publications` field is null in
  that older archive).
- The rootful Docker JUnit suite executed all eight short cases. The
  unavailable Podman endpoint skipped all eight cases with a visible reason;
  actual rootful Podman runtime parity is unverified. The controlled 72-path
  ring-drop fault and unclosed native envelope fixtures passed with Kotlin
  helpers. A later real guest PID-reuse qualification is recorded below.

## Active ownership

These are still substantive controllers, not thin launchers:

- `scripts/run_tier_e_split_privilege_fixture.sh` is a launcher. Without
  `TIER_E_SPLIT_CREATE_LOCK` it executes `fixture-run-env`. Real guest PID
  reuse requires a disposable Linux guest with a bounded guest-only PID limit
  and an explicit v2 hybrid registered profile; a normal host's PID controls
  remain unchanged. The Kotlin controller observed a real guest-host PID
  replacement held across collector resume and rejected it with incomplete
  lifecycle, zero resolved syscalls, and policy-ineligible terminal evidence;
  see [the Kotlin guest receipt](../../evidence/tier-e-kotlin-real-pid-reuse-20260926/README.md).
  Delayed producer-closed artifacts, explicit bounded
  incomplete coverage, live snapshots, and benchmark phases are typed fixture
  inputs. Older v2 profiles without `delayed_artifact` default it to false.
  A caller-supplied
  creation lock keeps container cleanup with that caller. Kotlin checks the
  run ID, scratch-directory ownership, lock and open-state identities before
  writing artifacts or creating containers; failure receipts mark those
  resources `EXTERNALLY_OWNED`. The legacy script is no longer the active
  controller.
- `scripts/run_tier_e_split_coverage_matrix.sh` launches `coverage-matrix`.
  Skip-existing, allowed-incomplete coverage, and non-default arm timeouts are
  rejected. `:profiler:tierELongMatchedBenchmark` calls the same Kotlin
  scheduler directly and remains outside `build`/`check`.
- `scripts/run_tier_e_split_negative_matrix.sh` launches `negative-matrix`.
  It no longer shells out to `jq`.
- `scripts/run_tier_e_hybrid_fault_matrix.sh` launches `fault-matrix`.
  Live snapshots and phase instrumentation are rejected.
- `scripts/run_tier_e_hybrid_live_comparison.sh` launches `live-matrix`.
  Phase instrumentation and live snapshot admission are fixture inputs. This
  does not reverse `REJECT_DEFAULT_PROMOTION`.
- `scripts/run_tier_e_split_matched_benchmark.sh` launches `matched-matrix`.
  Non-default timeouts, capture profiles, and attachment backends are rejected.
  Matched, fault and live matrices pin image, native agent, JAR and source
  identities before their first arm and compare binary identities afterwards;
  a durable `terminal.json` starts `INCOMPLETE` and becomes complete only after
  every required arm and post-run check succeeds. Baseline and USER_NOTIF arms
  use exact-name container cleanup on failure. Unique-edge benchmarks accept
  representative operation evidence only when the session-bound typed oracle
  reports a complete terminal, a nonzero recovered denominator, and no
  unresolved observations; ordinary fixture errors never become successful
  arms because their text resembles an expected oracle result.
- The trustworthy, rootful, capacity, and coverage experiment entrypoints
  launch Kotlin tooling. The trustworthy controller owns local Docker volume
  creation, separate bundle-build and benchmark containers, bounded artifact
  export, terminal JSONL admission, typed manifest and process-scoped perf or
  allocation evidence. Export now precedes host-side validation; only complete
  perf, allocation, manifest and source/image checks authorize exact container
  and volume retirement. A failed post-export check stops and retains them.
  Bounded one-fork rootful Docker CPU perf with 11/11 required JIT maps and
  allocation recording with an owned per-run kernel probe both completed
  with policy-ineligible terminal receipts. Large raw `perf.data` and
  allocation data remain local diagnostic artifacts, outside Git; SHA-bound
  local receipts are at [perf/allocation](../../evidence/tier-e-kotlin-perf-allocation-20260926/README.md). The
  capacity controller pins per-stage retained limits and stops at the first
  ineligible pilot; a single short Docker stage passed. Its optional node-noise
   probe now runs a separate JVM-based `/proc/self/stat` loop and must not be
   combined with historical shell-loop rates. A bounded one-pair rootful run
   correctly remained incomplete when the underlying 10,000-operation Tier E
   target missed 11 operation IDs; a no-noise control under the same 10,000-op
   schedule also missed 6. The opt-in raw-syscall and exact-admission audits
   localized one failing target to 10,000 raw entries but only 9,994 submitted
   exact admissions. A later audit-enabled, marker-bound one-pair Java noise
   diagnostic completed with 10,000/10,000 raw entries, submitted admissions,
   and matched operations; see the [sealed diagnostic](../../evidence/tier-e-kotlin-node-noise-diagnostic-20260926/README.md).
   This is not a five-pair default-path qualification: intermittent missing IDs
   remain open and no denominator or operation oracle is relaxed.
  The clean-workspace rootful gate is fail-closed and retains its runner and
  workspace when export/qualification fails. Transferring the tracked root
  `.editorconfig` eliminated apparent enforcer ktlint violations, and an owned
  UNIX socket fixture passed fresh-JVM tests. The historical raw-key-only
  version-one gate correctly rejects version-two native samples and its
  original threshold and baseline remain immutable. The operator-approved
  version-two rootful gate measures key construction and cache lookup in
  disjoint intervals, checks the exact capture/cache denominator, and bounds
  the per-sample key+lookup+logical median by 125% of each unchanged historical
  mode's total. The clean rootful Docker gate passed for both full and unique
  without changing attribution or policy eligibility; a predetermined
  15-pair Debug diagnostic comparison retained complete captures and a
  beneficial paired 95% interval in both modes. See the
  [retained v2 evidence](../../evidence/tier-e-comparable-phase-v2-20260926/README.md)
  and the [original isolation record](../../evidence/tier-e-raw-key-comparability-20260926/README.md).
  A later clean-workspace rootful gate on the final Kotlin diagnostics
  revision `d82ca4b9` completed its native and profiler checks but **failed**
  the unchanged `GetStackTrace` historical phase limit in two bounded runs
  (4,676,424 ns and 2,743,788 ns). Both retained stopped exact-name runners,
  artifact volumes, logs and `INCOMPLETE` terminals. These failed gates do not
  supersede the earlier v2 passing receipt, nor do they qualify this final
  revision. The final orchestrator handoff remains blocked; no phase threshold
  was increased or baseline replaced.
  The subsequent **clean**, committed `de268c9e` gate explicitly pinned the
  Docker runner to CPUs `0,1` through the versioned
  `config/testing/tier-e/rootful-gate.json` profile. All 39 native CTests,
  the profiler/rootful/fresh-JVM suites and both unchanged version-two phase
  bounds passed with a complete, policy-ineligible terminal. The matched
  [source-pinned phase comparison](../../evidence/tier-e-pinned-native-comparison-20260927/README.md)
  retained 96/96 successful CTests across clean prior/current revisions using
  the **same** native agent and Java workload bytes. Unrestricted placement
  produced incomplete phase gates even for the earlier revision; explicit
  `0,1` placement completed all six predeclared pinned runs. The earlier
  failures remain preserved, not rebaselined. See the
  [clean aggregate receipt](../../evidence/tier-e-pinned-rootful-gate-20260927/README.md).
  A subsequent execution split removed the gate's forced Gradle `--rerun-tasks`
  while keeping `integrationTest`, `integrationTestFreshJvm`, and
  `rootfulContainerTest` non-cacheable as live kernel evidence. A separate
  `:profiler:tierERootfulPhaseProbe` acquires **fresh** full/unique CTest
  samples on each invocation; a content-keyed Docker native build volume
  reuses only compilation for matching source/image/compiler/JDK inputs.
  `:profiler:tierEReplayPhaseEvidence` verifies retained raw samples against
  the same historical baseline and is Gradle-incremental. Two same-binary
  native-only probes completed with different run IDs in 23 and 10 seconds;
  earlier probes on source-dirty diagnostic worktrees failed the unchanged
  timing guard. Those first two runs are diagnostics, not the later
  interleaved clean-revision comparison or a replacement for the aggregate
  gate.
  The new `tierETrustworthyExperiment` and `tierENodeNoiseExperiment` tasks
  select strict experiment JSON profiles rather than operator benchmark
  variables. Version-two profiles also pin image and workload/collector CPUs,
  and `tierECapacityExperiment` resolves its typed pilot/decision schedule from
  `config/testing/tier-e/capacity.json`. Legacy environment-based entrypoints
  remain available for historical reproduction. The bundle's bootstrap and
  nested Gradle builds use owned project caches to avoid host-checkout locks;
  two bounded 1,000-operation typed pilots completed with an independently
  [retained cache receipt](../../evidence/tier-e-typed-capacity-20260927/README.md).
  Machine-local Docker endpoint selection remains separate. These short pilots
  do not qualify a capacity ceiling. The subsequent **clean `b8901ed7`**
  default five-pair node-noise profile correctly stopped at its first
  incomplete target: full-stream matched 10,000/10,000, unique mode missed
  five consecutive IDs, and zero complete pairs were published. The same
  source/image/agent's separate unaudited no-noise control and one-pair
  audit-enabled noisy diagnostic both completed. Those do not repair or
  attribute the intermittent default-path observation gap; see the
  [fail-closed receipts](../../evidence/tier-e-clean-node-noise-20260927/README.md).
  Podman parity remains operator-deferred and unverified.
  The post-capacity clean `b8901ed7` gate's first run timed out one
  four-thread JVM test under a whole-container two-CPU limit; an identical
  retry passed the JVM suites but failed both native `GetStackTrace` phase
  bounds. Measuring phase first while pinning only the CTest child still
  produced an `INCOMPLETE` timing result. All three failures and their
  recovery receipts remain retained. The [subsequent clean
  `ae38c8ab` gate](../../evidence/tier-e-separated-phase-gate-20260927/README.md)
  separated acquisition from JVM testing: a fresh, isolated six-sample
  native phase passed the **unchanged** v2 bounds, then the full rootful gate
  required the exact same source revision/digest, image ID, native agent and
  workload SHA-256s, JDK/compiler and CPUs before independently replaying
  every phase sample. All 39 native CTests and the fresh unrestricted-CPU
  profiler/kernel/JVM suites passed; the aggregate terminal was `COMPLETE`
  and policy-ineligible. No cached test or unrelated phase result can replace
  live kernel validation. This still does not qualify the incomplete default
  node-noise lane or deferred Podman parity.
  Gradle now owns a cacheable native **build-artifact package** (not CMake
  intermediate state or CTest results). The rootful, phase-only and
  profile-selected trustworthy/capacity/noise controllers validate native
  source, selected image/toolchain/build flags and every packaged file hash
  before loading binaries into per-run writable CMake directories. A second
  clean checkout restored the package `FROM-CACHE` in six seconds after the
  first built in 24 seconds; all 39 native CTests then executed afresh. The
  [cache receipt](../../evidence/tier-e-gradle-portable-cache-20260927/README.md)
  separates this from intermediate source-dirty, short matched workload
  timings and from the still-unqualified node-noise account. The original
  Docker build-volume path remains a compatibility route; it is not treated
  as a new measurement or automatically deleted.
  The bundle entrypoint bootstraps Kotlin
  tooling from the checkout and delegates source fingerprinting, native/runtime
  cache staging, manifest verification, and bundle replacement to Kotlin.
  A real native/runtime bundle built by this controller passed the historical
  shell verifier and was reused without rebuilding. The active coverage summary and stack identity
  comparison are Kotlin-owned; replay on copied immutable 2026-09-21 smoke and
  five-fork full archives matched their saved Node summaries with zero semantic
  JSON differences. Standalone Python perf
  analysis remains outside automatic runners. Historical standalone analysis
  commands must stay reproducible but not be selected as active dependencies.

The additional active native release/flavour, transport comparison, phase gate,
ring decoder/mapped-drain, and focused/delivery checks scripts now launch Kotlin
controllers. `TierEActiveEntrypointPolicyTest` inventories active `run_tier_e_*`,
`build_tier_e_*`, `compare_tier_e_*`, and `check_tier_e_*` scripts, excluding only
the explicit legacy fixture replay, and rejects a return to Node, Python, jq,
CTest, engine CLI commands, or shell matrix loops in active launchers. Native
phase medians still use the historical 125% baseline bound; the current host
phase test rejected a measured regression, which is retained as a failed gate
rather than rebaselined. The operator's `REJECT_DEFAULT_PROMOTION` decision
remains unchanged.

Completion means no active Tier E caller depends on repository-owned Bash,
Python, or Node for orchestration, protocol construction, verdicts, or
measurement policy; simple external CLI launch shims may remain. Each
replacement requires tests for malformed and incomplete evidence, preserved
source/image/native identities, real rootful Docker checks, and separate
Podman runtime evidence. Do not promote any historical diagnostic to a new
performance or containment decision merely because its tools were migrated.
