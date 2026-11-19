---
title: "Tier E diagnostic hardening, operator workflow and consolidation"
document_type: execution_plan
status: "proposed"
base_revision: b2c9b335720fd0491a8d86917a3b0593b818a4ec
---

# Plan A — make Tier E dependable and maintainable

**Goal:** Deliver a specialist diagnostic backend with truthful finalization, bounded
retryable ownership, one packaged operator workflow, a smaller maintained tooling
surface and qualified reference measurements.

Read the [roadmap and authority contract](2026-11-03-tier-e-improvement-roadmap.md),
the [Tier E design](../../internals/designs/profiler/tier-e-design.md),
the [split-privilege design](../../internals/designs/profiler/tier-e-split-privilege-deployment.md)
and the [liveness contract](../../internals/designs/core/notification-liveness-contract.md).
The existing September plans are dependencies to reconcile, not instructions to
repeat implemented work. Keep all-target capture opt-in and evidence policy-ineligible.

## Scope map

| Area | Existing locations |
|---|---|
| Session/collector owners | `profiler/src/main/kotlin/io/mazewall/profiler/internal/attribution/` |
| Daemon/control/evidence | `profiler/src/main/kotlin/io/mazewall/profiler/tierE/daemon/` |
| Host owner tests | `profiler/src/test/kotlin/io/mazewall/profiler/attribution/` |
| Host daemon tests | `profiler/src/test/kotlin/io/mazewall/profiler/tierE/daemon/` |
| Native capture | `profiler-native/src/agent.cpp`, `profiler-native/include/`, `profiler-native/tests/` |
| Active experiment tooling | `profiler/src/tooling/kotlin/io/mazewall/profiler/tierE/`, `config/testing/tier-e/` |
| Packaged commands | `profiler/build.gradle.kts`, `profiler/src/tooling/kotlin/io/mazewall/profiler/tierE/tools/TierEToolsMain.kt` |
| Kernel acceptance | `profiler/src/integrationTest/kotlin/io/mazewall/profiler/TierERootfulContainerEndpointIntegrationTest.kt` |

Names below are relative to these owning directories unless a full path is given.
Declare exact changed files in each backlog item. Preserve existing public APIs,
wire versions, accepted evidence readers and source-pinned historical receipts.

## A1. Preserve ownership through failed finalization and cleanup

**Reuse:** [cleanup issue](../../internals/backlog/issue-20260926-121446-405-preserve-tier-e-resource-ownership-until-cleanup-c.md)
and Delivery 2 of the [handoff plan](2026-09-29-tier-e-poc-handoff-hardening.md).
**Files:** `TierEKotlinDaemon.kt`, `TierECollectorRuntime.kt`,
`TierECollectorLoop.kt`, `TierEProfilerSession.kt`; existing corresponding tests
and a new daemon finalization fault test if no equivalent seam exists.

- [ ] Add a failing end-to-end owner test: `finish` throws, cleanup fails once,
  retry succeeds. Assert the original failure remains primary, independent cleanup
  is attempted, the owner remains reachable and ATTACH cannot replace it.
- [ ] Extend existing fault seams for interrupted collector join, detach failure,
  terminal counter failure and ring unmap failure. Verify no double release and
  no false successful terminal result after any fault.
- [ ] Represent collection termination separately from retirement. Move the
  daemon owner to a closing state; only successful physical cleanup makes it
  retired. A close retry executes outstanding actions only. Retain fatal identity
  and distinct suppressed cleanup failures using existing ownership helpers.
- [ ] Replace suppressed `runtime.finish` failures with the existing incomplete
  terminal-report contract: registered identity, `FINALIZATION_FAILED`, no claimed
  complete profile, all unproven dimensions false, `policy_eligibility=false`.
  Report publication failure remains observable through control state/logging.
  No incomplete event artifact gets promoted to a completed filename.
- [ ] Check that a failed terminal is emitted at most once and cannot be upgraded
  by retrying shutdown. Diagnostic report failure and resource retirement remain
  distinct facts; retrying cleanup does not manufacture collection success.

**Focused gate:**

```bash
./gradlew :profiler:test --tests '*TierECollectorRuntimeTest' --tests '*TierECollectorLoopTest' --tests '*TierEProfilerSessionTest' --tests '*TierEDaemon*Test' --tests '*TierETerminalReportFileSinkTest'
```

**Exit:** Every injected failure has a reachable cleanup owner and an honest
terminal outcome. Follow with the delivery completion gate below.

## A2. Make shutdown terminal and cached results identity-safe

**Reuse:** [shutdown issue](../../internals/backlog/issue-20260926-122439-817-make-tier-e-shutdown-terminal-across-service-and-c.md)
and [snapshot identity issue](../../internals/backlog/issue-20260926-122447-716-bind-cached-tier-e-terminal-results-to-admitted-sn.md).
**Files:** `TierEKotlinDaemon.kt`, `ControlSessionMachine.kt`,
`TierECollectorRuntime.kt`, `ControlSessionMachineTest.kt`,
`TierECollectorRuntimeTest.kt` and daemon lifecycle tests. These may be two scoped
deliveries; neither needs a new lifecycle framework.

- [ ] Reproduce direct shutdown followed by queued ATTACH and late attach
  success/failure completion. Terminal control state must absorb completions;
  stale successful acquisition is disposed, never published as a new session.
- [ ] Bind terminal caching to an immutable admitted snapshot identity, session
  epoch and producer-closure evidence. Validate identity before returning a
  cache hit. A null artifact placeholder is not snapshot identity.
- [ ] Test repeated identical finalization, mutated input collections, changed
  definitions, wrong session and changed closure evidence. Identical admitted
  requests are idempotent; incompatible requests reject without replacing the
  original terminal or releasing resources twice.
- [ ] Freeze relevant collections at admission, using the existing canonical
  snapshot identity/encoding where available; do not trust caller-owned mutable
  collections or a filename alone.

**Focused gate:**

```bash
./gradlew :profiler:test --tests '*ControlSessionMachineTest' --tests '*TierECollectorRuntimeTest' --tests '*TierEDaemon*Test'
```

**Exit:** No terminal state reopens and no cached result crosses a session or
snapshot identity. A1 ownership semantics hold in all new terminal branches.

## A3. Close the delayed-artifact correctness gap

**Reuse:** [71/72 coverage issue](../../internals/backlog/issue-20261019-084828-251-investigate-tier-e-delayed-artifact-coverage-dropp.md).
**Files:** The rootful integration test, `TierECollectorRuntime.kt`,
`TierEProfilerSession.kt`, `InvocationDictionaryJoiner.kt` and their existing tests
only as causal evidence requires. Inspect the actual dictionary consumer before
selecting any additional file.

- [ ] Recover the retained original failing artifact/event/reader timeline and
  compare the missing declared path with its producer, watermark, reader and
  terminal ownership. Record evidence availability; a missing historical file
  does not justify guessing a cause.
- [ ] Build a deterministic late-definition/closure ordering regression from
  the causal finding. Keep incomplete evidence incomplete until definitions and
  terminal proof reconcile. A subsequent passing run cannot erase the failure.
- [ ] Fix the responsible boundary once, preserving explicit bounds and the
  strict 72/72 declared-operation contract. Do not change the expected count,
  retry until green, widen a timeout without cause, or treat diagnostic capture
  completeness as policy eligibility.
- [ ] Rerun the affected kernel scenario with fresh artifacts and the same
  declared matrix. Retain both fault and positive evidence. If reproduction is
  unavailable, keep the issue open and mark the reference lane unqualified.

**Focused gate:** Causal host test, then the owning rootful integration method
using its existing configured endpoint. Confirm the test actually executed and
retained the required operation identities; a skip is not acceptance.

## A4. Ship one usable operator workflow

**Reuse:** Deliveries 1 and 3 of the
[handoff plan](2026-09-29-tier-e-poc-handoff-hardening.md).
**Files:** `TierETargetRegistration.kt`, `ElfSymbolOffset.kt`,
`TierETargetControlClient.kt`, event/terminal sinks, `TierEKotlinDaemon.kt`,
`profiler/build.gradle.kts`, daemon tests and the split-privilege guide.
**New:** The small helper CLI/registration writer and evidence-access policy
already specified by that plan.

- [ ] Add the packaged `tier-e-target-helper register` and `attach` using the
  current strict registration parser, ELF resolver and control client. Derive
  start-time and marker offset from operator-selected inputs. Do not require the
  operator to hand-encode control frames or compute ELF offsets.
- [ ] Check no-overwrite/no-symlink output, exited/reused PID, host versus
  container PID, wrong library/session, unsafe artifact name, malformed control
  response, absent permissions and nonzero exit on `ERR`.
- [ ] Implement opt-in evidence GID handling exactly as in the handoff plan:
  default `0600`, selected `0640`, closed temporary file attributes before atomic
  publication, no implicit directory permission changes. Both event and terminal
  files must follow the same publication/access policy.
- [ ] Provide one copyable launch → register → attach → run → detach → inspect
  sequence using installed commands. Show actual final artifacts, unsupported
  setup diagnostics, loss/unknown/truncation/closure fields and cleanup status.
- [ ] Document privileges/capabilities, PID/mount visibility, exact JDK bindings
  and supported capture profiles. All-target mode adds unattributed observations;
  it does not enlarge exact Java attribution or enable enforcement.
- [ ] Run the installed distribution with the helper-generated registration in
  the existing separate-daemon fixture. Prove cross-UID access with a non-root
  group member and refusal for a nonmember; root-only readability is insufficient.

**Focused gates:** Existing daemon/parser/sink tests, `:profiler:installDist`,
packaged-command smoke and the rootful cross-UID fixture. Preserve the existing
daemon command and control version; no second operator protocol.

## A5. Reduce the maintained surface after the supported path works

**Reuse:** [tooling migration issue](../../internals/backlog/issue-20260925-211445-014-move-active-tier-e-experiment-controllers-and-rema.md)
and its source-pinned retirement inventory.
**Files:** Active Kotlin tooling and tests, `TierEToolsMain.kt`,
`profiler/build.gradle.kts`, specifically identified legacy scripts/docs;
`agent.cpp` only for demonstrated diagnostic-only extraction.

- [ ] Inventory entry points and their actual callers: Gradle, CI, packaged
  distributions, docs and retained evidence verifiers. Label each supported,
  research-only or superseded. Reuse the inventory already present in the issue.
- [ ] Keep one maintained operator path and the existing typed experiment entry
  point. Remove a duplicate controller only after its callers migrate and its
  evidence/failure semantics have parity tests. Old evidence must remain readable.
- [ ] Freeze research-only modes behind existing opt-ins; move historical claims
  to clearly dated evidence references. Avoid introducing another configuration
  language, runner, package split or generic plugin system.
- [ ] Keep native hot-path capture, lifecycle and symbol identity cohesive. If
  timing/fault/experiment code demonstrably complicates it, extract that existing
  code behind the current test/diagnostic boundary; do not rewrite the agent.
- [ ] Consolidate current/default behavior and the 12-binding coverage matrix in
  the Tier E design; retain links to measured decisions and historical overrides.
  Link the new live-scoping contract as proposed/implemented according to actual
  status, never reinterpret the diagnostic stream as authorization.

**Exit:** Fewer active entry points/duplicate implementations, all retained
consumer/golden tests passing, no public compatibility break. Record before/after
entry-point and supported-configuration counts; code movement alone is not value.

## A6. Bound performance work and use Tier E as a scoped reference

**Files:** Existing `config/testing/tier-e/` profiles, Kotlin measurement tools
and evidence verifiers; the owning source file only after measurement identifies
it. Read the existing sustained/readiness decisions before adding experiments.

- [ ] Freeze a representative workload and budgets before measurement: steady
  and burst rates, stack depths/cardinality, concurrency, duration, allowed
  workload latency/throughput impact, target/collector CPU and memory ceiling.
  Report exact attributable operations versus attempted/observed operations.
- [ ] Retain the current qualified defaults until new paired evidence justifies
  a change. Continuous versus adaptive/readiness polling and transport variants
  already have decisions; do not rerun rejected promotions without a new hypothesis.
- [ ] Measure matched baseline, agent-only, and end-to-end diagnostic arms with
  exact source/native/JDK/kernel/profile fingerprints. Include warmup, independent
  forks, uncertainty, per-boundary cost, receipt latency, loss and retained-state
  high-water marks. Use validated monotonic clock domains for cross-process timing.
- [ ] Try at most one measured bottleneck change at a time: capture-profile
  selection, existing canonical cache, symbolization, marker transport or drain
  work. Exact mode must still capture every admitted boundary; sampling/stale
  stack reuse is not an enforcement optimization. Stop when the frozen budget
  passes or measurements do not support another change.
- [ ] Compare Tier E and the agentless collector only on a declared common
  operation/JDK/workload set with a third workload oracle. Verify that simultaneous
  instrumentation does not invalidate the comparison; otherwise use matched
  separate runs. Preserve wrong, missing, unattributed and unsupported categories.
- [ ] Require correct counts and call-path identities before comparing cost.
  A3's unresolved lane cannot certify another backend. Neither Tier E's selected
  bindings nor agreement between two profilers proves universal syscall coverage.

Existing entry points (choose the relevant profile; these are not all completion
gates):

```bash
./gradlew :profiler:tierEShortFixture -PtierEProfile=config/testing/tier-e/smoke.json -PtierEOutput=/tmp/opencode/tier-e-short-run
./gradlew :profiler:tierELongMatchedBenchmark -PtierEOutput=/tmp/opencode/tier-e-matched-run
./gradlew :profiler:tierEVerifyArtifacts -PtierEArtifacts=/tmp/opencode/tier-e-matched-run
```

Use a fresh output directory for each run and the configured container endpoint.
Long benchmarks are opt-in and must not be added to ordinary test/build tasks.

## TDD cases

- A1: finalization, join, detach, counter-read and unmap faults preserve ownership and error identity.
- A2: shutdown is terminal; changed/mutable/wrong-session snapshots cannot hit a prior result cache.
- A3: delayed definitions and closure preserve strict declared-operation coverage.
- A4: helper inputs and evidence publication reject identity, symlink, permission and protocol failures.
- A5–A6: preserve existing evidence-reader/golden cases and reject incomplete performance/reference inputs.

Use the focused red/green checks in each task, with kernel evidence where host
tests cannot establish the property.

## Delivery completion gate

1. Run the affected focused host tests; meaningful fault assertions precede the
   behavior fix. Run `./gradlew :profiler:test` after the focused checks pass.
2. For native changes, build and run the relevant CTest suite. For changed kernel
   behavior or cross-UID contracts, run the owning configured integration lane and
   retain evidence of execution. Cached native binaries are not cached test proof.
3. Run `./gradlew build` once at the scoped completion checkpoint. Do not weaken
   formatting, static analysis, coverage or required integration gates.
4. Update the relevant design/operator documentation and existing issue with
   actual evidence; archive only completed criteria. Check references,
   `git diff --check` and `./scripts/adkw check-backlog`.
