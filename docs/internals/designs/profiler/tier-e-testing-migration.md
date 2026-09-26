# Tier E testing configuration and execution ownership

**Status (2026-09-25): In progress; not a Kotlin-only execution path.**

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

## Implemented migration slice

- Short rootful JUnit fixture consumes the smoke JSON profile and exports its
  resolved configuration; other scenario parameters in its legacy shell
  controller still cross the compatibility environment boundary.
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
  Kotlin for per-arm rows and aggregate summaries as well. The current
  fork-level fault parser reproduced all ten retained native-live fault rows
  exactly, and the retained 30-arm ring-drop rows matched all historical
  columns (the later optional `native_live_publications` field is null in
  that older archive). These scripts still own process scheduling in Bash.
- The rootful Docker JUnit suite executed all eight short cases. The
  unavailable Podman endpoint skipped all eight cases with a visible reason;
  actual rootful Podman runtime parity is unverified. The controlled 72-path
  ring-drop fault and unclosed native envelope fixtures passed with Kotlin
  helpers. A live host-PID-reuse qualification was not run here.

## Remaining active ownership

These are still substantive controllers, not thin launchers:

- `scripts/run_tier_e_split_privilege_fixture.sh` is a launcher. Without
  `TIER_E_SPLIT_CREATE_LOCK` it executes `fixture-run-env`. Real guest PID
  reuse is rejected. Delayed producer-closed artifacts, explicit bounded
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
  allocation evidence. A one-fork rootful Docker smoke completed; perf and
  allocation recording still need their own live runtime qualification. The
  capacity controller pins per-stage retained limits and stops at the first
  ineligible pilot; a single short Docker stage passed. Its optional node-noise
  probe now runs a separate JVM-based `/proc/self/stat` loop and must not be
  combined with historical shell-loop rates. A bounded one-pair rootful run
  correctly remained incomplete when the underlying 10,000-operation Tier E
  target missed 11 operation IDs; it is not qualified evidence of node-noise
  parity or a reason to relax the Tier E operation oracle.
  The clean-workspace rootful gate is fail-closed and retains its runner and
  workspace when export/qualification fails. Transferring the tracked root
  `.editorconfig` eliminated apparent enforcer ktlint violations, and an owned
  UNIX socket fixture passed fresh-JVM tests. The gate now stops at a real
  `raw_key_ns` phase median of 770,918 ns versus its 551,140 ns retained limit
  (issue-20260926-133258-892); no baseline was revised. Controlled rootful
  historical/current Debug pairs show the full-stream raw-key interval now
  includes canonical-cache lookup (504 hits), which the historical full-stream
  interval excluded. The old per-phase threshold remains failed until an
  operator approves a comparable, lookup-inclusive total-work gate; see the
  [retained isolation evidence](../../evidence/tier-e-raw-key-comparability-20260926/README.md).
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
