# Repository-wide review — 2026-11-04

**Later [recursive follow-up](2026-11-04-repository-review-recursive.md):** 18 completed
reviews with working nested delegation, six additional residuals on three existing
owners, newer passing owner build/native receipts and the small-lockdown release
strategy. The findings and failed build below remain the dated first-pass record.

## Verdict

**Changes required. The current checkout is not release-qualified.** The single full
build failed during supervisor helper prewarming. Independent reviews also found
concrete defects in shared native ownership, owned execution, supervisor routing,
profile completeness and Portal contracts. There is no demonstrated P0 or universal
sandbox escape in this review.

Keep the existing platform/enforcer/owned-runtime architecture. Correct its shared
boundaries, qualify a small explicit artifact/deployment combination, then expand
convenience and performance work. A replacement architecture or generic lifecycle
framework would increase the work without addressing these failures.

- [Detailed finding register](2026-11-04-repository-review-findings.md): triggers,
  source evidence, confidence, corrections, regressions and exclusions.
- [Backlog ownership and dependencies](2026-11-04-repository-review-tracking.md):
  existing owners, new scoped follow-ups and qualification work.
- [Verification and review provenance](2026-11-04-repository-review-evidence.md):
  actual checks, skips, source drift and review limits.

This is review/documentation work, not implementation or approval to publish, merge,
activate a controller, or operate retained recovery sessions. Existing application,
test, build and staged changes were preserved.

**Coverage and tracking:** 23 source-review executions (18 primary lanes, three
coordinator escalations, two successful nested Astra reviews), consolidated into
20 work packages. Created 59 scoped issues and amended 54 existing owners/roadmap
issues. The verified inventory is 325 active (324 open, one deferred) and 739 resolved.
Aliases share ownership; requests that failed to start a child are not counted as reviews.

## Fix first

1. **Make requested restrictions real, with correct shared identities.** Allow-list
   narrowing can report success without installing its denial (BPF-01). Fix Linux
   action precedence (PLAT-08), then its planner/state comparisons (BPF-03). AArch64
   `renameat` is incorrectly absent, dropping an explicit rule (PLAT-01/BPF-02);
   typed descriptor sending forgets the authorized generation (PLAT-03). Correct
   these shared owners before downstream workarounds, preserving native close errors
   and managed-buffer validation in the same first wave.
2. **Make every admitted invocation reach a truthful outcome.** A throwing failure
   classifier leaves its public result unresolved while execution-end and drain say
   success (LIF-01). Plain nested fatal errors can allow reuse (LIF-02). Adapter
   timeouts cannot recover a result handle already lost by the core.
3. **Close false-completeness paths.** Contradictory imported loss/drain facts pass
   complete-profile conversion (EVID-01), and five executor methods run unobserved
   work that becomes a complete empty profile (PN05-02/EVID-02). Fix construction
   consistency before behavior-to-evidence binding (EVID-03).
4. **Correct optional enforcement semantics before promoting those modes.**
   Supervised `openat2` discards invalid size/high flags before effectful execution
   (SUP-03); healthy idle supervision expires (SUP-01); approved accept is denied
   by a route mismatch (SUP-02). Portal's dedicated baseline needs cross-process
   signal isolation (PRT-01), and replacement cleanup must retain its owner (PRT-02).
5. **Repair assurance concurrently.** Investigate the actual helper-prewarm build
   failure, restore intended mutation/ABI/overlay test selection, and fix the
   accepting-survivor KILL_THREAD oracle. A larger green test count on the wrong
   workload would not qualify the other fixes.

The first four streams can proceed in parallel where files/owners are independent.
The numbering is a recommended serial order, not a claim that every later fix must
wait for all earlier work. Native qualifiers follow the relevant shared correction.

## Twenty dependency-aware work packages

Each package groups a coherent delivery boundary; it is not one oversized issue or
permission to exceed the linked issues' scope. **P1** means high-impact correctness
or a blocker for the named artifact; **P2** is an important bounded defect/gap;
**P3** is lower-impact correction. Counts of open high-priority issues are not an
execution schedule.

| Order | Package and findings | Why this order / completion evidence |
|---|---|---|
| W01 | **Shared native facts and ownership** — PLAT-01–08 (BPF-02 aliases PLAT-01) | First: syscall mapping, action order, FD generation, native result and memory-lifetime mistakes propagate to every consumer. Deterministic boundary/fault tests first, then owned native positive controls and the selected architecture. |
| W02 | **Effective policy, receipt and measured-evidence contracts** — BPF-01/03/04/05/06/08, LL-01, LIF-04, EVID-01/03 | Faithfully track effective installed actions before skipping a filter. Use W01's corrected order; validate scope/collections and reject lost required Landlock grants. Evidence consistency precedes binding it to exact behavior. Preserve API compatibility and baseline-before-local-domain constraints. |
| W03 | **Total owned execution and retirement** — LIF-01–03, LIF-Q1/Q2 | Settle results and preserve fatal identity before releasing admission; remove only fully retired heavy generations. Genuine-owner rollback and listener-retirement barriers must establish the advertised drain boundary. |
| W04 | **Supervisor semantics and capacity** — SUP-01–06, BUILD-RUN | Repair idle/frame distinction, composed accept routing and full openat2 validation. Keep helper capacity reserved through failed reap; bound diagnostics. Diagnose the observed prewarm failure independently of these source findings. |
| W05 | **USER_NOTIF admission and lifetime** — PN05-01–06, PN-E2/PN05-07, retention910 | Fix shared receive classification once; cover all executor methods; preserve ACK ordering, startup failure and interrupted-workload ownership. Qualify the concrete bootstrap-directory stack-capture boundary; historical cold-network causality stays open. |
| W06 | **Portal baseline and broker ownership** — PRT-01–04 | Establish signal scope and continuous replacement ownership before restart-budget or shutdown promises. One absolute close budget; FIFO acquisition must reach validation promptly. Actual harmless-signal, replacement and grant tests remain required. |
| W07 | **Generated Portal correctness** — CG01–07, PRT-05/PCG-11, PCG-10 | CG01 → complete CG02 model → CG03 schema identity; CG04 strict decoding → CG05 validation-owned checkout. CG06 bounded writer and CG07 disjoint output ownership are parallel prerequisites for P03. Fix proxy/exception helpers once. |
| W08 | **Tier E native outcome and generation safety** — TE-01–04 | Checked-JNI preservation, allocation rollback and exclusive replacement ownership precede reuse in live-stack Plan B. Reverse-order publication must satisfy the actual Kotlin reader before live-admission measurements. |
| W09 | **Tier E terminal ownership** — TE-05–08 | Existing A1 cleanup owner first, then bounded finish, absorbing shutdown and correctly keyed retry. Retain the historical 71/72 miss for causal investigation; unrelated fixes do not explain it. |
| W10 | **Bounded, conformant HotSpot observations** — HS-01, HS09-01–05 | Bound native decoding and share strict2/observed3 conformance inputs across Kotlin/Go. Preserve readiness, per-occurrence identity, finalization, duplicate-key rejection and autonomous stop deadlines. Public policy ineligibility remains essential. |
| W11 | **Trustworthy check selection and oracles** — BUILD-02–04, TEST-01/02/05/06, BPF-07/09, HS-02 | Start immediately beside W01–10: select actual ABI, mutation, Kotlin buildSrc and overlay tests; reject missing simulator verdicts and child survival/setup failures. Duplicate lane IDs share one owner. Require a failing negative control for each repaired gate. |
| W12 | **Reproducible dependency/artifact closure** — BUILD-01, HS-03/04, PCG-08/09 | Optional producer locks precede consumer checksums. Content-verified prepared sources precede binary/source provenance. Bundle producer limits/cache inputs must match the runtime. Pin/record actual release inputs and approved licensing before reproducible-release claims. |
| W13 | **Framework outcome and dependency lifetime** — SPR-01–03, KD-E3 | Consume W03's reliable fatal/outcome contract. Preserve original Error through failed rollback; prove effective manager selection. Spring timeout policy must retain both runtime and dependencies. Coroutine cancellation failures belong at the structured caller. |
| W14 | **Profiler export and auxiliary routes** — EVID-04–09 | Escape executable DSL literals; hide test-only Java seams; preserve strace descendant ownership, unknown-opcode incompleteness, exhaustion and diagnostic failures. These have separate reachability from core enforcement and should not delay an unrelated core-only closure. |
| W15 | **Honest, physically owned demos** — KD-01/02, KD-E1/E2 | Reuse the corrected outline cleanup pattern, protect post-start acquisition and replace external metadata dependence with an acquired local control. Describe mock calls as synthetic boundary inputs. Qualify existing corrected CLI paths rather than reimplementing them. |
| W16 | **Supported API and deployment contract** — ARCH-01/02/04/05, TEST-04 | Decide necessary public value vocabulary, protect Portal JVM bridges, fix matrix budgets and select one exact deployment tuple. Then approve artifact-specific promotion gates. Preserve current all-artifact requirements until that decision exists. |
| W17 | **Production/diagnostic packaging and maintenance** — ARCH-03/06 | Remove avoidable profiler closure from the production worker/plugin through an approved migration. Keep one collector and separate product adapters; assign overlay ownership, qualification budgets and stop criteria. Final adoption measurements use the resulting artifact. |
| W18 | **Current documentation and local tooling** — DOCS-01–07, TOOL-01–04 | Correct unsafe/stale recipes and authority links. A minimal container context and effective endpoint check precede builds. Retire absent-controller routes before any effect; only then decide whether standalone ingestion merits repairs. |
| W19 | **Measurement validity before optimization** — TEST-03, P01/P10/P11, adoption805 | Reject impossible throughput, then compare equivalent real workloads including codecs, validation, total memory and cleanup. P10 optimizes the same correct writer; P11 remains conditional and may conclude no-go. No speed claim follows from static review. |
| W20 | **Paused recovery authority** — RET-01–03 | Keep separate from shipped library readiness. Extend existing029/877/695 with residual identity, post-validation and phase cases. Context428 has a scoped source correction; this is not foundation/native approval or authorization to resume the route. |

### Actual dependency edges

- W01 native ownership and W03 result/retirement semantics feed supervisor,
  profiler, Portal and adapter qualification; their independent local fixes may
  start sooner.
- PLAT-08 Linux action order → BPF-03 planner/state order → BPF-01 effective
  state/dedup qualification. All three are needed: correcting the rank alone does
  not preserve explicit ALLOW exceptions or force a stronger default to install.
- EVID-01 consistency → EVID-03 exact measured-behavior ownership. The executor
  omission is independent and must be fixed too.
- CG01 has **16 transitive active dependents** in the initial backlog graph;
  CG02 has 12. This supports fixing the generated foundation before conveniences,
  rather than filing a competing implementation roadmap.
- CG02 → CG03; CG04 → CG05; CG04/05/06 + helper corrections → P03; P03 and
  production measurements → batching/stateful/replacement qualification.
- HS-04 prepared-source identity → full HS-03 binary/source binding; HS-02's
  overlay gate must execute the actual patched decoder/lifetime regressions.
- Tier E cleanup405 → shutdown817/terminal716 qualification; TE-01/02/03 →
  live-capture reuse. Snapshot capture alone does not authorize helper effects.
- TOOL-02 retention/removal decision → conditional TOOL-03/04 work. Deletion of an
  unused ingestion route is preferable to building a new parser/recovery framework.
- RET-01 identity-first checks → RET-02 reliable failure post-validation;
  RET-03 additionally needs an installation-phase owner. All remain behind the pause.

## Readiness by artifact

| Artifact/mode | Current verdict | Minimum next qualification |
|---|---|---|
| Platform + static enforcer / owned synchronous operations | **Hold current release claim.** Shared boundary/lifecycle defects plus failed root build. | W01–03, applicable W11, exact packaged Java/Kotlin/JDK and native tuple; genuine startup, denial, overload and physical-retirement evidence. |
| Opt-in supervised enforcement | **Not production-qualified.** Concrete SUP defects and existing live-stack/resource-authority limitations. | W04/05 shared pieces; fresh stack/task/code identity and base-policy/LSM intersection, then native negative controls. |
| Portal runtime/codegen/worker | **Hold general generated-contract and compromised-worker claim.** | W06/07, bundle/ABI/packaging prerequisites and actual generated-child startup, denial, cancellation, close/reap and abrupt-host-death qualification. |
| USER_NOTIF discovery | **Hold complete-profile reliability claim.** | W02/W05; real all-overload observations, interrupted workload ownership, cold stack-enabled JVM and honest retained-loss limits. |
| Tier E | **Specialist diagnostic scope only; current reliability fixes needed.** | W08/09, checked-JNI/allocation/replacement/lifecycle regressions, selected native artifact and causal retained-miss investigation. No policy promotion. |
| Agentless HotSpot / shared bridge | **Useful bounded observation exists; next release held.** | W10–12, selected current native graph and original-attachment final sampling. Kotlin/Go replay is not native or product-persistence acceptance. |
| Spring/Koog integrations | **Hold affected optional contracts.** | W13 and current strict producer/consumer closure; real fatal rollback, context dependency lifetime and cancellation tests. |
| Demos/benchmarks | **Bounded demonstrations, not general production proof.** | W15/19 and exact artifact custody. Current corrected CLI/outline behavior stays credited. |
| Removed orchestrator / retained recovery | **No operational authority inferred. Recovery remains paused.** | Explicit controller disposition; scoped disposable authority regressions and separate authorized native/release gates. |

An optional profiler finding does not automatically block a separately qualified
static-core artifact. **Today's publication graph still couples them**, so separation
requires the existing release-matrix decision and gate tests, not deleting checks
from an all-artifact publish job.

## Architecture, complexity and future changes

- **Preserve useful boundaries:** platform owns native ABI/identity; enforcer owns
  policy and permanent installation; owned invocation handles separate result from
  physical end; Portal owns processes; adapters own framework completion semantics.
  The main defects are gaps between these owners, not evidence they should merge.
- **Represent decisions once:** validated policy/evidence construction, exact
  checkout-through-validation, absorbing terminal states and retained cleanup
  reservations have higher value than a new generic state-machine framework.
  Existing nullable-worker/phase and broker-close-shape refactors remain design debt
  unless an actual failing interleaving is demonstrated.
- **Reduce the active support surface:** production worker packaging, stale operator
  routes and duplicate legacy notification helpers are worthwhile simplification
  targets. Native safety checks, owned descriptors, scoped arenas and explicit
  irreversible outcomes are necessary correctness mechanisms.
- **Future-proof by contract:** independently sourced syscall/ABI checks, a shared
  wire corpus, real emitted-code consumers, unchanged compiled consumers, explicit
  upstream-overlay owners and artifact-specific qualification provide stronger
  protection than percentages or an unbounded “JDK22+/Linux” promise.
- **Performance claims need measurements:** confirmed cumulative retention/copying
  and native decoder growth have concrete mechanisms; overall throughput, tail
  latency, total memory and HTTP comparisons remain workload-specific open evidence.

Three operator decisions remain before implementation/promotion of the affected
contracts: the supported public value-type vocabulary (ARCH-01), first promoted
artifact/deployment tuple and its gate matrix (ARCH-04/05), and replacement or
suspension of start/merge authority after orchestrator removal (TOOL-02). They do
not prevent the independent corrective work listed above.

## Verification summary

The build owner ran exactly once:

```text
LC_ALL=C.UTF-8 ./gradlew build --no-daemon --console=plain
FAILED — :enforcer:integrationTest — 10m20s (wrapper 627.171s)
103 actionable tasks: 22 executed, 81 up-to-date
```

The failed enforcer suite had **198 tests: 195 passed, 1 failed, 2 skipped**.
`OwnedScopingNativeTest` failed in `prepareSupervisor`, before its authority
assertions. The helper-prewarm job deadline expired; the initiating reason remains
unresolved. No unchanged full-build retry or timeout relaxation was performed.

Fresh Portal integration had 42 passes/1 skip; profiler integration had 7 passes/9
skips and fresh-JVM 19 passes/5 skips. All 19 rootful endpoint cases were skipped.
Ordinary host suites and buildSrc verifier outputs were reused. These results do
not establish optional/rootful, AArch64, minimum-JDK native, hosted or release proof.

Isolated current-source/component checks reproduced many individual defects; the
host C ABI comparison passed all 62 observations. Exact limitations and source drift
are recorded in the evidence file, including later concurrent HotSpot production
overlays that require their owner's acceptance. Backlog/reference and review-scoped
whitespace checks pass; whole-checkout whitespace checking flags one concurrent
HotSpot patch line. These documentation checks are separate from the failed build.
