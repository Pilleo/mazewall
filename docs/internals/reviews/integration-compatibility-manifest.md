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
