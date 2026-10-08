# Independent packaged consumer gates

This separate Gradle build consumes real pinned `io.mazewall:enforcer` and
`io.mazewall:platform` Maven publications from an explicit file repository.
External artifacts resolve from Maven Central. It has no included source builds
or project dependencies; the gates reject dependency edges selecting projects
and require the MazeWall JAR paths to match the supplied repository/version.

The default consumer compiler is **stable Kotlin 2.4.20**, independently of the
producer's **2.4.20-Beta1**. No metadata or prerelease skip flags are used.
`consumerKotlinVersion` accepts only a pinned stable release. `consumerJdk`
selects the compilation and execution toolchain, including diagnostic javac.
Foojay can supply JDK22 if needed.

## Stage and check a new exact revision

From a clean, isolated worktree, record its revision and stage only local Maven
publications. All Gradle operations below acquire the shared fleet lock.

```bash
revision=$(git rev-parse HEAD)
version="0.1.0-candidate.${revision:0:8}"
repository="$PWD/build/fleet-consumer-repository/${revision:0:8}"
lock=/home/leanid/Documents/code/java/jseccomp/.superpowers/sdd/2026-10-06-mazewall-integration-foundation-plan/verification.lock

flock "$lock" ./gradlew --console=plain --no-build-cache \
  --no-configuration-cache --rerun-tasks \
  --init-script tests/consumers/stage.init.gradle \
  -PconsumerVersion="$version" -PconsumerRepository="$repository" \
  :platform:publishAllPublicationsToConsumerBaselineRepository \
  :enforcer:publishAllPublicationsToConsumerBaselineRepository

# Explicitly generate locks for a new coordinate; review the new lockfiles.
./scripts/check_integration_consumers.sh --repository "$repository" \
  --version "$version" --write-locks --evidence-dir "$PWD/build/consumer-lock-generation"

# Acceptance uses the recorded locks without rewriting them.
./scripts/check_integration_consumers.sh --repository "$repository" \
  --version "$version" --evidence-dir "$PWD/build/consumer-acceptance"
```

The script acquires that same lock internally for its entire matrix. It does not
publish anything. The init script adds only the selected file publication target;
the staging commands do not select remote publication or Maven Local tasks.

Each JDK22/JDK25 row cleans both consumer outputs, disables build/configuration
caches, logs the actual compiler and resolved artifact paths, compiles and runs
Java/Kotlin policy construction, independently compiles an **uncast** Callable
lambda, and requires actual javac rejection of process filesystem configuration
and worker-to-process policy assignment. Negative fixtures require exactly one
expected error, so missing symbols/classes cannot masquerade as scope rejection.
Callable entry examples compile but do not install containment or execute work.
Policies use canonical fixed-scope builders and `PolicyState.Uncompiled`.

The version probes resolve upstream Koog **1.3.0** and Boot **4.1.1**, both alone
and with the staged candidate. They log selected versions, including serialization
and compiler-library differences. Graph resolution is not adapter compilation or
framework runtime evidence.

## Recorded candidate and historical locks

The checked-in candidate locks under
`locks/0.1.0-candidate.39cd5ce0/kotlin-2.4.20/` correspond to committed producer
revision `39cd5ce060a5310ec64b42de56873bb85ac6b6e6`. Both JDK rows passed with stable
Kotlin 2.4.20. See the [compatibility manifest](../../docs/internals/reviews/integration-compatibility-manifest.md)
for exact artifacts, commands and limitations.

The original `gradle.lockfile` files preserve the historical
`0.1.0-baseline.ea0a8276`/prerelease-compiler baseline unchanged. New candidates and
stable compiler versions get separate strict lockfiles; do not overwrite the
historical locks to make a new candidate resolve.

Native packaged enforcement, Portal executables, merged-final-source publication,
and framework application behavior belong to subsequent coordinated release
gates. No remote repository availability is established by this local gate.
