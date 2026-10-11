# Integration foundation — final evidence and traceability draft

**Draft for controller review/application; 11 October 2026.** The filename retains
the plan date. Existing scoped source/artifact reviews accept the recorded engineering
gates below. **Full original-foundation acceptance is BLOCKED by the named remaining
contracts; release prerequisites are not passes. This is not a release-ready claim.**

## Exact producer, authority and evidence

**P = `dc6f7bf44b68f494a4cb609702dc97c72fb892c4`**; integrated tree
`ac4a6a96aa1a7dd47e4aa52d4eb7739c14074150`.
Candidate **`0.1.0-candidate.dc6f7bf4`**, Maven group **`io.mazewall`**.
Acceptance checkout: `/home/leanid/Documents/code/java/jseccomp/.worktrees/fleet-v1-final-acceptance`.
Local repository: that checkout's `build/fleet-consumer-repository/dc6f7bf4`.
Later documentation/lock/issue commits must preserve **P**, not relabel these bytes
or receipts with the metadata commit's HEAD.

Normative contracts are the [original plan](../../../integrations/2026-10-06-mazewall-integration-foundation-plan.md)
and [design](../../../integrations/2026-10-06-mazewall-integration-foundation-design.md),
with the [binding execution constraints](../../../.superpowers/sdd/2026-10-10-integration-foundation-finish/global-constraints.md).
The old inline checkbox ledger is historical, not current completion evidence.

**E** means `.superpowers/sdd/2026-10-10-integration-foundation-finish/verification/`
under the repository root. **D** means that plan's `task-9-draft/`.
Commands are the actual retained NUL-separated `E/<label>.argv`, not reconstructed
from a model answer. [D/gate-receipts.json](../../../.superpowers/sdd/2026-10-10-integration-foundation-finish/task-9-draft/gate-receipts.json)
records each full sourceSHA, argv/cwd/clocks, actual exits, log/argv/reconciliation
SHA-256 and custody. [D/command-ledger.md](../../../.superpowers/sdd/2026-10-10-integration-foundation-finish/task-9-draft/command-ledger.md)
renders those exact commands. The tables below never sum repeated/inherited cases
into a cumulative acceptance count.

Task 9 independently rehashed **3,498** acceptance tracked inputs (including raw
symlink bytes), **32** integrated accepted paths, **3,528 / 305 / 338** Task 5/6/7
receipt entries and **3,287** frozen combined XML/class/exec files. Actual XML
reparsing matches the accepted totals. See
[D/inspection-result.json](../../../.superpowers/sdd/2026-10-10-integration-foundation-finish/task-9-draft/inspection-result.json).
This is receipt verification and named-contract reconciliation, not renewed
whole-repository source correctness/security review.

## Required executed gates

Every source column **P** denotes the full producer SHA above. Artifact keys refer
to the byte table below and [D/artifact-inventory.json](../../../.superpowers/sdd/2026-10-10-integration-foundation-finish/task-9-draft/artifact-inventory.json),
which includes exact JAR/POM/module SHA-256 values for all nine modules.

| Gate / actual command label | sourceSHA | Result / actual executed counts and skips | Log, XML, hash/artifact receipt | Status |
|---|---|---|---|---|
| Integrated source/review custody | P | Accepted 32-path integration; successful mandatory hook/commit retry. First interrupted attempt remains **NO_PASS**, no exit receipt. | `task-4-root-review.md`; `E/task-4-root-accepted-source.json`, `task-4-root-commit.json`. Current source hashes match; not a new hook run. | PASS, scoped |
| Combined `combined` | P | Exit 0; **20 Test tasks EXECUTED**, **627 suites / 3,749 cases = 3,727 PASS + 22 explicit skips**, 0 failures/errors. Selected build: 334 tasks, 232 executed / 24 cache / 78 up-to-date; these are not Test counts. | `E/combined.log`, `.reports.json`, `.reports/`, `.suite-acceptance.json`, `.reconciliation.json`; D parsed suite summary. | PASS for included/applicable gates |
| Fresh kernel/fresh-JVM selections within `combined` | P | Enforcer integration **190**, fresh-JVM **119**; platform integration **1**; Portal integration **33**; profiler integration **14**, fresh-JVM **20**. These are subsets of 3,749, with their explicit feature skips. The 23 accepted correction suite names have **292 cases: 291 PASS + 1 non-Linux-only control skip**, no unexpected required skip. | Same actual argv/log and frozen XML; `E/combined.suite-acceptance.json`, D suite summary. No second suite run. | PASS for applicable corrected contracts |
| Real Docker endpoint within `combined` | P | `:profiler:rootfulContainerTest` **22 PASS / 0 skips**, actual Docker endpoint execution; no endpoint discovery fixture substitution. | Frozen `TierERootfulContainerEndpointIntegrationTest.xml`; rootful receipt in `E/combined.suite-acceptance.json`; Docker **29.9.0**, `unix:///var/run/docker.sock` preflight. | PASS |
| Coverage `coverage-proof` plus configured combined verifiers | P | Exit 0; **35 nonempty execs / 67 real sessions**, 39 native, all inside combined clocks. Unit/combined counters independently reproduce; kernel-only report derived from the same fresh native execs, not another run or added floor. | `E/combined.coverage-proof.json`, `.coverage/`, `coverage-proof.log`, `.reconciliation.json`; hashes of configured class/exec denominator. | PASS |
| Local core `publish-core` | P | Exit 0; 35/35 publication tasks executed; **40 archived repository files**; platform **375/375** and enforcer **648/648** published/frozen classes equal, maximum major 66. No tests in this command. | `E/publish-core.log`, `core-publication-manifest.json`, `core-publications/`; core hashes below. | PASS |
| Core lock generation `core-locks` | P | Exit 0; both actual JDK22/JDK25 consumer rows; three new locks. Java construction, uncast Callable positive, exactly expected two negative diagnostics and stable Kotlin compile/run. No JUnit count manufactured. | `E/core-lock-generation/inspection.json`, per-JDK logs; D four-lock inventory. | PASS |
| Strict core `core-acceptance` | P | Exit 0; same two real rows; no `--write-locks`; bytes/mtime/inode and supplied Maven paths unchanged. No required compatibility skip. | `E/core-acceptance/inspection.json`, `jdk22.log`, `jdk25.log`, `.reconciliation.json`; exact core/lock hashes. | PASS |
| Koog plugin/package/publication `publish-koog` | P | Exit 0; actual export binds **1,292 source entries / 35 plugin JAR digests**; seven local publications, **155 files**. Demo **1 fresh PASS**; Koog **5 inherited UP-TO-DATE PASS** from combined. `portalCheck` executed; 0 owning skips/failures/errors. | `E/publish-koog.log`, `task-6-publication-manifest.json`, plugin properties, owning XML, package checker/bundle. | PASS |
| Koog locks `koog-locks` | P | Exit 0; stable compiler, public submit compile and exact expected nonpublic-constructor negative; one new lock. **27-JAR host / 13-JAR worker** closures independent. | `E/koog-locks.evidence/inspection.json`; candidate lock and module hashes. | PASS |
| Strict Koog `koog-consumer` | P | Exit 0; same public/negative probes, graph/path/lock hashes unchanged; no lock rewriting or required skip. Staging is honestly UP-TO-DATE. | `E/koog-consumer.log`, `.evidence/inspection.json`, `.reconciliation.json`. | PASS |
| Fresh packaged Koog `koog-native` | P | Exit 0; real registry/dispatch/strict JSON/UTF-8/64KiB data/128KiB wire bounds, completed denial, preserved session, terminal crash/state loss/no replay, new-runtime cancellation and wire-reply rejection. **4 actual workers / 17 observed owned identities ended**, **12 hidden paths restored**; primary/cleanup/restoration = **0/0/0**, no required skip. These are asserted scenarios, not fabricated JUnit cases. | `E/koog-native.log`, worker observations, `task-6-native-reconciliation.json`, fresh closure/cache hashes; worker ZIP below. | PASS, native-fixture scope |
| Spring local `publish-spring` | P | Exit 0; four real publication tasks executed, **205 archived files / 164 checksums**; normal integration JAR **34 classes**, starter **0**. Owning compilation reused; no new host-suite execution claimed. | `E/publish-spring.log`, `task-7-publication-manifest.json`; Spring hashes below, core 30/30 versioned bytes equal. | PASS |
| Standalone `boot-jar` | P | Exit 0; **3 actionable tasks executed**, independent Groovy published-coordinate consumer; **31 nested JARs / 12 application classes**; all four nested MazeWall JARs equal Maven bytes. No JUnit cases. | `E/boot-jar.log`, `task-7-boot-artifact.json`, archived whole outer JAR. | PASS |
| Finished executable `boot-native` | P | Exit 0; **8 actual JVM cases**: measure=0, missing-review=1, stale=1, changed-actual-jar=1, changed-template=1, measure-unsupported=0, unsupported=1, verify=0. All reaped, no timeout/required skip; **15 observed owned identities ended**. | `E/boot-finished-jar/` actual argv/cwd/logs/reap receipts; `task-7-native-reconciliation.json`; outer/sidecar/generation hashes. | PASS, external known-fixture scope |

### Exact bytes and stable compatibility

| Artifact (`io.mazewall`, candidate version unless noted) | SHA-256 |
|---|---|
| platform JAR | `e804595b14774f8e497c370463b9d8d320e3117be8990015c2bbb301d8c6b4ad` |
| enforcer JAR | `37f8bb7f62ba2885ff314b903c6a639f542a1938840a497d100b75e995384412` |
| runtime JAR | `23a03fcc2345044a068eff2ec2fdbf31f5d6743b7b94d0eb940d28bfebd75f5a` |
| koog JAR | `f85caebb8823c41fa13f078a1ffeab397ee82cd8c453cccf4fef09ed433a22cc` |
| service-api JAR | `de766942e2839086dc411733fca6f00194522ab2b6ce9bdcc39827e3fe0ec18e` |
| denied-api JAR (test fixture) | `16c32caf5b69cbc64533b1bd0cea5dcd789c7e8c0d2c308dc8ae95f8d728fc0d` |
| koog-contained-tool JAR (native fixture) | `489de785bd904d7d9601d5e0132937767d5e78e851133350d26d8f5bc0321ac9` |
| embedded format-2 worker ZIP | `d34e64cb37cc7f5bd4133f9869f271baf5ad15560820089f2d4db6bfc6424375` |
| mazewall-spring-boot normal JAR | `5435a9a871ca6699d94a7245f15974a23b500f1598916bdd95133097a47b42cd` |
| mazewall-spring-boot-starter normal JAR | `c6deada2fac53b8ea6523dbda77597b128006674616f140f04df23264c6d1aa3` |
| finished report-application.jar (17,219,201 bytes) | `d955be9f3b6566aa1a805a1d8e5bc2e1e0424fbb600c024adcfe5000d8d45d2b` |
| external private fixture sidecar | `8c42071805a2ade379a589be1f0f20ef3d8291401581c17e7bf9e37c8587b811` |

Boot measured resource generation is
`24e80eb2fa8845b1f1a0ba30a5258a10726e9e24b7fe7c9e6538998ff480b757`;
candidate/document digest is
`f46c888a899d901dc23c1b428bb0c190604883ea3923c9e2d2117aa77144ac0f`.
These identities do not supply reviewer authenticity.

Gradle **9.6.1**, producer Kotlin **2.4.20-Beta1**, core/worker serialization
**1.9.0**. Actual stable consumer compiler/build tools **2.4.20**, no metadata or
prerelease skip flags or source substitution. Core consumers use actual Temurin
**22.0.2+9** at `/home/leanid/.gradle/jdks/eclipse_adoptium-22-amd64-linux.2` and
Oracle Graal **25.0.4.1.1+1-LTS-jvmci-25.4-b23** at
`/home/leanid/.sdkman/candidates/java/25.4.4+1-graal`.
Negative diagnostics are exactly one `compiler.err.cant.resolve.location.args`
for ProcessFilesystem and one `compiler.err.prob.found.req` for WorkerAsProcess.
Koog's external constructor rejection is `compiler.err.not.def.public.cant.access`.

Upstream Koog **1.3.0**, coroutines **1.10.2**, host serialization **1.10.0**,
worker serialization **1.9.0**. Boot **4.1.1**, Spring JDBC **7.0.9**, H2
**2.4.240**. Java/Boot stdlib **2.4.20-Beta1** and Boot-selected serialization
**1.11.0** are actual runtime selections, not additional stable-compiler passes.
The [compatibility manifest](integration-compatibility-manifest.md) records this
new coordinate separately from historical candidates.

**Mutable Maven metadata is diagnosed, not a blocker or equal snapshot.** Across
Tasks 5/6/7 all **30/30 versioned core JAR/POM/module/checksum files** remain
identical. Task 6 retains **30/40** inherited full-repository bytes; Task 7 retains
**145/155**. Each republish changes only two unversioned `maven-metadata.xml`
`lastUpdated` clocks and eight corresponding checksums. Task 5 clocks are
`20261011015609` / `20261011015631`; Task 6 both `20261011024231`; Task 7 both
`20261011032229`. Both old/new archives and checksums validate. The accepted
Task 5/6 review's Minor M2 corrects the report's overstrong “unmet invariant” prose;
this draft follows that ruling without rewriting old receipts or restoring metadata.

### Coverage and required skips

Actual INSTRUCTION covered/missed (unit / kernel-only / combined): enforcer
**41,087/8,947; 17,851/32,183; 41,557/8,477**; platform
**19,844/5,908; 5,209/20,543; 22,321/3,431**; profiler
**74,522/23,564; 9,091/88,995; 74,935/23,151**.
Executable CLASS covered/missed respectively: enforcer **407/29; 223/213; 407/29**,
platform **195/55; 47/203; 215/35**, profiler **545/46; 91/500; 547/44**.
XML class-entry counts **596/352/789** are distinct. Unit floors **82%/65%/70%**
and configured combined-native verifiers pass unchanged. Platform combined inputs
retain configured cross-module enforcer execs; kernel-only percentages are
informational, not a newly invented acceptance floor.

| Actual skipped cases in combined | Count | Status / proof boundary |
|---|---:|---|
| CET exploit/positive locking | 2 | PREREQUISITE: compatible **pre-enabled** SHSTK runtime/loader; no late enable |
| Public HotSpot native opt-in | 7 | PREREQUISITE: explicit native enablement and rootful assets |
| Installed Tier E native fixtures | 5 | PREREQUISITE: supplied installed agent/workload fixture parameters |
| HotSpot asset/attach/refusal unit conditions | 4 | Not executed; no installed/native pass from these conditions |
| Landlock assumption-controlled units | 2 | Unchanged unit skips; actual required Linux Landlock cases separately executed |
| Non-Linux-only fallback control on Linux | 1 | Deliberate inapplicable control, not a native pass |
| Root admission-condition fixture | 1 | Empty skipped XML; precise condition-rejection reason unavailable |

All **22** skips retain class/method/message identities in
`E/combined.suite-acceptance.json`. Feature prerequisites remain open only in the
feature gates that require them. The real 22-case Docker endpoint does not enable
or replace installed-agent/HotSpot or CET proof.

## Original contract reconciliation

This table names current implementation/evidence rather than inheriting old
checkbox states. Counts are suite-local subsets of the combined invocation.
**BLOCKED** denotes missing original contract/evidence; it is not a fabricated
failed test. Exact corrective files/interfaces/regressions are in
[D/gap-manifest.md](../../../.superpowers/sdd/2026-10-10-integration-foundation-finish/task-9-draft/gap-manifest.md).

| Contract / findings | Current implementation and executed evidence at P | Residual status |
|---|---|---|
| C0 — A01/A02/A10/A11 | Exact published core Java/stable Kotlin/JDK22/JDK25 matrix, uncast Callable and expected negative scope checks; accountable source/artifact identities. | PASS, scoped consumer baseline; primary recipes handled by C5/C9 |
| C1 — A04 | `FileSystemPolicy`/canonical definitions preserve active deny-all; PolicyIntentContract **5**, native **2**, canonical artifact host **6** / native **1**. | PASS; old construction-model names are historical |
| C2 — A01/A02/A04 | FixedScopeBuilder **7**, immutable definitions, receiver retention/true-false setters; exact external positive/negative compiles. | PASS; no process FS promotion restored |
| C3 — A06/A07 | Captured required installer, RequiredInstallationReceipt **3**, managed/native zero-entry and generation retirement; actual Linux installation cases execute. | PASS for applicable Seccomp/Landlock; CET-positive PREREQUISITE |
| C4 — A03 | `PreparedOperationResources.resolve/claim/validate` pins real acquisitions/runtime/member/generation; resource host **6**, prepared native **7**, DeploymentOrigin host **27** / native **7**; finished Boot identity/template/sibling denials. | PASS for documented bounded supported origins/rights; not immutable-byte/race-proof revocation |
| C5 — A01/A07/A08/A09 | PortalWorkerPolicy **5** proves selected-runtime composition/incomplete refusal; current result-level APIs preserve coverage. | BLOCKED: G-GUIDANCE and actual profile→converted policy→fresh JIT chain G-PROFILE-JIT |
| C6 — A04/A08 | Strict canonical reviewed artifacts, ReviewedOperationArtifact **5**, actual implementation/classloader/member/resource origin and external fixture review; C4/Boot stale/mutated/unsupported zero-entry checks. | PASS, known reviewed-contract scope; digest is not approval |
| C7 — A05/A06 | OwnedRuntime host **18** / native **1**; finite readiness/admission/nesting/cancel/drain/fatal retirement; Boot incomplete→complete physical-end assertions. | PASS, synchronous owned-runtime scope |
| C8 — A05/A09/A12 | Registered same-domain inline transition, cross-domain/self-drain rejection, retained observed/inferred invocation evidence. Explicit Spring per-call context delegation has owning host tests. | PASS for these supported surfaces; broader core declared-metadata snapshot G-CONTEXT BLOCKED |
| C9 — A01/A05/A10 | Actual Callable-only `Mazewall` facade and uncast Java probes pass; supported cause identity retained. | BLOCKED: remaining borrowed/factory migration G-BORROWED and primary owned-runtime recipes G-GUIDANCE |
| P0 | PortalRuntime host **9**, ProcessBroker lifecycle **4**, actual Broker integration **24** and acquired ownership/second-close receipts. | PASS, explicit owned startup/shutdown |
| P1 | Stateful loss published before admission release; terminal/no-replay/native crash, preserved independent group and explicit new runtime. | PASS; no automatic reconstructed session |
| P2 | Public typed prepared submit/handle and finite limits; PortalRuntimeLimits **3**, PreparedPortal native plus cancellation/physical-cleanup cases. | PASS for supported async process ownership |
| P3 | Required generation **2**, parent authorization **3**, stable cache **1**, worker receipt lifetime **1**; packaged worker required readiness and actual native dispatch. | Parent/generation coverage PASS; explicit operator launch environment/FD contract/proof G-LAUNCH-PROOF BLOCKED; production authority PREREQUISITE |
| P4 — A03/A11 | Codegen functional **13**, format-2 opaque bundle, generated dispatchers, verified **13-JAR worker** closure and fresh materialization. | PASS; hiding old pathnames is not revocation of renamed backups |
| P5 — A12 | Upstream Koog tool/actual registry `uppercase_ascii`; host adapter **5**, fresh packaged allowed/denied/bounds/crash/cancel/retire probes. Fixture `test-denied` is not advertised. | PASS for bounded first-party native-fixture contract; P3 residuals retained |
| S0 — A12 | AdvisorPlacement **25** owning host cases, actual worker/transaction/advice observations; finished Boot singleton/advisor preservation and real platform body from virtual caller. | PASS, narrow supported/rejected advisor topology; no host mock as kernel proof |
| S1 — A12 | OperationBinding **13**, lifecycle **2**, actual reviewed member/origin/resources, required inactive/missing/stale/final unsupported zero-entry checks. | PASS, synchronous singleton approved materialized results |
| S2 — A09/A12 | JdbcTransaction **31** and reviewed binding **1**; seven real H2 propagation modes, same-worker inline transitions/outcomes; finished Boot actual commit/denial/rollback and external file effects. | PASS for supported synchronous JDBC; inferred permission failures remain inferred |
| S3 — A12 | SecurityDelegation **3**, SecurityJdbcPlacement **4** in owning 92-case Spring suite; optional conditional Security bridge/starter. Finished executable Boot is actual Security-absent `java -jar`, not raw target/classpath substitute. | PASS for owning host/finished Security-absent Boot scopes; no new exact-coordinate native Security claim |
| R0 — A10/A11 | Same P local normal publications, stable matrix, strict Koog consumer/opaque worker and independent finished Boot; optional combined graph actually executed. | Local engineering gates PASS; operator-managed remote repository PREREQUISITE |
| R1 — A09/A12 | Real boundary denials, preopened-FD/native resource examples, shared-heap/external-effects/trusted pre-body initialization, honest cancellation/state-loss/drain and JDBC outcomes. One raw Portal smoke record retained. | These boundary receipts PASS, scoped; independent-helper/late-async examples G-BOUNDARY-PROOF and full equivalent-work raw overhead G-RAW-OVERHEAD BLOCKED; independent human exercise PREREQUISITE |

Thus Gate A retains C5/C8/C9 residuals, Gate B retains applicable foundation/P3
residuals, and Gate C has scoped synchronous/JDBC/finished-Boot acceptance rather
than inheriting unimplemented contexts/borrowed surfaces or optional native Security.
Every A01–A12 finding is mapped above; none disappears to make a gate look complete.

## Task 8 applicability and process custody

Latest root guidance and [testing strategy](../designs/core/testing-strategy.md#build-and-evidence-gates)
say the full build already selects integration/fresh-JVM suites; equivalent host
and `run_tests.sh` commands are alternatives, not extra completion gates. The
actual combined task/fixture/coverage/Docker receipts satisfy the applicable
full-build/kernel/endpoint requirement. No distinct required alternate container
environment or contract lacking evidence was identified. **Task 8 requires no
redundant broad replay.** This does not convert any skipped feature prerequisite
or G-PROFILE-JIT/G-LAUNCH-PROOF into a pass.

All twelve long command labels ran in finite transient systemd USER services:
**1200s command TERM / 10s KILL**, service **RuntimeMaxSec=1230,
TimeoutStopSec=10, KillMode=control-group**. Actual exits/observers are 0, owner
PID/starttime identities and owned workloads ended, service cgroups absent.
Shared `flock` was held; consumer/native scripts with internal locks were not
nested under it. Regular maximum observed gaps: Task 5 **15.686036s**, Task 6
**15.121s**, Task 7 **15.045723s**; native observers **0.053s / 0.054070s**.
Collected service `Result=success` defaults are not proof. Unowned editor LSPs
were preserved, not signaled. Koog's observer is not the child's waitpid owner;
Boot's direct JVM reaping and Java thread joining are distinct receipts.

## Carried review findings and parked proof limits

- **Fixed 1.5s delayed-event Minor remains open.** A bounded fixture-only wait-entry
  handshake is the precise follow-up in `buildSrc/.../RootVerificationFunctionalTest.java`.
  Passing runs do not prove deterministic finalizer-wait entry. Event ordering relies
  on pinned Gradle 9.6.1; the production 60s wait is an event-delivery bound, not a
  hard collector deadline. Collector snapshots are self-only, not failed-worker stacks.
- Task 1's incidental fixture/formatter/native setup execution remains a process
  deviation; setup-only graphs must be inspected before launch. Historical assignment,
  model/skill provenance and metadata-only ignored/operator-file preservation retain
  their controller-transcript/metadata proof limits; credential contents are not sought.
- Task 3's final-static **74.246682s** observation gap is a recorded cadence deviation.
  Native Proxy **TERM143** proves graceful reap/stdout end, not that its forced-KILL
  branch ran. Exact expired/sub-second readiness and injected new-owner cleanup-adapter
  failures were not separately executed. Shared verifier controls do not prove every
  new adapter path or every descendant that reparents between observations. The
  intentional TSYNC-disabled unrestricted-sibling control is not a denied sibling.
- The profiler routing-prose Minor is corrected: `ProfilerStressTest` has its own
  **direct** `@NeedsFreshJvm`, not an inherited base-class tag. Historical failed/wrong
  routing receipts remain historical. Task 4 private report's stale “root=BASE” prose
  does not override actual preserved parent **58747460f194e8f3d1feba978b6ab722853386cb**.
- The first Task 4 root attempt remains interrupted **NO_PASS**. Its coarse Bash
  scan cannot prove historical universal workload absence; collected service state
  cannot reconstruct historical effective properties/every descendant's pre-release
  reap. The successful retry's actual exit/hook/Git/journal supplies scoped acceptance.
- Koog worker 648689 was sampled only in startup Seccomp mode 0; the unchanged passing
  required probe supplies readiness/call evidence, not an invented mode-2 sample.
  Other process security samples are not per-thread attestation or individual child
  exit codes. Hiding/restoring 12 pathnames does not revoke source/renamed-backup access;
  unchanged conflict/signal/SIGKILL recovery-fault suites were not freshly replayed.
- Boot uses an external private **known-fixture** review, not production signing or
  application self-approval. Security-absent synchronous non-web acceptance does not
  extend to HTTP/Security/JPA/network/hostile-code features. Generic denials remain
  `INFERRED_PERMISSION_FAILURE`. Digests/O_PATH pin identity/snapshots, not immutable
  loaded bytecode or revocation of preopened FDs/mappings/shared heap/sibling writes.
- The newest Broker60 extension remains preserved **unverified**, SHA-256
  `95ebfc2904305635d80f11f41b46df70a3fec10294702d1da114e23066993c0e`.
  Current Broker/native gates expose no missing required proof needing it. It is not
  imported, accepted as tested, or turned into a mandatory speculative extension.

## Remaining gates and metadata application

| Gate | sourceSHA / command/result | Evidence / actual count | Status |
|---|---|---|---|
| Original missing recipe/migration/context/launch/profile/boundary contracts | P, named source inspection; no source fix or regression run in Task 9 | G-GUIDANCE, G-BORROWED, G-PROFILE-JIT, G-CONTEXT, G-LAUNCH-PROOF, G-BOUNDARY-PROOF in D gap manifest | BLOCKED |
| Positive CET / installed Tier E / public HotSpot | P, selected suite conditions in combined | Actual **2 / 5 / 7** feature skips, plus four HotSpot unit conditions; no fabricated positive artifacts | PREREQUISITE |
| Production reviewer/launch profile and required resource-control authority | P artifacts; approval not performed | Known-fixture sidecars and parent permit are narrower evidence | PREREQUISITE |
| Remote repository verification | P exact candidate; no remote command | No operator-managed remote receipt or credential handling | PREREQUISITE |
| Independent human usability | P guidance/candidate; no human exercise | No participants or results invented | PREREQUISITE |
| Equivalent-work raw overhead | P; one executed packaging smoke sample only | Cold **598ms**, warm **57,815µs**, payload **22 bytes**, RSS **201,404,416 bytes**; XML hash in D raw smoke receipt. No baseline distributions/preparation/admission/drain measurements. | BLOCKED for full R1; feasible local follow-up |

The controller receives exact candidate compatibility append/target, four new
coordinate-namespaced locks, and closure notes/diffs for the four brief-owned
fixture-quality issues. A separate fifth failure-triage closure is evidence-supported
by accepted real first/reused-cache RED/GREEN/collector controls, with the delayed
fixture Minor retained; its current root index registration is absent and belongs
to the controller. No earlier/unrelated operator issues are automatically archived.
All historical native/build failures keep their original source labels and outcomes.

Task 9's ignored review package contains actual target paths, preimage/postimage
hashes, owned issue/index hunk proposals and validation receipts. It makes no root
README/backlog/index/HEAD/config change, commit or publication. Controller review,
mixed-index integration and normal identity/hook commit remain separate actions.
