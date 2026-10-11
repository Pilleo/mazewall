# C0/R0 packaged consumer compatibility manifest

## Identity and gate result — 2026-10-08

- Producer/source base: `39cd5ce060a5310ec64b42de56873bb85ac6b6e6`.
- Worktree: `/home/leanid/Documents/code/java/jseccomp/.worktrees/fleet-20261008-consumers`;
  branch `fleet/20261008-consumers`.
- Candidate: `io.mazewall:{platform,enforcer}:0.1.0-candidate.39cd5ce0`.
- Producer compiler: **2.4.20-Beta1**, unchanged; publication JVM attribute **22**.
- Independent consumer compiler: **stable 2.4.20**, including actual resolved
  `kotlin-compiler-embeddable`, build-tools and runner artifacts at **2.4.20**.
- Wrapper: **Gradle 9.6.1** (embedded Kotlin **2.3.21** is not the consumer compiler).
- Actual launcher/daemon: Oracle GraalVM
  `25.0.4.1.1+1-LTS-jvmci-25.4-b23`, Linux amd64.

| Actual selected toolchain | Java construct/run | Uncast Callable | Process filesystem rejection | Worker/process type rejection | Stable Kotlin compile/run | Locked upstream/candidate graphs |
| --- | --- | --- | --- | --- | --- | --- |
| Temurin **22.0.2+9**, Foojay cache | PASS | PASS | PASS | PASS | PASS | PASS |
| Oracle GraalVM **25.0.4.1.1+1-LTS-jvmci-25.4-b23** | PASS | PASS | PASS | PASS | PASS | PASS |

JDK22 location: `/home/leanid/.gradle/jdks/eclipse_adoptium-22-amd64-linux.2`.
JDK25 location: `/home/leanid/.sdkman/candidates/java/25.4.4+1-graal`.
Java diagnostic processes print these runtime versions themselves. Both consumer
compilation tasks executed from cleaned private outputs with the build cache off.
No required compatibility test was skipped. Resource `NO-SOURCE`, lifecycle
`UP-TO-DATE`, and Kotlin configuration-error-task `SKIPPED` (no configuration
errors) are not skipped consumer tests.

**Stable 2.4.20 accepted the actual Beta1-produced metadata.** No metadata-skip or
prerelease-skip flags, metadata rewriting, source substitution, remote publication,
or copied main-worktree build outputs were used. No compiler change in core is
needed on this evidence. Older stable compiler versions are not established.

## Actual resolved graphs

| Configuration | Kotlin stdlib | Kotlin reflect | Serialization core/JSON | Coroutines | Framework |
| --- | --- | --- | --- | --- | --- |
| Java standalone candidate | 2.4.20-Beta1 | — | 1.9.0 | — | — |
| Stable Kotlin standalone candidate | 2.4.20 | — | 1.9.0 | — | — |
| Koog upstream baseline | 2.3.10 | 2.3.10 | 1.10.0 | 1.10.2 | agents-tools(-jvm) 1.3.0 |
| Koog + candidate | 2.4.20-Beta1 | 2.3.10 | 1.10.0 | 1.10.2 | agents-tools(-jvm) 1.3.0 |
| Boot upstream baseline | — | — | — | — | BOM 4.1.1, Spring 7.0.9 |
| Boot + candidate | 2.4.20-Beta1 | — | **1.11.0** | — | BOM 4.1.1, Spring 7.0.9 |

Stable consumer compilation's internal build-tools classpath also selects reflect
1.6.10 and coroutines 1.8.0; these are compiler implementation dependencies,
not the consumer application or Koog runtime graph. The upstream/candidate probes
are separate configurations, not source adapters. Their successful resolution does
not prove Koog execution, Spring advisor ordering, JDBC behavior, or serialization
ABI compatibility inside framework applications. Those remain adapter/release gates.

Original baseline locks are preserved byte-for-byte. Candidate locks are in
`tests/consumers/locks/0.1.0-candidate.39cd5ce0/kotlin-2.4.20/` and enforced strictly.

## Reproduction and evidence

Use [the consumer README](../../../tests/consumers/README.md) for revision-derived
local staging and the strict-lock matrix. Recorded staging used:

```bash
flock /home/leanid/Documents/code/java/jseccomp/.superpowers/sdd/2026-10-06-mazewall-integration-foundation-plan/verification.lock \
  ./gradlew --console=plain --no-build-cache --no-configuration-cache --rerun-tasks \
  --init-script tests/consumers/stage.init.gradle \
  -PconsumerVersion=0.1.0-candidate.39cd5ce0 \
  -PconsumerRepository="$PWD/build/fleet-consumer-repository/39cd5ce0" \
  :platform:publishAllPublicationsToConsumerBaselineRepository \
  :enforcer:publishAllPublicationsToConsumerBaselineRepository

./scripts/check_integration_consumers.sh \
  --repository "$PWD/build/fleet-consumer-repository/39cd5ce0" \
  --version 0.1.0-candidate.39cd5ce0 \
  --evidence-dir "$PWD/build/fleet-consumer-evidence/matrix-corrected"
```

Evidence lives privately in the assigned worktree:

- `build/fleet-consumer-evidence/stage.log`: initial staging (platform FROM-CACHE;
  superseded as final proof by no-cache staging).
- `build/fleet-consumer-evidence/stage-no-cache.log`: producer staging, both core
  compile tasks executed, successful in 1m18s.
- `build/fleet-consumer-evidence/jdk25-stable-red.log`: first independent stable
  attempt was successful, despite the diagnostic filename; generated candidate locks.
- `build/fleet-consumer-evidence/matrix/jdk{22,25}.log`: failing fixture assertion
  counted the consumer graph root as a project dependency; corrected to inspect
  selected dependency edges. No producer incompatibility was implicated.
- `build/fleet-consumer-evidence/matrix-corrected/`: successful matrix;
  `commands.log`, `jdk22.log`, `jdk25.log`, `gradle-version.log`,
  `source-revision.txt`, `worktree-status.txt`, `artifact-sha256.txt`.

The script owns the shared verification lock throughout its checks, cleans the
consumer outputs per JDK and records exact commands/artifact paths. New coordinates
require explicit lock generation; acceptance does not rewrite locks.

| Artifact | SHA-256 |
| --- | --- |
| platform JAR | `e804595b14774f8e497c370463b9d8d320e3117be8990015c2bbb301d8c6b4ad` |
| platform POM | `f90e5cd86031bdbf2e27e3ec567a6d2cabb40e64fa9695ac94548bb9ec0d4931` |
| platform module | `3eab9e97377b387857f342770691b2dafc2d3f39201404071d85a01263e31797` |
| enforcer JAR | `c8cb1ef818196f7f2e9126e20eca7a2d01578ebd2b023e3a05bec4f5b5a7a53a` |
| enforcer POM | `f09c3bb4f669a2769014576c17460077e922900ab4cf4a7fc12c1c40183ce244` |
| enforcer module | `b3ce72093bbeae246ef5c115fdcecfd9f71c140b01dfa5cd171d0b0d9f1a96b6` |

## Remaining coordinated acceptance

This closes the independent Java/stable-Kotlin/JDK22/JDK25 host compatibility
fixture for the committed candidate. Callable examples are compiled, not executed
under containment. Packaged native Seccomp/Landlock/USER_NOTIF proof requires a
coordinator-granted kernel lock window and belongs to subsequent release scope.
Final merged-source staging, optional adapter/application gates, Portal executable
packaging, external repository availability and human usability remain separate
release acceptance. There is no blocking stable-metadata compiler failure here.

## Exact integrated candidate — 2026-10-11 (`dc6f7bf4`)

This entry supplements the historical `39cd5ce0` entry above. Its tested producer
is **`dc6f7bf44b68f494a4cb609702dc97c72fb892c4`**, candidate
**`0.1.0-candidate.dc6f7bf4`**, group **`io.mazewall`**. Later metadata commits
must not relabel the producer SHA or artifact coordinate.

- Acceptance checkout: `/home/leanid/Documents/code/java/jseccomp/.worktrees/fleet-v1-final-acceptance`.
- Supplied repository: that checkout's `build/fleet-consumer-repository/dc6f7bf4`.
- Actual producer: Gradle **9.6.1**, Kotlin **2.4.20-Beta1**, core serialization
  **1.9.0**, JDK22 bytecode/variant floor (maximum class major **66**).
- Independent stable compiler/build tools: **2.4.20**, without metadata/prerelease
  skip flags, sibling builds, project/source substitution or Maven Local/remote publication.

| Actual selected JDK | Java construct/run | Uncast Callable compile | Exactly expected negative scope errors | Stable Kotlin compile/run |
|---|---|---|---|---|
| Temurin **22.0.2+9**, `/home/leanid/.gradle/jdks/eclipse_adoptium-22-amd64-linux.2` | PASS | PASS | PASS | PASS |
| Oracle Graal **25.0.4.1.1+1-LTS-jvmci-25.4-b23**, `/home/leanid/.sdkman/candidates/java/25.4.4+1-graal` | PASS | PASS | PASS | PASS |

Both lock generation and subsequent strict acceptance actually execute both rows.
`ProcessFilesystem` yields exactly one `compiler.err.cant.resolve.location.args`;
`WorkerAsProcess` exactly one `compiler.err.prob.found.req`. Callable is uncast.
Selected compile/runtime core JAR paths and hashes are the supplied Maven bytes.
These host API checks are not native containment proof on JDK22.

| Inspected graph / artifact use | Kotlin stdlib | Coroutines | Serialization | Framework / result |
|---|---|---|---|---|
| Java core consumer | 2.4.20-Beta1 | — | 1.9.0 | Real Java API probes |
| Stable Kotlin core consumer | 2.4.20 | — | 1.9.0 | Actual stable compile/run |
| Upstream Koog graph | 2.3.10 | 1.10.2 | 1.10.0 | agents-tools(-jvm) 1.3.0 |
| Koog + candidate graph | 2.4.20-Beta1 | 1.10.2 | 1.10.0 | Same upstream 1.3.0; graph is not native execution |
| Packaged independent Koog consumer | actual inspected candidate graph | 1.10.2 | host 1.10.0 / worker 1.9.0 | Stable compiler 2.4.20; real `uppercase_ascii` registry/native gate PASS |
| Boot upstream graph | — | — | — | BOM 4.1.1, Spring 7.0.9 |
| Boot + candidate graph / finished Java-Groovy application | 2.4.20-Beta1 | — | **1.11.0** | Boot 4.1.1, JDBC 7.0.9, H2 2.4.240; finished executable native gate PASS, Security absent |

The Koog consumer has **27 host JARs** separate from the **13 opaque worker JARs**,
with no Koog/Spring/host `TextTools` guest leakage into the worker. Its public submit
compile passes; arbitrary prepared-operation construction rejects with exactly
`compiler.err.not.def.public.cant.access`. The published native-fixture package
indexes `bounded-text` and `test-denied`, while the actual advertised registry
contains only `uppercase_ascii`. No production-fixture or hostile-code claim follows.

| Exact local artifact | JAR SHA-256 |
|---|---|
| platform | `e804595b14774f8e497c370463b9d8d320e3117be8990015c2bbb301d8c6b4ad` |
| enforcer | `37f8bb7f62ba2885ff314b903c6a639f542a1938840a497d100b75e995384412` |
| runtime | `23a03fcc2345044a068eff2ec2fdbf31f5d6743b7b94d0eb940d28bfebd75f5a` |
| koog | `f85caebb8823c41fa13f078a1ffeab397ee82cd8c453cccf4fef09ed433a22cc` |
| koog-contained-tool (native fixture) | `489de785bd904d7d9601d5e0132937767d5e78e851133350d26d8f5bc0321ac9` |
| mazewall-spring-boot | `5435a9a871ca6699d94a7245f15974a23b500f1598916bdd95133097a47b42cd` |
| mazewall-spring-boot-starter | `c6deada2fac53b8ea6523dbda77597b128006674616f140f04df23264c6d1aa3` |
| finished report-application.jar | `d955be9f3b6566aa1a805a1d8e5bc2e1e0424fbb600c024adcfe5000d8d45d2b` |

POM/module and service/denied API hashes are in the source-bound Task 5/6/7
publication manifests and the Task 9 artifact inventory. Worker ZIP digest:
`d34e64cb37cc7f5bd4133f9869f271baf5ad15560820089f2d4db6bfc6424375`.
All **30/30 versioned core files** remain byte-equal across publications. Two
unversioned metadata clocks and their eight checksums change on each required
republish; archives retain each snapshot. Whole repository snapshots are not equal;
the accepted review treats this as a diagnosed index-clock difference, not a binary
identity blocker or justification to rewind metadata.

### New strict locks only

| Target path (under `tests/consumers/`) | SHA-256 |
|---|---|
| `locks/0.1.0-candidate.dc6f7bf4/kotlin-2.4.20/java-api.lockfile` | `2a38ff960d2d568854b2aeeb9c1b1a660e527a9d6a71f61458a90a0d6c44839e` |
| `locks/0.1.0-candidate.dc6f7bf4/kotlin-2.4.20/kotlin-api.lockfile` | `8f52cb1c579f497965d30f7b0d3e79be4f4a9dd9e6e9d181de1f8407502ff1fb` |
| `locks/0.1.0-candidate.dc6f7bf4/kotlin-2.4.20/mazewall-packaged-consumers.lockfile` | `7912d15ac1ccf9f6d2dab1329a62472d39fdb8ee69b5ee9812c64db52513dca6` |
| `koog-portal/locks/0.1.0-candidate.dc6f7bf4/gradle.lockfile` | `da700b0b2d277308af0e193f661bb083d2b41b0b954fe3228f87257cb551deab` |

Strict acceptance omits `--write-locks` and preserves each new lock's bytes/mtime/inode.
All **seven historical locks** retain their bytes. No new Spring lock is invented:
the finished application is the standalone Java/Groovy consumer, not another stable
Kotlin compiler matrix. The Koog lock-location init selects only the independent
consumer's candidate lock destination; applying these locks does not change that
consumer's default historical lock configuration. Exact reproduction uses the
recorded init and argv, not an implied permanent build-wiring change.

### Receipts and acceptance boundary

Retained workspace: `.superpowers/sdd/2026-10-10-integration-foundation-finish/`.
`task-5-report.md`, `task-6-report.md`, `task-7-report.md`, accepted
`task-5-6-review.md` and `task-7-review.md` bind actual argv/exits, XML, all Maven
archives/checksums, selected paths, process identity/physical end and exact producer.
The [final acceptance draft](2026-10-10-integration-final-acceptance.md) reconciles
full original contracts and all skips/proof limits.

The once-run combined gate has **20 fresh Test tasks**, **3,749 cases: 3,727 PASS,
22 explicit skips, zero failures/errors**. Actual rootful Docker endpoint suite:
**22 PASS / 0 skips**. Packaged Koog native assertions and all **eight** finished
Boot positive/negative JVM cases pass at their recorded inputs. Neither framework
graph resolution nor inherited UP-TO-DATE suites are presented as fresh native proof.

Positive CET/pre-enabled runtime and installed Tier E/HotSpot asset/native opt-ins
remain feature prerequisites. Original guidance/borrowed/context/profile-backed-JIT/
launch environment-FD and complete raw-overhead residuals, production external
review/launch approval, remote operator-managed repository and human exercises
remain explicit. No complete foundation or release-ready claim is made here.
