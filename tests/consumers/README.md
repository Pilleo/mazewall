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
lock="$(git rev-parse --path-format=absolute --git-common-dir)/mazewall/verification.lock"
mkdir -p "$(dirname "$lock")"

flock "$lock" ./gradlew --console=plain --no-build-cache \
  --no-configuration-cache --rerun-tasks \
  --init-script tests/consumers/stage.init.gradle \
  -PconsumerVersion="$version" -PconsumerRepository="$repository" \
  :platform:publishAllPublicationsToConsumerBaselineRepository \
  :enforcer:publishAllPublicationsToConsumerBaselineRepository

# Explicitly generate locks for a new coordinate; review the new lockfiles.
./gradlew consumerCheck -PconsumerRepository="$repository" \
  -PconsumerVersion="$version" -PconsumerWriteLocks=true \
  -PconsumerEvidence="$PWD/build/consumer-lock-generation"

# Acceptance uses the recorded locks without rewriting them.
./gradlew consumerCheck -PconsumerRepository="$repository" \
  -PconsumerVersion="$version" -PconsumerEvidence="$PWD/build/consumer-acceptance"
```

The Kotlin test acquires that same `flock` lock internally for its entire matrix. It does not
publish anything. The init script adds only the selected file publication target;
the staging commands do not select remote publication or Maven Local tasks.

Each JDK22/JDK25 row cleans both consumer outputs, disables build/configuration
caches, logs the actual compiler and resolved artifact paths, compiles and runs
Java/Kotlin policy construction, independently compiles an **uncast** Callable
lambda, and requires actual javac rejection of process filesystem configuration
and worker-to-process policy assignment. Stable Kotlin independently checks both
fixed-scope rejections. Each language also checks the two retired `wrap` signatures,
`wrapTyped`, single/fixed/cached factories and `ContainedExecutor`, one source and
exact missing-symbol diagnostic per retired surface. Negative fixtures require exactly one
expected error, so missing symbols/classes cannot masquerade as scope rejection.
Callable, declared-context capture and custom-scoping `prepare` examples compile
but do not install containment or execute work.
Policies use canonical fixed-scope builders and `PolicyState.Uncompiled`.

These 14 small source files live under `src/rejected-api`: they are compiler
rejection probes, not retained production implementations. Their Gradle task is
`verifyRejectedApi`. Host-only harness checks run with `./gradlew consumerHarnessTest`.
Set `-PverificationLock=/absolute/path` for an existing shared fleet; staging and
acceptance must use the same override. Do not externally wrap `consumerCheck`
in that same lock because it owns the matrix lease internally.

The version probes resolve upstream Koog **1.3.0** and Boot **4.1.1**, both alone
and with the staged candidate. They log selected versions, including serialization
and compiler-library differences. Graph resolution is not adapter compilation or
framework runtime evidence.

## Actual documentation snippets

Opt in with `-PincludeDocSnippets=true`. `:doc-snippets:extractSnippets` extracts
the actual `<!-- consumer: id -->` Kotlin fences from `README.md`,
`GETTING_STARTED.md`, `enforcer/README.md` and `profiler/README.md`. Every runtime
Kotlin fence requires a unique marker; repository/dependency Gradle DSL is excluded.
Extraction adds only a package declaration, with no rewritten API calls or helper
stubs. The inventory is `doc-snippets/build/generated/snippets/inventory.tsv`.

Publish `:profiler` alongside the core owners using the same local staging mechanism
above, under a new coordinate when needed. This gate requires all three exact
JAR paths and rejects project dependencies. Generate the new coordinate's strict
locks once, then omit `--write-locks` for acceptance:

```bash
flock "$lock" ./gradlew -p tests/consumers --console=plain \
  --no-configuration-cache --no-build-cache \
  -PincludeDocSnippets=true -PconsumerKotlinVersion=2.4.20 \
  -PconsumerRepository="$repository" -PconsumerVersion="$version" \
  -PconsumerJdk=22 :doc-snippets:clean :doc-snippets:consumerEnvironment :doc-snippets:run
# Repeat with -PconsumerJdk=25 using the same reviewed locks.
```

The runtime self-check supplies genuinely absent collection evidence via the public
legacy `ProfilingResult` constructor. The actual conversion snippet, direct policy
conversion and Portal document conversion must all throw `IncompleteProfileException`;
the document round trip must preserve the incomplete coverage. It never fabricates a
complete trace. Owned/bootstrap and collection snippets are compiled, not executed;
native profile→install→post-install-JIT proof remains a separate gate.
`snippetSnapshot` selects a retained pre-change source directory only for diagnostic
compilation; acceptance extracts live guide text without that property.

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
