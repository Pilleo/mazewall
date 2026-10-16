# Integration contract completion — actual local acceptance

**12 October 2026: locally accepted engineering scope; external release gates remain prerequisites.**
This supplements the [historical acceptance](2026-10-10-integration-final-acceptance.md)
and [compatibility manifest](integration-compatibility-manifest.md); it does not rewrite their outcomes.
The controller performs the final whole-change review after the Task8 report.

## Identities and receipt authority

- Immutable library producer **P = `617fc10d3aa38e50887541da31baa459d6002ef7`**,
  tree `79dde0aabce5af4c1449bed8bbbb1b940e8ddd69`, parent
  `c049dd3cab4e22eee45da919293884ab3624a9fa`.
- Maven group `io.mazewall`, candidate **`0.1.0-candidate.617fc10d`**.
- Checkout `/home/leanid/Documents/code/java/jseccomp/.worktrees/integration-contract-completion-20261011`,
  branch `feat/integration-contract-completion-20261011`.
- **E** is `/home/leanid/Documents/code/java/jseccomp/.superpowers/sdd/2026-10-11-integration-contract-completion/`.
  Repository: `E/task-6c-final/repository/617fc10d3aa38e50887541da31baa459d6002ef7`.
  ROOT-only original plan/design, constraints and receipts stay in their operator custody;
  they are references, not copied checkout inputs.
- Source freeze: `E/task-6d2/continuation-20261012-a1/source-freeze.json`,
  aggregate `00e4539487d66e23b083b0511da0717bba4ee3d72ca1ec13b8d4c01f82ef4725`:
  3,587 entries, including five preserved untracked entries; 3,582 actual committed blobs.
- `E/task-8/` binds the owned patch, per-path hashes, receipt references, strict locks,
  275 frozen repository payloads, unchanged production/build inputs and final checkpoint.
  **The later consumer/evidence checkpoint HEAD is not a new library producer or artifact label.**

Post-P executable fixture changes are exactly JDBC Java/build/README and benchmark
Kotlin/build/README. JDBC imports the explicit Spring bridge and reconciles supported
bounded directory resolution with ungranted/unsupported/stale/wrong-member negatives.
Benchmark aligns the exact seven-GAV published closure and selects the prepared
`bounded-text` isolation group for health/timing. No production algorithm or library
artifact changed. Per-file source hashes are in `E/task-8/baseline.json` and the seal.
Benchmark aggregate is `ca8b54389d78b65d45a5f86daf4dd3dd3f37d68bdefaa26cdccc827e1162cd8f`;
executable JAR `17984fc92a61ae083bb2a3fe8fa6ec924eaa4954bbdca5d935d248b0d0cca9cc`.
That aggregate is the accepted measurement origin. Task8 changes only the README's
archived-issue link afterward; executable Kotlin/build bytes remain identical, and
the checkpoint README hash is separately sealed without relabeling measurement origin.
Current ordinary JDBC JAR is `834a1481560d43a1737579821bf08502a36e845bc80bec693bfe1db95a171aa4`;
Security JAR is `be2cc308b76f9eb9c422149d196ad0c864e7b5f7b5e5f6d29be1944ca2ec9607`.
Old failed/old-JAR receipts retain their labels.

## Actual gates (counts are local to each invocation, never cumulative)

| Gate / receipt under E | Actual result, origin and freshness |
|---|---|
| `task-6d2/continuation-20261012-a1/` combined build and checkpoint review | Two build stages exit0; 3,826 selected cases = **3,814 PASS + 12 skips**, zero failures/errors. **413 fresh = 405 PASS + 8 skips**, 3,413 reused. Coverage/ABI/ratchet/backlog accepted; LinuxNative real selector 59/62 instructions = 95.1613%, unchanged 78% floor. |
| Same producer's normal mandatory hook | Exit0; 3,355 selected = 3,351 PASS + 4 skips; **41 fresh Portal cases**, zero failures/errors/skips. Task8's distinct unchanged-hook checkpoint receipts are in `task-8/`; source hook counts are not relabeled as Task8. |
| `task-6c-final/` core/plugin/Koog, accepted `task-6c-final-review.md` | P617 actual Maven bytes; 2,031 core class bodies, max major66. Stable Kotlin2.4.20, actual JDK22/25 positive probes, seven retired API/type and two scope negatives per language; 15 live guide snippets and three incomplete-conversion refusals per JDK. Published worker/plugin closure and generated classes PASS; **16 fresh native assertion groups**, 27 host / 13 opaque-worker JARs. |
| `task-6c3/`, accepted `task-6c3-review.md` | **Eight finished Boot JVM cases**, four nested MazeWall JARs equal Maven bytes. Reused for unchanged Boot artifact only. `origin-fix/` current JDBC fixture: two measurement JVMs, one bounded-origin-control JVM, two protected native verification JVMs, zero native skips; ordinary and Security exit0. Assertion groups, not manufactured JUnit counts. |
| `task-6c4/group-fix/`, accepted `task-6c4-review.md` | Four actual payload native/validator exits0; 80 mutation controls per payload. **336 ordinary + 40 accepted pressure samples**, 24 pressure rejections; 84 ordinary public timing rows. Separate post-P consumer source/JAR origin. |
| `task-7a/`, accepted `task-7a-review.md` | Eight fresh positive delegated-cgroup cases: actual memory.max268435456/cpu.max50000 100000 and child membership before permit, readiness/guest dispatch and physical end. Repeated fresh owning proof in P617 combined gate; local authority only. |
| `task-7b/`, accepted `task-7b-fix-review.md` | Existing pinned native assets: **5 installed TierE / 7 public HotSpot / 12 asset / 1 attach** proof cases; 45 CTests, six-phase samples and 22 rootful dependency cases separately recorded. Pre-P source-qualified receipts; changed source-transfer helper has five real regressions and a 3,220-entry archive. P617 gate separately executes 7 HotSpot / 22 endpoint cases; no imaginary fresh installed TierE run in that host gate. |

The P617 combined twelve skips remain five privileged installed TierE, two CET,
one deliberately unselected native-refusal, two Landlock assumptions, one non-Linux
control and one gated endpoint fixture. Separate executed asset receipts do not turn
these skipped rows into passes. Optional `detektTooling` actually failed with **87
errors / 36 unchanged files**; it remains active debt, not a mandatory-gate waiver.
The benchmark review's four health guards lack dedicated negative controls (Minor);
present correct guards and actual accepted controls are not claimed exhaustive.

## Original 22-contract reconciliation

| Contract | Actual accepted scope / proof and remaining boundary |
|---|---|
| C0 | P617 published Java/stable Kotlin/JDK22+25 matrix, actual supplied Maven paths, strict locks and primary compiled recipes PASS. Host API compatibility is not JDK22 native proof. |
| C1 | Active deny-all filesystem intent and canonical definitions retained; source gates PASS. Pinned native-asset proof is scoped to its actual images/tools/runtime, not universal native support. |
| C2 | Fixed process/worker construction, immutable definitions/setters and exact positive/negative scope compilation PASS; no filesystem scope promotion. |
| C3 | Required installer/receipt, zero-entry and native quarantine/retirement PASS for applicable Seccomp/Landlock. Positive already-enabled CET remains prerequisite. |
| C4 | Genuine prepared owners, member/origin/generation validation and finished-JAR/Boot negatives PASS. Explicit granted bounded exploded resolution PASS; not installed exploded JDBC, immutable live bytes or race-proof revocation. |
| C5 | Correct coverage-preserving guidance and real USER_NOTIF profile → HOTSPOT_JIT conversion → required install → completed post-install **C1 tier2 nmethod**/lazy work → physical end PASS (`task-5a/`, current P617 owning gate). Empirical selected workload, not all future paths or Native Image hardware proof. |
| C6 | Canonical reviewed artifacts and actual member/classloader/resources, stale/mutated/unsupported/wrong-member zero-entry PASS. External known-fixture authority is not production approval; digest is not authenticity. |
| C7 | Owned synchronous platform workers, finite admission/readiness/nesting/cancel/drain, fatal generation retirement and physical end PASS; no guaranteed termination or heap isolation. |
| C8 | Opt-in core declared metadata capture/snapshot/restoration and registered inline boundaries PASS (`task-3/` plus recovery). No default ThreadLocal transfer; held restoration owns physical end. Security captures context references, not deep immutable Authentication. |
| C9 | Callable facade/primary recipes and complete approved borrowed public API/caller retirement PASS (`task-4a/b/c2/c3/`, P617 positives/individual negatives). Experimental coroutine internal wrapper retained. Custom supervision preserved only within supported early VFORK bootstrap; observed FORK callback gap remains active. |
| P0 | Owned broker/startup/shutdown acquisition, retryable failed-close owners and second-close evidence PASS; no ambient-owner substitution. |
| P1 | Terminal state loss before admission release, no automatic replay/recreation, independent group and explicit new runtime PASS. |
| P2 | Typed prepared asynchronous submit/handle and finite process budgets, cancellation versus physical retirement PASS. |
| P3 | Explicit trusted operator launch preparation, inherited legacy defaults, declared synthetic marker/acquired-descriptor canaries and independent required readiness PASS (`newworkspace/`, `task-7a/`). No full FD/environment secrecy inventory; production launch/cgroup authority prerequisite. |
| P4 | Real published worker/codegen/plugin closure, generated classes and opaque 13-JAR worker materialization PASS; hiding/restoring 12 paths is not access revocation. |
| P5 | Upstream Koog1.3.0 registry `uppercase_ascii`, bounded first-party data, dispatch/denial/bounds/crash/cancel/end PASS. `test-denied` is not advertised; no general hostile/generated-code backend. |
| S0 | Actual owned advice/transaction placement, supported singleton topology and finished Boot proof PASS; no host mocks substituted for kernel proof. |
| S1 | Reviewed synchronous singleton member/origin/resources, required inactive/missing/stale/final/wrong-member zero-entry PASS; approved materialized results only. |
| S2 | Real H2 commit/rollback/read-denial and same-worker JDBC propagation/lifecycle PASS; NIO permission diagnosis remains inferred. |
| S3 | Current exact-coordinate native Security JDBC Admin/Bob, authorization denial/rollback/reuse, business failure/cancel/context restore and physical end PASS. Finished Boot remains Security-absent; no web/JPA/reactive or deep-immutable-auth claim. |
| R0 | P617 local normal publications, strict core/docs/plugin/Koog locks, finished Boot/current JDBC/Security and combined source gates PASS. Exact operator-managed remote repository/independent retrieval remains prerequisite. |
| R1 | Independent prestarted helper same-file positive vs worker denial and late callback across physical end PASS (`task-5b/`); four actual equivalent-work datasets PASS for descriptive harness scope. Full ACE, isolated IPC/native-install/guest-body attribution, exhaustive FD/environment inventory and independent unfamiliar-human exercise are not established. |

## Actual raw observations

Warm submit-to-result-observed medians, **microseconds**, n=20 per cell; each
payload has 94 accepted rows (84 ordinary + 10 pressure), six pressure rejections.

| ASCII bytes | DIRECT | JDK | MANAGED | PROCESS_PORTAL | Public queue | Public transport+worker |
|---:|---:|---:|---:|---:|---:|---:|
| 1 | 13.518 | 37.356 | 753.404 | 915.755 | 3.760 | 715.130 |
| 22 | 16.312 | 69.776 | 1566.477 | 1049.512 | 4.101 | 761.318 |
| 1024 | 110.501 | 204.562 | 1703.529 | 2945.676 | 9.642 | 2254.913 |
| 65536 | 2248.832 | 327.792 | 1285.339 | 1913.982 | 4.291 | 1589.464 |

Lossless raw logs/JSONL, min/median/mean/max, first-measured, preparation/admission,
throughput/resources/drain records: `E/task-6c4/group-fix/payload-*`,
`descriptive-stats.json`, `raw-counts.json`, `matrix-results.json`.
Concurrency1/pressure4 and fixed mode order, coexisting owners, JIT/GC/preload,
validation/hash/logging/proc observations affect results. Public workerExecution
includes transport+worker; serial input-ID association is not wire request-ID/PID
attribution. Pressure timing is unattributed. No isolated IPC/install/guest-body,
attributed guest RSS, statistical significance or negligible-overhead claim.

## Physical proof and remaining prerequisites

Accepted receipts retain actual finite owner argv/log/exit, acquired PID/starttime
generations and cgroup/socket/pump/resource ends: P617 continuation491, final
core/Koog365, current JDBC services15+4+7, final raw46 observed PID generations
(raw also710 thread generations). These are scoped observed identities, not an
exhaustive descendant inventory. Managed drain Complete, Portal child dead and
JDK owner joined are separate from result delivery. Task8 acquires its own fresh
finite delegated hook service/shared lock and records fresh Portal XML/physical end.

- [ ] **CET:** operator supplies a compatible JVM/loader with SHSTK already enabled
  in parent and children. Never late-enable. Under the existing finite lock/service
  workflow run `./gradlew :enforcer:integrationTest --tests io.mazewall.seccomp.IntelCetIntegrationTest --tests io.mazewall.ffi.cet.CetExploitDemoTest`;
  retain runtime/proc feature evidence and actual non-skipped positive receipts.
- [ ] **Production reviewer/launch/cgroup authority:** obtain independent production
  approval of exact implementation/member/origin/resource generation, declared launch
  profile/preparation and delegated root/limits. Use the existing prepared-resource
  review workflow and [delegated fixture commands](../../../portals/runtime/DELEGATED_CGROUP_TEST.md).
  Current external Gradle known-fixture sidecars and local cgroups do not supply approval.
- [ ] **Remote repository authority:** operator provides the exact repository containing
  these unchanged candidate GAV/JAR/POM/module bytes, authentication externally and
  an independently retrieved hash receipt. Existing strict workflow:
  `./scripts/check_integration_consumers.sh --repository <absolute-supplied-repository> --version 0.1.0-candidate.617fc10d --evidence-dir <fresh-owned-evidence>`.
  The script owns its lock; no nested flock. Use retained plugin/Koog/JDBC argv for
  their separate consumers; do not republish or relabel to checkpoint HEAD.
- [ ] **Independent unfamiliar humans:** actual participants run the
  [consumer guidance](../../../tests/consumers/README.md),
  [JDBC/Security workflow](../../../tests/consumers/spring-jdbc/README.md) and
  [benchmark recipe](../../../benchmarks/integration-boundaries/README.md) at the
  pinned repository/version; retain real commands, comprehension/results and cleanup.
  No participant, remote access or production/human approval is invented.

Backlog reconciliation archives only eighteen current fulfilled task/alignment
issues with accepted reviews and real receipts. Optional tooling and a separately
scaffolded unsupported FORK gap remain active. Baseline/operator rows and historical
c049/dc6f/checking locks remain preserved; see `E/task-8/issue-counts.json` and report.
