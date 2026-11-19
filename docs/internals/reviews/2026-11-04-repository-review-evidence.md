# Repository review — verification and provenance

Date: 2026-11-04. Companion to the [repository review](2026-11-04-repository-review.md), not a release certificate or implementation report. **The one full build failed; the initiating supervisor-startup cause remains unresolved.** Documentation/backlog validation is recorded below, including an unrelated whole-checkout whitespace diagnostic.

**Later evidence:** the [recursive follow-up](2026-11-04-repository-review-recursive.md#newer-hotspot-implementation-and-owner-evidence)
records a separate owner-produced passing build and bounded native observation,
with checked receipt/source/artifact identities and explicit qualification limits.
The failed invocation below was the first review's run, not the latest known build.

## Scope and source identity

The reviewed object was the dirty checkout on `feat/kubescape-agentless-hotspot`, anchored at **`b2c9b335720fd0491a8d86917a3b0593b818a4ec`**, not that commit alone. The initial status had 312 entries, including user-owned staged deletions of the orchestrator. Existing modified, staged, untracked and explicitly plan-owned ignored sources were in scope; deleted code was not restored or assumed to ship. No review finding authorizes implementation, cleanup, a new control plane, worktrees, publication or privilege changes.

The initial tracked/nonignored manifest contains **4,247 file hashes** (recorded `2026-11-03T23:26:30.367073Z`). The supplementary retained-plan manifest contains **22,409 Markdown/Python hashes** under `docs/superpowers` and `.superpowers/sdd` (recorded `23:35:30.656264Z`, excluding build/cache trees and following no symlinks). These are two differently timed inventories, with overlap, not one immutable snapshot. Neither the approximately 260k-line orientation inventory nor these hashes proves every line/file was semantically reviewed. Ignored plans/recovery code received targeted review, not exhaustive historical certification.

### Point-in-time drift check

A documentation-only helper independently rehashed both manifests, compared current Git tracked/nonignored filenames with the initial inventory, and rescanned the bounded retained Markdown/Python roots. Receipt time: **`2026-11-04T00:05:02.461771Z`**; HEAD still matched the anchor. Review-family reports and current/new backlog documents are excluded from application-drift classification. No secret contents were inspected; no source was restored, changed or executed by this helper.

- Initial inventory: **4,243 unchanged; four modified test-overlay files; no missing initial files**. No additional existing production/build source drift was found in that inventory.
- Current Git inventory adds **four test-overlay files** outside review/backlog documentation; no other new non-documentation source was found.
- Retained inventory: **22,408 unchanged; one changed progress document; no missing initial files**. Three new retained documents were observed. This is documentation drift, not additional application-source drift.
- Thus **eight application/test-source paths differ from the initial inventory: four modified and four new**, all mirrored HotSpot definition-accounting tests. No test/compilation of those edits is claimed here. The checkout remained active: a third new retained document appeared between the first and second inventory runs. These counts are a timestamped receipt, not a freeze guarantee.

Below, `E` means `experiments/hotspot-agentless/patches`, `P` means `profiler/hotspot-agentless/patches`; each paired row identifies two separate files. Hashes are SHA-256 prefixes; full values are retained locally.

| Paths | Initial → observed hash | Applicability |
| --- | --- | --- |
| `E/gadget-additions/pkg/symbolizer/otel/definition_drain_test.go.txt`; `P/gadget-additions/pkg/symbolizer/otel/definition_drain_test.go.txt` | `9af6760b4295` → `4a3af40db879` | Added sampling, readout-window, failure and concurrent-stop fixtures |
| `E/profiler-additions/tracer/definition_drain_test.go.txt`; `P/profiler-additions/tracer/definition_drain_test.go.txt` | `a29be2dc1313` → `613e65287c64` | Added closed-source and sticky-failure fixtures |
| `E/gadget-additions/pkg/operators/ebpf/definition_readout_authority_test.go.txt`; `P/gadget-additions/pkg/operators/ebpf/definition_readout_authority_test.go.txt` | absent → `2e896dc9e04f` | New readout-authority/native-join fixtures using substituted resources |
| `E/profiler-additions/tracer/definition_accounting_test.go.txt`; `P/profiler-additions/tracer/definition_accounting_test.go.txt` | absent → `ce2031c331ca` | New layout, conservation, binding and cumulative-reader fixtures |
| `.superpowers/sdd/2026-11-03-hotspot-definition-accounting/progress.md` | `120861baa7c9` → `35ad40cece87` | Concurrent issue042 progress, not executable source |
| Same retained directory: `counter-regression-{fix-brief,implementation,review}.md` | absent → separately hashed locally | New issue042 work records, not independent repository-review lanes |

Read-only Git diffs of both modified overlay kinds, inspection of the new canonical test fixtures, and the owning progress record bound these changes to existing **issue042 definition-accounting work**. Canonical/experiment pairs had identical observed hashes. The new files' presence is not proof that the root Go command selects dependency-overlay tests (HS-02 says otherwise), nor acceptance of current native output. Lane 08's authored-overlay identity and historical accepted patch identity must not be relabeled as identities of these later bytes. Findings about unchanged production paths retain their source basis; any claim about the edited tests, new patch closure or current bundle requires owner requalification. All external work was preserved.

### Coordinator checkpoint — later production drift

At **`2026-11-04T00:19:59.521876Z`**, a second read-only inventory still found the
same HEAD, but concurrent issue042 work had advanced into production overlays.
The earlier test-only receipt remains historical; it is not the final source state.
Local receipt: `coordinator-source-comparison.json`, including full hashes.

- Initial inventory: **4,180 unchanged; 55 review-owned backlog/index documents
  changed; 12 other files changed; none missing**. Those 12 comprise eight mirrored
  production-overlay paths and four test-overlay paths.
- **Eight new non-review source paths:** four production additions and four tests.
  Thus **20 source/test paths differ from the initial inventory: 12 production and
  eight tests**. Canonical/experiment pairs matched at this checkpoint.
- Retained inventory: **22,408 unchanged; one modified progress document; six new
  documents; none missing**. The new stage1 brief and fix reports are concurrent
  owner records, not additional repository-review executions.

For each following suffix, both `profiler/hotspot-agentless/patches/` and
`experiments/hotspot-agentless/patches/` contain a separate production path:

| Production path suffix | Initial → coordinator hash |
|---|---|
| `profiler-additions/tracer/definition_drain.go.txt` | `395d50859a7a` → `a870260c9a0b` |
| `profiler-additions/tracer/reader_health.go.txt` | `835ccb2fecc77` → `89b11ed06b5b` |
| `profiler-auxiliary-ownership.patch` | `4e5df4cef43a` → `6ac27374f0c82` |
| `profiler-definition-drain.patch` | `4eee99f164f1` → `5151a3417406` |
| `profiler-additions/libpf/trace_definition.go.txt` | absent → `3e3dc5cf3741` |
| `profiler-additions/tracer/definition_accounting.go.txt` | absent → `510d0dec820f` |

Targeted inspection of these diffs, the new source contracts and the owner's
progress/stage1 brief identifies source-side binding/reconciliation, cumulative
reader accounting and close/drain guards. This is an **in-progress implementation**;
it has no new independent acceptance from this review. The owner's native/readout
integration and behavioral/native checks remain separate checkpoints. The two
Gadget test kinds also changed after the helper receipt: drain tests now
`ce444b1de023`, readout-authority tests `ff5a518e1301`; tracer test hashes remained
as recorded above. No application source was edited by the repository review.

HS-01's decoder, HS-02's root test-selection wiring, HS-03/04's packaging/preparation
paths and the cited Kotlin/bridge finding paths retain their inspected source basis.
Do not extend that conclusion to the new accounting/finality implementation or
derive current bundle/native qualification from the earlier build. Its ownership
and acceptance stay with [issue042](../backlog/issue-20261005-193140-042-prove-selected-hotspot-event-conservation-and-all-.md).
The active checkout may change again; these are timestamped applicability limits.

## Review execution and coverage provenance

**23 source-review executions:** 18 primary lanes (01–18), three coordinator escalations (19–21), and two successful nested Astra reviews. Most other nested-child attempts failed the harness's depth-1 limit and supplied no execution evidence. Coordination records contain the full primary/escalation session IDs. Models below are the recorded review roles, not a claim of independent runtime verification.

All lane names in this table refer to local Markdown reports under `/tmp/opencode/mazewall-review-2026-11-04/`. They are provenance labels, not version-controlled attachments. Actionable findings/source references belong in the durable main/detail/tracking reports; loss of the temporary directory must not erase the actionable review.

| Execution / model | Source coverage | Actually executed evidence and limit |
| --- | --- | --- |
| 01 `01-platform.md` / Astra max | FFM ownership/layouts, syscall metadata, deadlines, reactor | Current-source Kotlin pure probes; 224-entry architecture audit; freshly compiled C oracle and Kotlin layout comparator (62 values); JDK-only address extraction. No native prctl, AArch64 or descriptor-reuse race |
| 02 `02-policy-bpf.md` / Sol max | Policy/builders, composition, BPF/verifier/simulator, planner/state, Landlock | Existing-output probe followed by 18-source policy/BPF overlay; 20 asserted defective outcomes. Portal signal ALLOW verdicts independently checked at policy layer; no signals sent or native bypass run |
| 03 `03-enforcer-lifecycle.md` / Sol max | Owned runtime, receipts, rollback, generation lifecycle | Java probe against existing main/test outputs, mocked native install, actual Java worker joins. Fresh Kotlin overlay compile timed out at 60s with no output: **not** a successful current-source compile |
| 04 `04-supervisor.md` / Astra max | Authority helpers, protocol/routing, supervisor lifetime | Fresh channel/extractor/pool sources with mocked I/O or boundary stubs reproduce idle timeout, flag/size loss and retained-capacity escape. No native helper/open effect; usable report survives later continuation failure |
| 05 `05-profiler-notif.md` / Sol max | USER_NOTIF transport, listener, admission, ACK/lifecycle | Fresh `Profiler.kt` dispatch probe with counting installer stubs; five admission forms miss install. Kernel/JVM claims separately source-checked, not live reproduced |
| 06 `06-profiler-evidence.md` / Sol max | Evidence derivation, collectors, DSL/Java boundary | Fifteen current source inputs compiled with existing support jars; fake-tool/late-child and Java probes. Metadata-skip/internal opt-in constrain compiler claims; no actual strace/ptrace containment acceptance |
| 07 `07-tier-e.md` / Astra max | Native capture/JNI, attribution, symbol replacement, Kotlin retirement | C++20 witness using current production header confirms two replacement-state schedules. No agent load, checked-JNI, allocator fault or JVM replacement race |
| 08 `08-hotspot-native.md` / Astra max | Go/eBPF overlays, decoder, packaging/preparation provenance, selection | Copied-byte decoder tests and tiny local replacement-module canary; expected explicit canary failure proves selection property. Canonical `go list -m` fails local toolchain floor (1.26.8 versus ≥1.26.10). No current root race/native/package acceptance |
| 09 `09-hotspot-host.md` / Sol max | Kotlin host reducer/parser/process lifecycle and shared Go bridge | Sixteen current Kotlin host/model/parser files plus target compiled; malformed-terminal/timer fake-process probes. Selected bridge `bundle`/`wire` tests pass. No full bridge race/hard-limit or native capture |
| 10 `10-portal-runtime.md` / Astra max | Broker/worker, grants, signal policy, retryable cleanup | Actual existing runtime handler/JDK proxy reproduces Object-name overload loss; bytecode branch source-cross-checked. 123 scoped source hashes unchanged. No fresh runtime Kotlin build, worker/signal/FIFO test |
| 11 `11-portal-codegen.md` / Sol max | Schema/codecs, generated dispatch, packaging task, Portal roadmap | Current-source codegen/codec compile with resolved Kotlin 2.4.21; emitted-source/malformed-input probes; unmodified bundle action invoked without Gradle caching. Existing runtime-handler overload probe is separately bounded. No TestKit/cache or real-worker qualification |
| 12 `12-spring.md` / Sol max | JDBC fatal identity, SmartLifecycle, retained S0 manager selection | Ten current adapter files freshly javac-compiled; real Spring 7.0.9/H2 2.4.240 in-memory host probes, both supported manager kinds and plain/diagnosed Error controls. Existing core support jars; no native generation-reuse test |
| 13 `13-koog-demos.md` / Sol medium | Koog adapter and non-Spring demo contracts | Source/tracking review only; no provider/network/compiler/test run. Lifecycle questions escalated to 19 |
| 14 `14-build-ci.md` / Sol max | Gradle/buildSrc, workflows, locks/checksums, consumers, publication | One failed full build (below); static metadata/selector audit, strict duplicate-key YAML parse, official wrapper checksums, fresh-result retention checks. No optional resolution, PIT or hosted publication |
| 15 `15-tests-benchmarks.md` / Sol max | Gate selection, mutation targets, native oracle, benchmark validator | Static selector audit; copied kill-oracle control-flow probe; actual existing benchmark validator accepts impossible duration while 80 controls still pass. Not a native kill test or measured production throughput |
| 16 `16-docs.md` / Sol medium | Public/internal guidance, plans, backlog references | 1,309-document bounded link inventory; 85 focused public links/anchors; initial 1,005-issue/index inventory. No rendered site/external-link or every-document semantic certification |
| 17 `17-tools-hygiene.md` / Sol medium | Scripts, removed control plane, container context, retained recovery entry | `bash -n` on 29 scripts/hooks; AST parsing of three scoped tooling Python and five retained files; pure inventories. No script/hook/control-plane/recovery execution or secret inspection |
| 18 `18-architecture.md` / Sol max | Artifact/API edges, release sequencing, deployment envelope | Connected source and static six-module dependency/API inventory only; no resolved publication closure or binary-upgrade run |
| 19 `19-koog-lifecycle-escalation.md` / Sol max | KD-E1/E2/E3 retirement, acquisition rollback, cancellation | Current relevant lifecycle/adapter sources compiled with explicit scaffolding, actual invocation handle and virtual-thread interruptible-channel witness; coroutine 1.10.2. Not upstream Koog registry or native worker acceptance |
| 20 `20-retained-authority-escalation.md` / Astra max | Ignored plan-owned recovery authority, provider/member/phase seams | Nine T files/original R AST/hash comparison; isolated Python 3.14.4 equality/identity probe. Recovery sources never imported/executed; retained/live/native authority unqualified |
| 21 `21-profiler-liveness-escalation.md` / Astra max | Kernel RECV cancellation/restart and JVM stack-before-ACK | Upstream source/contract checks and bounded in-memory six-token request model; no native/runtime test. E2 addendum supersedes earlier missing-proof disposition |
| Nested `05-astra-notif.md` / Astra max | PN-E1/E2 only | `ses_e5c3c1e11ffeacxdKP74dFs7aP`; source-authority review, no tests. Rejects O_NONBLOCK-only hang and blanket native-blocking premise; identifies specific boot-directory cycle |
| Nested `02c-astra-landlock.md` / Astra max | Landlock ABI8 sibling-domain/TSYNC reachability | `ses_e5c329238ffe5bbCJbhfUY4cQg`; source/docs/hash review only. No new valid-scope supported-path TSYNC bypass; raw-scope conditional recipe attaches to BPF-05/existing issue687 |

The later documentation helpers—tracking session `ses_e5c231e86ffeEympaIqCvRi6Lm` and this evidence helper—are **not additional independent source-review coverage**. No further delegation or Gradle/test/native operation was performed by this helper.

### Escalations that remain bounded or unresolved

- Supervisor lane 04 produced a usable report, but its requested follow-up continuation failed the harness cybersecurity gate. This is a harness limit, not a completed independent/native investigation. **LIF-Q2** (whether core complete drain includes supervisor watcher/listener physical retirement) and the full-build initiating cause remain unresolved. Coordinator reinspection of accept/openat2/source contracts adds source corroboration, not a new native run or a complete-retirement proof.
- **PN-E2 disagreement is reconciled by lane 21's addendum, lines 373–485**, agreeing with the nested Astra review: an unwarmed bootstrap-only class in an appended **directory** outside canonical bypass roots can open while `_thread_in_vm`; stack acquisition before ACK waits for a handshake-safe target while that target waits for the kernel reply. This is a conditional source-confirmed cycle/session-failure schedule, **not runtime reproduction or permanent JVM deadlock**. ZIP entries and ordinary FFM/JNI transitions are not interchangeable counterexamples. Installed vendor/update behavior, timing and historical failure causality remain unqualified.
- Landlock's ABI8 caller-domain broadcast semantics do not establish a bypass with a well-formed public process policy. The raw-definition/copy/base loophole supplies a separate conditional source recipe already owned by BPF-05/issue687. No native denial/readback sequence was executed.
- Portal signal exposure is source/compiler-confirmed; actual delivery still depends on credentials, PID visibility and outer policies. Native signal acceptance remains unrun. Native wrong-FD writes, allocator termination, file truncation and target resource corruption were not experimentally established by the isolated probes.

## The one full-build checkpoint

Owner: lane 14 only. Exact command, run **once**, without `--continue`:

```text
LC_ALL=C.UTF-8 ./gradlew build --no-daemon --console=plain
```

Start **`2026-11-03T23:31:02.338357Z`**; finish **`23:41:29.509159Z`** (November 4 local time). Wrapper wall time **627.171s**; Gradle **10m20s**, **103 actionable tasks: 22 executed, 81 up-to-date**, configuration cache reused. **Exit 1**, failed `:enforcer:integrationTest`. No unchanged full-build retry was run.

### Failure chain, not an invented diagnosis

Failed case: `io.mazewall.enforcer.api.execution.OwnedScopingNativeTest.captured custom authority permits exact stack and denies lookalike on real owned worker()`.

The fresh case fails during `Supervision.prepareSupervisor()` at `OwnedScopingNativeTest.kt:80–85`, **before captured-authority/domain assertions**. The isolated child exits 2; its measured case duration is **13.3s**. The daemon fatal chain is `SocketCleanup.await` → `SocketHelperPool.prewarm` → `SupervisorDaemonEngine.run`: **“Socket helper cleanup timed out”**, then daemon exit 1. Prewarm shares a **five-second startup deadline** across helper jobs (`SocketHelperPool.kt:61–74`). `ChildStartupTransaction.kt:62–73` subsequently reports missing live readiness using its generic **“within 30s”** message. That message is not proof of a 30-second elapsed wait. Full log lines **602–647** retain the chain; lines **905–919** retain the failed gate and timings.

**Why a helper missed the shared budget remains unresolved.** No per-helper completion/native scheduling evidence selects a cause. Do not call this a permission-prerequisite failure, imply EPERM/EACCES can be relaxed, guess a remedy, extend deadlines/retry/skip without investigation, or attribute it to the older same-class admission-limit bug whose execution-end correction is present. This failure establishes neither an authority-test assertion failure nor a containment bypass.

### Fresh XML observations

Invocation token: `693a7e36-5189-42c4-a94b-c6a565ce11cb`. Lane 14 retained XML with task membership and mtime within the invocation window; this helper independently parsed those retained copies and matched all six JSON totals. This is evidence validation, not rerunning tests.

| Task | Cases | Passed | Failed | Skipped/aborted |
| --- | ---: | ---: | ---: | ---: |
| `:enforcer:integrationTest` | 198 | 195 | 1 | 2 |
| `:platform:integrationTest` | 1 | 1 | 0 | 0 |
| `:portals:runtime:integrationTest` | 43 | 42 | 0 | 1 |
| `:profiler:integrationTest` | 16 | 7 | 0 | 9 |
| `:profiler:integrationTestFreshJvm` | 24 | 19 | 0 | 5 |
| `:profiler:rootfulContainerTest` | 19 | 0 | 0 | 19 |

The health listener's disabled-class-container totals are not interchangeable with XML testcase totals. Enforcer skips concern CET prerequisites (one has no XML reason); Portal requires an owned delegated cgroup-v2 unit; profiler fresh-JVM skips name missing rootful/native fixtures. Nine public HotSpot and all nineteen rootful endpoint cases are disabled without XML reason text. **Zero executed rootful assertions is not rootful acceptance.**

- Ordinary host suites, both root consumer harness suites and the exhaustive buildSrc **Java verifier were UP-TO-DATE**, not freshly executed. Most compilation/static analysis reused outputs. BuildSrc's three ordinary Kotlin JUnit tests were **not selected** by the explicit bridge; reuse and absent selection are different facts (BUILD-04).
- Root CI-policy/source guards, backlog validation, warning ratchet, API/ABI and package-topology actions did execute. Their presence in local `build` does not close the CI selection omission (BUILD-02).
- Enforcer `integrationTestFreshJvm`, complete enforcer/platform coverage/aggregates, root `mergeCheck`/`build`, later checks and `verifyGradleReproducibility` were **not reached**, not passed or deliberately skipped. Profiler combined/kernel coverage completed with reused ordinary-unit data.
- Scoped build/buildSrc/workflow/lock/metadata hashes matched before/after the run. This never established global source immutability; lane 14 already observed the two mirrored gadget drain-test edits during its invocation.

Preflight: Linux **7.2.11-x64v3-xanmod1**, x86_64/glibc2.43; Oracle GraalVM JDK **25.0.4.1.1**, Gradle **9.8.2**, compiler catalog **Kotlin 2.4.21**. C compiler/Clang BPF target and local Docker socket existed; Podman did not. Review process/selected Java had no effective/file capabilities; Yama `ptrace_scope=1`, `unprivileged_bpf_disabled=2`, `kptr_restrict=1`. These facts are not successful native probes or the established cause of startup failure. No capability/sysctl repair, cache clearing, formatting, hooks, dependency regeneration, publishing or test bypass was performed.

## Source/version authorities and evidence limits

These authorities were consulted by the named lanes; this companion did **not** refetch documentation:

- Platform/policy: official Linux **v6.6/v6.18** arm64/syscall/FS tables; versioned Linux **7.0** Landlock ABI8 documentation. Native notification reviews examined pinned Linux **v5.0/v5.5/v5.15/v6.18** and man-pages; supervisor openat2/ADDFD review used **v6.12**. These are scoped contract authorities, not running-image provenance.
- JVM: Java SE **25** FFM/launcher and JNI/JVMTI contracts; pinned OpenJDK **`jdk-25+36`** and **`jdk-22+36`** handshake/VM-entry/directory-loader sources. Neither upstream source matching nor isolated installed-vendor probes qualifies every vendor/update, GC, launch mode or JDK22 native deployment.
- Build/codegen: Context7 supplied Gradle **9.6.1**, not checkout **9.8.2**. Exact official 9.8.2 lifecycle/locking/`Classpath` contracts and distribution/wrapper SHA endpoints supplied version-specific evidence. All three wrapper JARs/pins matched; approval inventory covered **1,754 root / 305 buildSrc artifacts**, with 305 shared approvals agreeing. This is not independent provenance verification of every dependency.
- Spring: Context7 **7.0.5** supplied general lifecycle semantics; exact official tagged **7.0.9** source and cached **7.0.9** runtime libraries were used for actual adapter probes. H2 was **2.4.240**, in-memory only. Coroutines: rolling Context7 docs were not called pinned **1.10.2** evidence; tagged source/cached 1.10.2 JAR established the cancellation behavior.

Fresh isolated compilation is identified per row above. Most standalone Kotlin probes used installed **2.3.20**, with existing support outputs and, where recorded, metadata-skip/friend/internal opt-ins; lane 11 used resolved **2.4.21** without metadata-skip/source repairs. Successful current-vendor standalone compilation is **not** the project's Gradle compiler/ABI/full quality gate. Existing-bytecode probes (especially 03, 10, 15 and initial 02) corroborate source branches but do not certify the current full output closure. Expected defect/canary assertions passing means the defect was observed, not that production acceptance passed. Timed-out/setup-failed compiler attempts remain failed attempts.

Still unrun/unestablished: optional Spring/Koog strict-lock resolution and copied-consumer approvals after the Kotlin upgrade; independent current JDK22/25 packaged consumer matrix; complete native/race/Go scanner and release-asset acceptance; PIT class inventories/mutation workload; hosted Actions/resource-memory behavior; publishing/JitPack; CET-enabled loader, delegated cgroup/OOM and rootful endpoints; AArch64 native syscall verdicts; JDK22 native install/liveness; checked-JNI/allocator/FD-reuse/retirement races; external recovery/guest/session/provider authority; benchmark/performance claims. A default full build does not select all these gates even if green. Mutable Actions/image inputs and intentionally eligible no-scan security-job results remain limits, not evidence of compromise or a vulnerability scan.

No new universal escape, actual resource corruption, permanent deadlock, complete installation retirement, all-source profiling completeness or production-readiness conclusion follows from this review. Source-confirmed defects, bounded probes, historical successes and outstanding qualification remain separate states.

## Historical credit and local receipt custody

Preserve implemented fixes and their historical records: immutable BPF proof/x32/payload/JVM-floor corrections, denied secure-exec cleanup, captured supervisor backend, prepared-retirement late-failure sampling, SpotBugs auxiliary truncation/regression, independent-consumer deadline correction and the scoped retained-persistence fixes are not automatically reopened by unrelated residuals. Source implementation may still await qualification/archival approval; open issue status alone does not refute implementation, and source inspection alone does not close an issue.

Durable historical authorities, **not current green receipts**:

- [BPF generation hardening, November 3](2026-11-03-bpf-generation-hardening.md).
- [Portal codegen hardening, November 3](2026-11-03-portal-codegen-hardening.md) (including its historical 36 focused passes).
- [Profiler route/repository review, November 3](2026-11-03-profiler-route-and-repository-review.md).
- [Integration compatibility manifest](integration-compatibility-manifest.md) and [integration contract completion](2026-10-12-integration-contract-completion.md).
- [HotSpot public native attempt](2026-10-23-hotspot-public-native-attempt.md), [runtime qualification](2026-10-23-hotspot-runtime-qualification.md), and [persisted-profile acceptance](2026-10-24-hotspot-persisted-profile-acceptance.md).

Raw logs, XML copies, repro code, source/dependency manifests and lane reports remain **local receipts under `/tmp/opencode/mazewall-review-2026-11-04/`, not version-controlled attachments**. No portable retention beyond this directory is promised. New helper receipts: `provenance-source-comparison.json` (full initial/final drift and new-file hashes), `provenance-xml-verification.json` (all retained XML hashes/recount), `provenance-artifact-sha256.txt` (lane/build/manifests), and the read-only comparison script `provenance_inventory.py`.

Key SHA-256 receipts:

| Local artifact | SHA-256 |
| --- | --- |
| `initial-manifest.json` | `b310e069c7d00f973071d97d04871a633ce3c0193cb3ce585b8444f2bd8ee894` |
| `retained-plan-source-manifest.json` | `21ff306c4d43fb496aa5cdbe57b895d000d4e4ad12e1ca6d50a165c53c40c4cc` |
| `14-build-ci/full-build.log` | `a98ce5918258dfb42a5144d3526b587bad7ff824967f6b45283558082db35283` |
| `14-build-ci/result.json` | `4ab1b7be9178a5a2d0850640a9acede6e994832ecd6097a4dc0d15307fedd413` |
| `14-build-ci/build-evidence-summary.json` | `4ca2ceb851abf21602c20198affa34f04af50532659c5cd075a25347997f1b98` |
| `04-supervisor.md` | `12317799aa5b754a7751ed903d118b099af40337c8a37fbc42ade18f501f0142` |
| `21-profiler-liveness-escalation.md` including addendum | `df6cbe47f9eed763ebd7a1df775e9e13fc2f80173b5068303ed2efe6b681aa26` |

## Final documentation validation

Helper-only validation passed: nine local Markdown links resolve, all seven key SHA-256 receipts match, final newline/no trailing whitespace, and report-only `git diff --no-index --check` emitted no diagnostics (ordinary no-index difference exit 1). Receipt: `provenance-document-validation.json`. These checks neither validate the coordinator's later files nor execute application tests.

Coordinator outcomes after integration:

- **PASS:** `./scripts/adkw check-backlog`; independently reconciled **59 new
  issues**, **51 existing finding owners plus three roadmap acceptance updates**,
  20 packages and the **325 active / 739 resolved** inventory. New priorities are
  19 high/P1, 39 medium/P2 and one low/P3; no historical priority sweep or closure.
- **PASS:** local Markdown file/anchor checks for all four reports, all new issues,
  new existing-owner addenda and the whole backlog index. Expanded source prefixes
  and explicit line ranges resolve; the external decoder is identified by its
  locked module/revision. New YAML, canonical IDs, index rows and nine added hard
  dependency edges reconcile. Roadmap acceptance links remain distinct from those edges.
- **PASS:** review-scoped `git diff --check`, explicit newline/trailing-whitespace
  checks for untracked reports/new issues and all addenda, and
  `git diff --cached --check`.
- **Whole-checkout `git diff --check`: exit 2**, reporting space-before-tab at
  `profiler/hotspot-agentless/patches/profiler-definition-drain.patch:92`. This is
  concurrent issue042 patch work outside the documentation review; it was preserved.
  The diagnostic is not a claim that the nested patch is semantically invalid.
- The tracking helper separately noticed pre-existing malformed YAML indentation
  around `target_symbols` in archived issue580. It was preserved; the actual ADK
  gate above passed. Focused YAML validation is not a strict-parser certification
  of every historical archive file.
- Post-helper production drift received the timestamped applicability addendum
  above. Raw receipts remain local, and all review documentation is uncommitted.

Final local validation receipt: `coordinator-document-validation.json`. No further
Gradle run was performed. Startup root cause, concurrent implementation acceptance
and the explicitly unrun qualification remain open.
