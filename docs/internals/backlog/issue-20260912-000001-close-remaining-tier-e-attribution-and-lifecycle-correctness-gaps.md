---
title: "Close remaining Tier E attribution and lifecycle correctness gaps from architecture review"
severity: "MEDIUM"
status: "open"
priority: high
dependencies:
  - "issue-20260825-023934-tier-e-wp-04-lifecycle-trust.md"
  - "issue-20260825-023935-tier-e-wp-05-concurrency-stress.md"
  - "issue-20260825-023936-tier-e-wp-06-noise-budget.md"
  - "issue-20260910-000105-bound-tier-e-retention-and-lifecycle.md"
  - "issue-20260910-000101-freeze-profiler-evidence-boundaries.md"
  - "issue-20260910-225629-741-record-review-hardening-liveness-and-boundary-evid.md"
  - "issue-20260912-224643-007-refactor-invocationreplaymachine-to-track-re-entra.md"
  - "issue-20260912-224645-805-extract-tierebpfprogrambuilder-from-tierebpfengine.md"
  - "issue-20260912-224648-231-decouple-dictionary-frame-interning-from-streaming.md"
  - "issue-20260912-232402-204-enforce-re-entrant-path-suppression-and-causal-unc.md"
  - "issue-20260912-232404-647-enforce-process-incarnation-identity-gating-across.md"
  - "issue-20260912-232406-760-differentiate-selected-sys-enter-observations-from.md"
  - "issue-20260912-232409-587-integrate-bounded-dictionary-streaming-watermarkin.md"
target_modules:
  - "profiler"
target_files:
  - "docs/internals/designs/profiler/tier-e-design.md"
  - "profiler/src/main/kotlin/io/mazewall/profiler/**"
  - "profiler/src/test/kotlin/io/mazewall/profiler/**"
  - "profiler/src/main/kotlin/io/mazewall/profiler/tierE/**"
open_questions: false
---

# 🟡 [Severity: MEDIUM]: Close remaining Tier E attribution and lifecycle correctness gaps

**Context:** The prior WP-04/05/06 and retention/evidence work removed the largest Tier E gaps, but the architecture review still leaves several attribution-lifecycle boundaries unbound in one place. This issue captures those remaining correctness edges and binds them to explicit acceptance tests before broader Tier E hardening is considered complete.

**Needed:**

1. Define strict attribution contract: exact-stack attribution is valid **only** when the active native boundary stack is non-reentrant; otherwise emit `UNKNOWN` or a dedicated `causal-uncertain` classification.
2. Make re-entrant path suppression normative: when a supported wrapper calls into Java, the wrapper enters an explicit inactive boundary state before callback-driven JVM work and creates a new exact attribution boundary only on validated re-entry.
3. Add process-lifetime identity gating: track `process_incarnation_id` plus observed exec/exit invalidation and tie it to session lifecycle checks while preserving TGID session filters.
4. Separate syscall-attempt contract from observed syscalls: define capture domain as **selected `sys_enter` program observations**, with explicit behavior for seccomp-denied-attempts and explicit non-coverage of any unobserved attempts.
5. Extend raw-stack cache correctness across class/method lifetime and redefinition: guard symbolization against class unload/redefinition races and remove ambiguity from cache keys across method epochs.
6. Move dictionary transport protocol expectations into protocol: bounded pending definitions, ordered terminal checkpoints/watermarks, explicit incomplete-tail behavior, and dedupe ordering where suppression happens only after successful dictionary edge emission.

## Evidence dimensions (final acceptance criteria)

1. **Capture-domain validity**: each reported event must identify whether it was observed on the selected path (`sys_enter`) and whether it was an allowed observation or an execution-attempt outside the capture contract.
2. **Transport completeness**: pending dictionary definition queue and checkpoints are bounded and all completeness boundaries are explicit.
3. **Attribution coverage**: no stale exact attribution after re-entrant transitions, stale context reuse, or detached session transitions.
4. **Dictionary integrity**: no cross-version or cross-generation method collapse; unresolved symbols must surface as explicit incomplete-tail entries.
5. **Lifecycle validity**: no resumed exact attribution after invalidation, stale process identity rejection, and session terminality.

Failure in any one dimension marks the run incomplete; incomplete runs remain fail-closed and cannot be treated as verified.

## Required tests (architecture test matrix)

1. supported native → Java callback → unsupported native syscall: inner context attribution is explicit (`UNKNOWN`/`causal-uncertain`) and not falsely exact.
2. active outer scope + inner capture failure: no stale-positive exact attribution when wrapper re-entrancy invalidates exact state.
3. mid-scope detach while session alive: attribution is invalidated immediately and no exact events are resumed after callback window.
4. seccomp-denied attempt + active session: output includes boundary limitation and attempt-vs-observation distinction.
5. PID reuse/exec transition stress: stale process identity never admits old contexts and never reopens old session attribution.
6. method unload/redefine races: no invalid method-id dereference and no cross-version symbol collapse in cache.
7. first unique-edge emit fails then repeats: later duplicate edge emission is allowed only after successful first emit.
8. dictionary pressure with long-running workload: bounded memory for pending dictionary entries and explicit incomplete-tail handling.

## Tracking contract

1. Keep architecture unchanged unless a correctness gate requires a re-entry barrier or process-lifetime gate.
2. No kernel dependency additions for this issue.
3. For each required test, record: execution environment, first failing evidence, regression evidence after fix, and the commit that proves pass/fail transition.
4. Link each matrix row to design/protocol text and the corresponding test output.

### Current evidence update

The unique-edge retry boundary is now represented in the profiler collector and design contract:
resolved records remain retryable until downstream retention is acknowledged, and only acknowledged
edges are deduplicated. The focused `AttributionCollectorTest` covers failed-first-emission retry and
post-acknowledgement suppression; commit and durable artifact linkage remain pending until delivery.

The current refactoring sequence also adds transactional dictionary batches (`33c45876`), separate
integrity/edge components (`954d2ccc`), immutable replay state with typed lifecycle views
(`57c42afa`), capture-domain limitation evidence (`3b66e900`), and an isolated dictionary joiner
(`6a978f4b`). Commit `ff58ffff` completes the safe integration boundary: `DictionaryBatchSink` and
the publisher preserve definitions and watermarks across retry, opaque emission tokens prevent
foreign acknowledgements, typed process/session identities reject stale observations, explicit
replay invalidations fail closed, and the native symbolization cache is versioned by redefinition
epoch. Follow-up commit `4d820021` adds the current protocol, terminal-state, ring-buffer,
timeout, metrics, and probe-boundary contracts; native fixture commit `0b9bf45b` adds the
live retransformation, seccomp boundary, and full/unique phase benchmarks. The umbrella remains
open until these outputs are retained as durable rootful-CI artifacts for every matrix row.

### Durable verification evidence

| Dimension | Evidence | Result |
|---|---|---|
| Capture-domain validity | `AttributionCollectorTest.capture domain attempt missed is a limitation and never an exact event` | `PASS` for classification; denied-before-`sys_enter` remains an explicit boundary limitation |
| Transport completeness | `DictionaryBatchPublisherTest.retry keeps the exact batch and watermark unchanged until commit`; terminal and abandoned-tail tests | `PASS` for transactional behavior; incomplete tails remain fail-closed |
| Attribution coverage | callback, inner capture failure, stale identity, and token-ownership tests in `AttributionCollectorTest`/`InvocationReplayMachineTest` | `PASS` |
| Dictionary integrity | native symbolization epoch plus weak unload guards; `agent_redefinition_smoke_test` | `PASS` (`0b9bf45b`) |
| Lifecycle validity | `InvocationReplayMachineTest.detach and exec invalidation require validated re-entry before exact attribution`; PID-reuse test | `PASS` |

Exact commands:

```text
./gradlew :profiler:test
cmake -S profiler-native -B /tmp/jseccomp-profiler-native-build -DCMAKE_BUILD_TYPE=Debug
cmake --build /tmp/jseccomp-profiler-native-build -j2
ctest --test-dir /tmp/jseccomp-profiler-native-build -V
```

### Follow-up integration evidence

Current profiler-native CTest adds `agent_redefinition_smoke_test`,
`agent_phase_benchmark_full`, `agent_phase_benchmark_unique`, and
`seccomp_boundary_fixture`. The profiler test suite adds committed protocol transcripts,
terminal-state rejection, actual mapped-reader BUSY/DISCARD/wraparound coverage, and
bounded concurrent cursor reservation coverage. These artifacts are authoritative when
run in rootful CI; local rootless Podman remains a documented `INCOMPLETE` environment
constraint for privileged BPF fixtures.

The stale `tier-e-proto` and `ebpf-prototype` target paths in the original review have
been normalized to the active `profiler` and `profiler-native` implementation paths.
Resolved Tier E and retention/lifecycle issues remain unchanged. This issue remains
`open` until the eight-row matrix has durable CI artifacts and commit linkage.

### Pass/Fail matrix (to be populated by implementation PR(s))

| Test | Pass criteria | Failure signature | Required evidence |
|---|---|---|---|
| 1 supported→callback→unsupported | exact emission never from stale parent context | stale exact event in unsupported syscall | profiler/eBPF traces + test report |
| 2 outer scope + inner capture failure | explicit unknown/causal-uncertain on inner syscall | exact inner event emitted from outer invocation | test report |
| 3 mid-scope detach | immediate invalidation + no resumed exact events | exact events after detach window | event stream + session state assertions |
| 4 seccomp-denied attempt | denied-attempt surfaced as boundary limitation | treated as captured exact attempt | deny-attempt fixture + output evidence |
| 5 PID reuse/exec | stale incarnation never authorized | accepted stale invocation after exec/reuse | lifecycle assertion + fixture output |
| 6 unload/redefine race | cache key includes method epoch and unload/version guard | invalid method-id exception or cross-version collapse | profiler cache/state report |
| 7 dedupe retry behavior | later emit proceeds after first failure | permanent dedupe block after transient failure | unique-edge replay output |
| 8 dictionary pressure | bounded pending queue + incomplete-tail artifact | unbounded memory / silent truncation | profiler memory + tail artifact |

---
<!-- id: issue-20260912-000001-close-remaining-tier-e-attribution-and-lifecycle-correctness-gaps  file: issue-20260912-000001-close-remaining-tier-e-attribution-and-lifecycle-correctness-gaps.md -->
