---
title: "Tier E Production Readiness and Boundary Selection Implementation Plan"
status: "active"
document_type: execution_plan
base_revision: 24cf8d6b
---

# Tier E Production Readiness and Boundary Selection Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ship a reusable production-owned Tier E collector, replace the benchmark-only single-binding capture profile with safe configurable boundary selection, and use sustained and application-shaped measurements to decide whether any further kernel-side optimization is justified.

**Architecture:** Keep exact capture synchronous for every admitted native boundary and keep dictionary/full-stream, AUTO-selected validated USDT, continuous collection, and raw `MZSD v2` as defaults. Put lifecycle ownership above the existing `TierECollectorLoop`, represent boundary selection as an immutable set of validated `BindingKind` values, and treat excluded bindings as an explicit capture-domain choice rather than an attribution failure. Measure those changes with the existing calibrated, CPU-pinned benchmark bundle before opening a separate scoped-tracepoint experiment.

**Tech Stack:** Kotlin/JDK 25 profiler module, C++ JVMTI native agent, generated eBPF programs, Gradle/JUnit, CMake/CTest, rootful Docker benchmark bundle, JSONL evidence.

**Spec:** `/home/leanid/Documents/code/java/jseccomp/docs/internals/designs/profiler/tier-e-design.md`

## Global Constraints

- Preserve exact `GetStackTrace` capture for every admitted boundary; do not use signatures, timestamps, approximate stacks, or previously captured context as proof.
- Preserve collector-authoritative acknowledgement, fresh invocation identities, bounded retention, fail-closed terminal evidence, raw `MZSD v2`, and `policy_eligibility=false`.
- Keep dictionary/full-stream, AUTO/USDT, legacy ring decoding, and continuous collection as production defaults unless a later decision artifact passes its predeclared gates.
- Keep `capture-profile=jdk25-unix-openat0` readable as a compatibility alias while introducing typed binding selection.
- Reject unknown, unsupported, empty, duplicate, or JDK-incompatible binding selections before capture admission opens.
- Excluded bindings must execute their original native operation with no exact marker installed; exclusion is reported as capture-domain scope, not fabricated coverage.
- Do not add dependencies, alter BPF map schemas, change ring-record layouts, or introduce a new dictionary format.
- Rootful Docker results are privileged performance evidence; host/rootless results remain explicitly qualified.
- Optimize benchmark execution by reusing the immutable source bundle, native Release artifact, Gradle caches, and one toolchain image per matrix.

## Review Focus

- A malformed or unsupported boundary profile must fail closed before admission, while the default omitted profile must remain full capture.
- Detach, callback suppression, capture failure, exec, and terminal shutdown must clear exact marker state even when a boundary is excluded.
- Collector shutdown must use an externally proven producer-closure fact; an empty ring or readiness timeout must never imply closure.
- Sustained overload above capacity must become explicit incomplete evidence with reconciled loss, not a successful performance sample.
- Narrowed profiles must compare equal application outputs and exact admitted stacks while reporting omitted bindings as declared scope.

---

### Task 1: [100%] Freeze the measured production baseline

**Files:**
- Reference: `docs/internals/evidence/tier-e-calibrated-readiness-and-capture-20260919.json`
- Reference: `docs/internals/designs/profiler/tier-e-design.md`
- Reference: `docs/internals/backlog/issue-20260918-210822-014-integrate-a-production-owned-tier-e-collector-loop.md`

**Interfaces:**
- Consumes: Existing calibrated fifteen-fork readiness decision and five-fork capture-phase studies.
- Produces: Fixed defaults and rejection decisions that later tasks must not silently revisit.

- [x] **[100%] Step 1: Retain continuous collection as the default.**

  Readiness-driven collection remains explicit and experimental because full-stream improved collector CPU by 11.34% and unique-edge by 12.53%, below the 15% gate, while unique-edge also failed the receipt-p99 gate.

- [x] **[100%] Step 2: Retain the current transport and marker defaults.**

  Keep dictionary/full-stream, AUTO-selected validated USDT, legacy ring decoding, exact per-boundary capture, and `policy_eligibility=false`.

- [x] **[100%] Step 3: Stop unmeasured dictionary and warmed-cache micro-optimization.**

  Existing evidence identifies synchronous stack walking and marker transitions as dominant; dictionary serialization and warmed-hit allocation are not current decision targets.

---

### Task 2: [100%] Add a lifecycle-owning reusable collector service

**Files:**
- Create: `profiler/src/main/kotlin/io/mazewall/profiler/internal/attribution/TierECollectorService.kt`
- Modify: `profiler/src/main/kotlin/io/mazewall/profiler/internal/attribution/TierECollectorLoop.kt`
- Modify: `profiler/src/main/kotlin/io/mazewall/profiler/internal/attribution/TierEProfilerSession.kt`
- Test: `profiler/src/test/kotlin/io/mazewall/profiler/attribution/TierECollectorServiceTest.kt`
- Test: `profiler/src/test/kotlin/io/mazewall/profiler/attribution/TierEProfilerSessionLifecycleTest.kt`

**Interfaces:**
- Consumes: `TierEProfilerSession`, `TierECollectorLoop`, `TierECollectorLoopPolicy`, and caller-supplied producer closure.
- Produces: `TierECollectorService.start()`, `TierECollectorService.finish(definitionFile, producerClosed)`, `TierECollectorService.close()`, and immutable `TierECollectorServiceMetrics`.

- [ ] **[0%] Step 1: Write failing lifecycle ownership tests.**

  Cover start-once, finish-once, close-before-start, close-during-collection, collector exception, callback exception, explicit producer closure, false producer closure, and concurrent finish/close. Assert that no path finishes a profile from an empty ring alone and every owned source closes exactly once.

- [ ] **[0%] Step 2: Introduce explicit service states.**

  Add internal `NEW`, `RUNNING`, `STOPPING`, and `TERMINAL` states. The service owns one collector thread, while the caller remains responsible for proving that native/BPF producers are closed.

- [ ] **[0%] Step 3: Separate delivery failure from collection failure.**

  Capture exceptions from `onResolved`, event draining, and terminal publication as distinct terminal causes. Preserve retained evidence and expose the failure in service metrics instead of silently ending the thread.

- [ ] **[0%] Step 4: Make shutdown ordering explicit.**

  Request stop, join the collector thread with a bounded deadline, invoke `TierECollectorLoop.finish` only with the supplied producer-closure fact, freeze loop/session metrics, then close the session. Timeout or missing closure yields incomplete lifecycle/transport evidence.

- [ ] **[0%] Step 5: Keep collection policy configuration typed.**

  Default the service to `TierECollectorLoopPolicy.CONTINUOUS`; expose readiness and adaptive policies only through internal experimental configuration and include the selected policy in metrics/evidence.

- [ ] **[0%] Step 6: Run focused tests and commit.**

  Run `./gradlew :profiler:test --tests io.mazewall.profiler.attribution.TierECollectorServiceTest --tests io.mazewall.profiler.attribution.TierEProfilerSessionLifecycleTest` and commit as `feat(tier-e): add production-owned collector service`.

---

### Task 3: [100%] Generalize native boundary selection safely

**Files:**
- Create: `profiler-native/include/capture_profile.hpp`
- Modify: `profiler-native/include/binding_manifest.hpp`
- Modify: `profiler-native/src/agent.cpp`
- Create: `profiler-native/tests/capture_profile_test.cpp`
- Modify: `profiler-native/CMakeLists.txt`

**Interfaces:**
- Consumes: `BindingKind` values classified by the exact ABI-aware `BindingManifest`.
- Produces: immutable `CaptureProfile`, `CaptureProfile::parse(std::string_view)`, `CaptureProfile::admits(BindingKind)`, and stable external binding names.

- [ ] **[0%] Step 1: Write parser and admission tests before extraction.**

  Cover omitted/full, the legacy `jdk25-unix-openat0` alias, one binding, multiple bindings, stable ordering, unknown names, duplicates, empty lists, unsupported values, and default preservation.

- [ ] **[0%] Step 2: Give each supported binding one stable configuration name.**

  Map the existing `BindingKind` values to names such as `unix-open0`, `unix-openat0`, `unix-close0`, `unix-unlink0`, `file-read0`, `file-write0`, `file-close-int-fd`, `socket-read0`, `socket-write0`, `net-socket0`, `net-connect0`, and `net-accept`.

- [ ] **[0%] Step 3: Parse a bounded plus-separated option.**

  Add `capture-bindings=name+name` because native agent options already use commas between options. Enforce the finite manifest size, reject duplicates and unknown names, and canonicalize the selected set without heap growth on the capture path.

- [ ] **[0%] Step 4: Preserve JDK-qualified admission.**

  Keep narrowed admission closed until `VM_INIT` validates the supported JDK/manifest interval. A validation failure leaves every narrowed binding excluded and records an explicit profile-admission failure; it must not fall back to full capture.

- [ ] **[0%] Step 5: Replace the global hard-coded branch with immutable membership.**

  `profile_admits(bindingKind)` becomes a constant-time lookup in the validated profile. The proxy must clear stale marker state before this decision and excluded methods must call the original operation unchanged.

- [ ] **[0%] Step 6: Add bounded per-binding admission accounting.**

  Report admitted and excluded boundary counts by the finite `BindingKind` enum. Counters are diagnostic only and never authorize attribution or alter marker transitions.

- [ ] **[0%] Step 7: Run native tests and commit.**

  Run a Release CMake build and focused CTest for `capture_profile_test`, then commit as `feat(tier-e): add manifest-backed capture profiles`.

---

### Task 4: [100%] Add typed Kotlin configuration and evidence scope

**Files:**
- Create: `profiler/src/main/kotlin/io/mazewall/profiler/internal/attribution/TierECaptureProfile.kt`
- Modify: `profiler/src/main/kotlin/io/mazewall/profiler/internal/attribution/TierEProfilerSession.kt`
- Modify: `profiler/src/main/kotlin/io/mazewall/profiler/tierE/stress/TierEKernelSmoke.kt`
- Test: `profiler/src/test/kotlin/io/mazewall/profiler/attribution/TierECaptureProfileTest.kt`
- Modify: `profiler/src/test/kotlin/io/mazewall/profiler/tierE/stress/TierEMatchedBenchmarkJsonTest.kt`

**Interfaces:**
- Consumes: Stable native binding names from Task 3.
- Produces: `TierECaptureProfile.Full`, `TierECaptureProfile.Selected(bindings)`, canonical agent-option rendering, and a serializable capture-domain declaration.

- [ ] **[0%] Step 1: Write failing configuration tests.**

  Assert deterministic rendering, duplicate rejection, empty-selection rejection, full-profile omission, compatibility alias rendering, and bounded manifest membership.

- [ ] **[0%] Step 2: Add the typed profile to Tier E session/run configuration.**

  Keep `Full` as the default. Do not use arbitrary strings past the option-decoding boundary.

- [ ] **[0%] Step 3: Record declared capture scope separately from observed attribution.**

  Evidence must include selected binding names, admitted counts, excluded counts, admission-validation state, and `capture_domain_validity`. Expected exclusions do not become missing observations, but undeclared exclusions make the run incomplete.

- [ ] **[0%] Step 4: Preserve exact admitted-event reconciliation.**

  Every admitted invocation must still join to one immutable definition and satisfy the existing operation oracle. The profile only narrows admission; it does not relax stack, lifecycle, dictionary, or transport checks.

- [ ] **[0%] Step 5: Run focused tests and commit.**

  Run the capture-profile and benchmark JSON tests, then commit as `feat(tier-e): expose typed capture-domain selection`.

---

### Task 5: [100%] Add sustained-load and long-session collector evidence

**Files:**
- Modify: `profiler/src/main/kotlin/io/mazewall/profiler/tierE/stress/TierEMatchedBenchmark.kt`
- Modify: `profiler/src/main/kotlin/io/mazewall/profiler/tierE/stress/TierEKernelSmoke.kt`
- Create: `profiler/src/main/kotlin/io/mazewall/profiler/tierE/stress/TierESustainedCollectorDecision.kt`
- Create: `profiler/src/test/kotlin/io/mazewall/profiler/tierE/stress/TierESustainedCollectorDecisionTest.kt`
- Modify: `profiler/src/test/kotlin/io/mazewall/profiler/tierE/stress/TierEMatchedBenchmarkJsonTest.kt`
- Create during retained run: `docs/internals/evidence/tier-e-sustained-collector-20260919.json`

**Interfaces:**
- Consumes: `TierECollectorService`, continuous/adaptive policies, calibrated benchmark run levels, and immutable bundle/image provenance.
- Produces: rate-controlled sustained evidence and a decision that cannot promote a policy on incomplete or saturated samples.

- [ ] **[0%] Step 1: Add deterministic rate-controlled workloads.**

  Run below-capacity, near-capacity, and intentional-overload rates with declared duration, requested rate, achieved rate, ring capacity, and operation oracle. Keep the same operation/rate contract across compared policies.

- [ ] **[0%] Step 2: Add efficient run levels.**

  Use `smoke` for one short correctness fork, `development` for five rotated forks, and `decision` for fifteen rotated forks. Reuse one Release native artifact and one cached image; do not rebuild per policy or profile.

- [ ] **[0%] Step 3: Retain collector saturation metrics.**

  Record p50/p95/p99 receipt age, maximum ready records per drain, polls, collector CPU, ring loss, pending and retained high-water marks, terminal drain duration, and producer-closure result.

- [ ] **[0%] Step 4: Encode overload verdicts correctly.**

  Below-capacity and near-capacity decision samples require complete evidence and zero unexplained loss. Intentional overload must produce explicit incomplete evidence with reconciled dropped/lost counts and must be excluded from performance acceptance statistics.

- [ ] **[0%] Step 5: Test interruption and durable partial output.**

  Kill one child, block one sink, and force one retention exhaustion. Each run must retain a terminal failure record or durable partial artifact with its exact exclusion reason.

- [ ] **[0%] Step 6: Run rootful smoke before the decision matrix.**

  Run focused host tests, build the immutable bundle once, execute one rootful smoke matrix, and only then run the fifteen-fork decision matrix.

- [ ] **[0%] Step 7: Commit the fixture separately from measured evidence.**

  Commit implementation as `test(tier-e): add sustained collector decision fixture`; retain generated evidence in a later documentation commit so code review does not mix harness behavior with host-specific results.

Current status: a rootful, receipt-clock-enabled fifteen-fork decision matrix completed with complete evidence in all four policy/mode arms. A blocked-sink terminal fixture now prevents a late downstream commit after terminal cleanup. A rootful child-termination run retained a failure and terminal JSONL record; the harness now classifies the deliberate kill independently of child output. The final retained evidence and the remaining overload saturation dimensions still remain.

---

### Task 6: [100%] Benchmark application-shaped boundary profiles

**Files:**
- Modify: `profiler/src/main/kotlin/io/mazewall/profiler/tierE/stress/TierEMatchedBenchmark.kt`
- Add workload sources under: `profiler/src/main/kotlin/io/mazewall/profiler/tierE/stress/`
- Modify: `profiler/src/test/kotlin/io/mazewall/profiler/tierE/stress/TierEMatchedBenchmarkJsonTest.kt`
- Create during retained run: `docs/internals/evidence/tier-e-boundary-selection-20260919.json`

**Interfaces:**
- Consumes: Full and selected capture profiles, calibrated run levels, baseline mode, and exact operation oracles.
- Produces: paired full-versus-selected application results and operational cost guidance by admitted-boundary rate.

- [ ] **[0%] Step 1: Add three dependency-free application-shaped workloads.**

  Add filesystem metadata/open-close, buffered file copy/read-write, and loopback socket request/response workloads. Each workload declares expected application output, relevant bindings, operation relationships, and background allowances.

- [ ] **[0%] Step 2: Define named profile presets as typed binding sets.**

  Provide internal benchmark presets for filesystem metadata, file I/O, and network I/O. Presets are conveniences over `Selected`; the native agent continues to receive only canonical binding names.

- [ ] **[0%] Step 3: Compare baseline, full, and workload-specific selection.**

  Use three warm-ups, ten measured batches, five rotated development forks, and fifteen decision forks only for candidates whose development confidence interval supports improvement.

- [ ] **[0%] Step 4: Require semantic equivalence.**

  Application checksums/results must match baseline. For admitted bindings, decoded logical stacks, invocation relationships, statuses, loss, and terminal dimensions must match the full-profile arm after filtering full evidence to the declared selection.

- [ ] **[0%] Step 5: Report operational guidance instead of one universal slowdown.**

  Publish added nanoseconds per admitted operation, admitted boundaries per application operation, estimated single-core fraction at declared event rates, workload p50/p95/p99, CPU, peak RSS, and retained bytes.

- [ ] **[0%] Step 6: Accept selection only as an opt-in scope contract.**

  Require a positive paired improvement interval, no correctness regression, and no memory/tail gate regression. Do not replace the default full profile; users must select a narrower capture domain explicitly.

- [ ] **[0%] Step 7: Commit workloads and evidence separately.**

  Commit harness changes as `perf(tier-e): benchmark configurable boundary profiles`; retain measured artifacts with the final evidence/documentation commit.

Current status: the rootful fifteen-fork full-versus-`jdk25-unix-openat0` decision completed. Both profiled arms matched every declared operation and retained complete evidence. The narrower capture domain reduced paired median batch time, but the harness retains binding/count equivalence rather than a cross-JVM logical-frame transcript, so the result is opt-in scope guidance rather than a default change.

---

### Task 7: [100%] Run a scoped-tracepoint feasibility experiment only if node cost remains material

**Files:**
- Create: `docs/internals/backlog/issue-<timestamp>-evaluate-tier-e-scoped-tracepoint-attachment.md`
- Create experiment code only under existing Tier E stress/engine test boundaries after the backlog issue is approved.
- Update: `docs/internals/designs/profiler/tier-e-design.md`

**Interfaces:**
- Consumes: Accepted production collector policy and measured application event rates from Tasks 5 and 6.
- Produces: A keep/reject decision for per-platform-TID perf tracepoint attachment; it does not directly change production.

- [ ] **[0%] Step 1: Gate the experiment on measured node-wide cost.**

  Start only if global raw-tracepoint filtering consumes material CPU at realistic unrelated-host syscall rates. Otherwise record `not_justified` and stop.

Current status: the saved rootful node-noise probe completed five paired runs after an explicit attachment-ready file handshake. Its unrelated-syscall throughput deltas ranged from -59.27% to +11.05%, with a median -5.17%; the signs are mixed and the result does not meet the predeclared >=15% stable-cost gate. Scoped tracepoint attachment is therefore rejected as not justified on this host. The existing global raw tracepoint remains unchanged.

- [ ] **[0%] Step 2: Define thread lifecycle correctness before code.**

  Specify discovery and retirement of JVM platform/carrier TIDs across thread creation, exit, fork, exec, detach, and PID/TID reuse. Missing a live carrier must make evidence incomplete.

- [ ] **[0%] Step 3: Build a benchmark-only attachment path.**

  Keep the current raw tracepoint path unchanged. The experiment attaches the same logical BPF handling through per-TID perf events and preserves marker state, record layout, loss accounting, and collector semantics.

- [ ] **[0%] Step 4: Compare identical host-noise and workload matrices.**

  Measure target workload overhead, unrelated-host CPU, event equivalence, attach/detach latency, TID churn, ring loss, and lifecycle failures.

- [ ] **[0%] Step 5: Apply a strict promotion gate.**

  Require exact event equivalence, complete lifecycle evidence, zero unexplained loss, and at least 15% node-wide CPU improvement with a confidence interval excluding zero. Otherwise remove or retain the path as rejected experimental evidence.

---

### Task 8: [100%] Reconcile design, backlog, evidence, and release guidance

**Files:**
- Modify: `docs/internals/designs/profiler/tier-e-design.md`
- Modify: `docs/internals/backlog/issue-20260918-210822-014-integrate-a-production-owned-tier-e-collector-loop.md`
- Add or update retained artifacts under: `docs/internals/evidence/`

**Interfaces:**
- Consumes: Test names, commands, commits, raw logs, manifests, hashes, and decision outputs from Tasks 2 through 7.
- Produces: Auditable implementation status and operational guidance without overclaiming policy eligibility.

- [ ] **[0%] Step 1: Document implemented contracts separately from experiments.**

  Record the collector service and configurable binding profiles as implemented only after their tests pass. Keep readiness and scoped tracepoints historical/experimental unless their own gates pass.

- [ ] **[0%] Step 2: Publish machine-readable verdict dimensions.**

  Every retained run reports `capture_domain_validity`, `transport_completeness`, `attribution_coverage`, `dictionary_integrity`, `lifecycle_validity`, `runtime_overhead`, `evidence_integrity`, `performance_gate`, and `policy_eligibility=false`.

- [ ] **[0%] Step 3: Close the collector-loop issue only with production ownership and sustained evidence.**

  Require the service lifecycle tests, below/near-capacity complete runs, explicit overload incompleteness, and durable terminal artifacts. A rejected readiness policy does not block closure once continuous production behavior is proven.

- [ ] **[0%] Step 4: Add operator guidance.**

  State that full capture is the safest default, selected profiles reduce total cost by narrowing the declared domain, and exact per-admitted-boundary overhead remains. Include event-rate-based capacity examples from retained measurements, not universal slowdown claims.

- [ ] **[0%] Step 5: Run final verification.**

  Run focused tests, `./gradlew :profiler:test`, native Release CTest, rootful smoke/decision matrices, `./scripts/adkw check-backlog`, and `./scripts/adkw hook`. Save all command logs to files before analyzing them.

- [ ] **[0%] Step 6: Commit documentation and evidence.**

  Commit as `docs(tier-e): retain production readiness and boundary selection evidence` without changing unrelated resolved backlog records.

## Acceptance Criteria

- The reusable collector service owns its thread and terminal state without inferring producer closure from ring emptiness or readiness timeout.
- Full capture remains the default and produces behavior equivalent to the current baseline.
- Configurable selection admits only exact manifest-backed bindings and rejects malformed or unsupported profiles before admission.
- Excluded boundaries preserve native outcomes and never install stale exact attribution; evidence declares their omission as capture-domain scope.
- Every admitted boundary retains exact stack, lifecycle, dictionary, transport, and acknowledgement guarantees.
- Sustained below/near-capacity runs are complete with zero unexplained loss; intentional overload is explicitly incomplete and fully reconciled.
- Application-shaped benchmarks preserve outputs and admitted stack relationships while reporting cost by admitted-boundary rate.
- Readiness, adaptive polling, and scoped tracepoints do not become defaults without their independent decision gates.
- Build/image reuse shortens iteration without weakening clean-source fingerprints, Release native builds, provenance, or retained raw evidence.

## Delivery Order

1. Collector service and lifecycle tests.
2. Native manifest-backed capture profile.
3. Typed Kotlin profile and evidence scope.
4. Sustained collector fixture and development evidence.
5. Application-shaped profile benchmark and decision evidence.
6. Scoped-tracepoint feasibility only if measurements justify it.
7. Design/backlog reconciliation and final verification.
