# Testing Strategy

mazewall has three intentionally separate test tiers. A green coverage number
is meaningful only when its tier is named.

## Host-safe unit quality

### Gradle resolution scanner

`./gradlew checkGradleLazyResolution` scans Kotlin and Groovy Gradle
scripts in the caller's checkout. The Kotlin/JDK lexer recognizes
the bodies of `doFirst`/`doLast`, `providers.provider`, `@TaskAction` methods,
and declared `asArguments` methods. Nested bodies retain their enclosing action
context; action/provider call arguments remain configuration-time expressions.
Immediately invoked closure arguments and immediately consumed provider bodies
are not deferred, including provider expressions inside matched grouping
parentheses. Enclosing helper calls are not treated as grouping. Comments and
literal strings cannot change brace context;
`${...}` expressions and Groovy shorthand GString interpolation remain code.

Outside those bodies, `.asPath` and `.singleFile` consumption fails with a file
and line diagnostic. Configuration-origin `.files`/`.getFiles()` consumption
also fails, including indexed `configurations["name"]` receivers;
`inputs.files(configuration)` remains lazy input registration.
The mandatory buildSrc Kotlin suite executes production
decisions against 96 retained positive and negative language fixtures, including the
multiline Spring action regression and the now-deferred actual Java consumer.
Selected eager regressions assert the complete file/line/text diagnostic inventory,
including an independent copy of the original eager consumer's line17.
Run `./gradlew -p buildSrc verificationActionsTest` when changing
the scanner; it is independent of native/kernel verification.

Source discovery is a separate contract. The scanner first enumerates CWD files
with explicit pruning for `.git`, `.gradle`, generated `build` directories and
`.worktrees`, then selects `.gradle`/`.gradle.kts` suffixes. Filename inclusion
globs must not force traversal of private/generated state. Checkout-local VCS
ignore rules exclude untracked scratch; ripgrep config, `.ignore`, global and
parent ignore rules cannot hide normal source. There are no integration, test or
orchestrator source-folder exclusions.

In a Git checkout root, its default read-only Git index augments discovery so
tracked source is checked even when ignore rules match (including a tracked
`src/.../build` source package). Read-only inventory Git children clear only the
repository-local environment names reported by Git; inherited `GIT_INDEX_FILE`
and foreign repository context cannot replace the checkout's default index.
The parent environment and all non-Git-local variables remain unchanged. Git
top-level must still match CWD, including linked worktrees with a regular `.git`
pointer file. Git optional locks are disabled. VCS/cache/worktree state is never source. Non-Git source
snapshots use the same explicit filesystem discovery. Directory enumeration
errors and source read/permission failures remain nonzero scan failures; no
cache ownership, permissions or content are repaired by the scanner.

The mandatory `GradleSourceInventoryTest` Kotlin suite exercises the production
entrypoint with real temporary Git/source files, unreadable generated directories,
unreadable real source, tracked ignored scripts under inherited alternate indexes,
linked-worktree default indexes, untracked modules and source symlinks.
Permission fixtures require an unprivileged UID and refuse
false root-user permission proof; they never change user cache directories.

Source aliases follow the coordinator's bounded-within-checkout policy. Resolution
checks link components before target access; targets and remaining path suffixes
must stay inside physical CWD and outside `.git`, `.gradle`, `.worktrees` and
conventional `build` namespaces. External, dangling, unsupported-type, unreadable
and cyclic targets fail with concrete errors. Resolution is bounded to40 distinct
link identities. Valid file aliases retain the link's Gradle language/diagnostic
path while reading its verified physical target.

Resolution consumes components in filesystem order: a reached symlink's raw
target is expanded before any later `..`, rather than lexically collapsing the
whole target lexically. Absolute targets must begin with the physical
checkout's exact component prefix; no external prefix is traversed to admit an
equivalent spelling. Dot components within that anchor are supported. A parent
component cannot erase a private/root boundary crossing, an unreadable intermediate
directory, or a non-directory traversal. The 52 real-filesystem cases include
the symlink-before-parent eager-source regression with a clean lexical decoy,
relative/dot/absolute variants and explicit boundary/permission failures.

Directory aliases visit physical `(device,inode)` identities. Repeated aliases do
not suppress real Gradle source: an active alias forces coverage of its target even
when normal local VCS ignores hide that physical directory. Supplementary alias
discovery also ignores ambient Git `core.excludesFile` rules: the child
`check-ignore` command overrides that file with `/dev/null`, retaining checkout
`.gitignore` and repository-local scratch exclusions without mutating parent
configuration or environment. Actual-entrypoint eager and unreadable-target
fixtures use owned temporary global Git configuration to protect this contract.
Normal and forced
coverage each expand a physical directory at most once; a directory-edge graph
rejects cycles even across already-visited sibling aliases. Indexed source and
links remain authoritative under the actual default Git index. Legitimate contained
aliases with only non-Gradle files pass; no project/source-folder exception is used.

Read targets are re-resolved before source IO. This is a readonly verification of
a stable checkout, not FD-relative race-proof containment against concurrent
filesystem mutation; complete parser/interprocedural and native claims remain out
of scope. Existing user links/cache ownership/content are never repaired or removed.

Inventory bounds are explicit: untracked paths named `build` are conventionally
pruned, not proven to be generated. An untracked legitimate module/source package
with that name is outside this walk; tracked source remains indexed. Local VCS
ignore rules are trusted declarations for untracked scratch, not ownership proof.
These bounds do not certify every possible untracked module layout.

This is a bounded lexical guard, not a compiler or Gradle type resolver. It does
not follow helper calls, dynamic DSL dispatch, reassignment, provider aliases
consumed later, or arbitrary configuration aliases across statements. `.files`
tracking recognizes direct `configurations` receiver chains and simple same-line
`val`/`var`/`def` bindings, not every FileCollection. Other resolution APIs are
outside this guard. Shadowed action/provider/method names require manual review.
Groovy slashy-literal admission uses the previous significant token across
newlines; division/slashy ambiguity in other contexts is not fully parsed.
Unterminated comments/strings/interpolations and unbalanced delimiters fail the
scan rather than granting an exemption. A passing scan establishes only these
source-level contracts.

`./gradlew unitCheck` runs the ordinary `test` source set, selected host lint/static
analysis, the warning ratchet and CI cache-policy checks, and verifies
`build/jacoco/test.exec` plus owned unit-child files in
`build/jacoco/unit-children/*.exec`. It never selects kernel execution files.
It is the required gate for CI runners that cannot
create a seccomp listener, install Landlock, or use Podman. Unit tests model
native failures through `NativeEngine` collaborators; they do not weaken a
failure into a warning merely to make a test run.

The unit floors cover deterministic policy, compiler, and state-machine logic.
They deliberately do not claim that a real kernel transition was exercised.

The full host-only instruction floors are enforced by `unitCheck` from only
`jacocoTestReport.xml` execution data: `:enforcer` 82%, `:platform` 65%, and
`:profiler` 70%. Platform's lower bundle floor is deliberate: native bridge and
kernel-owned code are verified in `kernelCheck`, while its pure protocol and
mapper slices have separate high mutation gates below.

Coverage ownership is part of this contract. Platform-owned specifications must
run in `:platform:test`: the 2026-09-07 relocation of syscall, socket utility,
and daemon-reactor specifications raised the observed host-unit platform report
to about 69% without adding synthetic tests. The host floor is 65%, retaining
more than four percentage points of change tolerance below that result.

The host-only mutation gate adds an assertion-quality check for the deterministic
policy, protocol, and profiling slices. Run the three modules serially, with two
PIT workers and one ordinary test fork:

```bash
./gradlew :enforcer:pitest :platform:pitest :profiler:pitest \
  --max-workers=1 --no-parallel \
  -Pmazewall.pitest.threads=2 -Pmazewall.test.maxParallelForks=1
```

`gradle/pitest-limits.gradle` supplies the shared default of two PIT workers.
`-Pmazewall.pitest.threads` takes precedence over compatible `-Dpitest.threads`;
selected values must be positive integers. Each PIT parent uses
`-Xms128m -Xmx512m -XX:MaxMetaspaceSize=256m`, and each minion uses
`-Xms128m -Xmx256m -XX:MaxMetaspaceSize=256m`. Both retain
`--enable-native-access=ALL-UNNAMED`. These limits do not change Gradle's JVM
budget or the native suites' fresh-process requirements.

CI runs that command through the host wrapper (not the container runner):

```bash
python3 scripts/ci_resource_probe.py --output build/reports/ci-resources/mutation.json -- \
  ./gradlew :enforcer:pitest :platform:pitest :profiler:pitest \
  --no-daemon --max-workers=1 --no-parallel \
  -Pmazewall.pitest.threads=2 -Pmazewall.test.maxParallelForks=1
```

The schema-1 receipt records `command_exit_code`, `oom_kill_delta` and
`qualification` (`pass`, `unsafe` or `unknown`), with raw memory observations.
A qualified pass requires at least 2147483648 bytes of observed headroom and zero
additional OOM kills; unknown evidence is not a pass. The wrapper preserves a
failing command's exit status and rejects unsafe or unknown memory evidence even
when PIT succeeds. CI always uploads the receipt and all three PIT report paths,
including after failure or cancellation when artifact upload can still execute.
On Linux, the wrapper acquires a child-subreaper lifetime before launch and uses
pidfds to retire its descendants, including detached single-use Gradle daemons
and workers. Retirement also drains descendants after a normal launcher exit,
before final observations/publication; cancellation preserves the actual launcher
status separately from the wrapper's signal status. Cleanup allows three seconds
before SIGKILL and one further second to reap; a cleanup failure is not success.
Unavailable ownership primitives prevent launch. This is a single-threaded CLI,
not a general-purpose in-process supervisor: it refuses pre-existing children or
custom SIGCHLD handling and restores the prior subreaper setting afterward.
Run the wrapper regression separately with
`python3 -m unittest discover -s scripts/tests -p 'test_ci_resource_probe.py' -v`.

`./gradlew -p buildSrc pitestLimitsTest --console=plain --max-workers=1` resolves
the actual shared script, root properties, and PIT plugin in an independent
three-project fixture. It checks precedence, invalid values, JVM budgets,
unrelated extension settings, and changed properties with configuration-cache
reuse. The regression also runs in mandatory `verificationActionsTest`; both
tasks declare the shared script and root properties as inputs.

PIT runs only named host unit tests and enforces line reach, mutation score, and
test strength independently. The current floors are:

| Module | Line reach | Mutation score | Test strength |
| --- | ---: | ---: | ---: |
| enforcer | 89% | 61% | 76% |
| platform | 97% | 61% | 98% |
| profiler | 93% | 65% | 75% |

Raise a floor when a behavioral specification kills additional meaningful
mutants. PIT is not evidence of a seccomp or Landlock kernel transition.

`BpfSimulator` is covered by host unit contracts and the differential
kernel-vs-simulator suite, but is deliberately outside the PIT slice: control-flow
mutants can create non-terminating interpreter paths and consume the mutation-run
budget without representing a production failure. To make it mutation-testable,
the simulator would need an explicit, configurable instruction-step bound with a
distinct exhaustion result; do not add a test-only production bypass.

## Fast iteration and cache reuse

Root `buildLogicCheck` is the explicit complete build-tool regression gate. It uses
public GradleBuild APIs, a distinct nested build name `build-logic-verification`,
the existing buildSrc `verificationActionsTest`, one nested worker and no project
parallelism. Root `check` requires it, preserving existing root build/mergeCheck
coverage; buildSrc's own test/check dependencies also remain mandatory. The bridge
has no outer cached-success output or skip predicate: actual nested inputs and
marker invalidation remain authoritative.

JAR bootstrap/help/compile/assemble do not implicitly select the verifier, though
ordinary buildSrc compilation can still run. Lazy `mustRunAfter` edges put other
root/child tasks after the gate only when it is selected, without adding it to fast
paths. Workflow and buildSrc settings remain declared verifier inputs.

Build-tool regression fixtures use `$GRADLE_USER_HOME/caches/mazewall-test-kit`
as their TestKit home rather than `buildSrc/build/test-kit`. The current Gradle
User Home owns this path, including explicit `-g` overrides; the existing host and
dedicated container homes remain separate. Its location under `caches` lets the
existing Gradle cache archives retain nested-build dependencies, compiled scripts
and compilation outputs across disposable producer checkouts. It does not reuse
old native acceptance outcomes or remove the mandatory build-tool regression suite.
The JVM argument provider declares the relative cache layout as input and the
absolute Gradle home as internal transport. This prevents host/container cache-home
switches from invalidating the same verification task and rerunning its suite
inside the demo's bounded startup. Verification sources and classpaths remain
declared inputs; only cache-location plumbing is excluded.
The real two-checkout TestKit regression asserts the configured nested home and
requires the second independent Java compilation to be `FROM-CACHE` with a real
class output. Run it directly with `./gradlew -p buildSrc testKitCacheTest`.

CI computes `scripts/ci_cache_key.sh` once immediately after checkout in each
container-owning validation job (`optional-integrations` and `core-quality`).
Container Gradle restore/save keys share that immutable job output, retaining their
OS prefixes, SHA suffixes and dedicated cache layout. Saves
require successful key computation as well as their existing conditions.

The main container restore first tries its matching build identity, then matching
and broad optional-container entries before its older broad main fallback. This
reuses the compatible PR container home already saved by optional acceptance,
without adding another per-PR archive or mixing host/container cache directories.

The helper hashes current bytes and NUL-delimited relative names of Git-tracked
Gradle/Groovy/Kotlin build scripts, root/nested `gradle.properties` and
`gradle.lockfile`, the versions catalog, verification metadata, wrapper JAR/properties,
Containerfiles, CI workflow and cache-key/container-runner scripts. Untracked or
generated scripts/markers and unrelated source do not affect it. Repository-local
Git overrides are cleared only in the child, with optional index writes disabled.
Contained readable regular-file aliases are supported; external, dangling, cyclic,
special, unreadable or missing inputs and Git/hash errors fail without publishing
a digest. Computation assumes an immutable checkout, not race-proof containment.

`./gradlew -p buildSrc ciCachePolicyTest --console=plain --max-workers=1` runs real
owned-Git fixtures and workflow-policy mutations. These checks also run in mandatory
`verificationActionsTest`, whose inputs include the helper. The policy fixtures
retain cache-identity/save/host-container regressions and reject serialized,
skipped, unbounded or allowed-to-fail mutation lanes, missing diagnostics and
unsafe required-check joins. They execute the extracted join shell command for
all 625 core/mutation/security/build-logic combinations with Go fixed to success,
then every Go result with the other prerequisites successful. Only five literal
successes pass. Existing security125/legacy25 projections fix the extra results
explicitly to success. Actual security metadata/eligibility/ownership/task shells,
snapshot assertion and lifecycle conditions are exercised without scanner/network
calls; simulated outcomes do not establish actual cache persistence. Cache identities are
compatibility keys, not native protection attestations: cache isolation, ownership,
required native freshness and publication gates are unchanged.

The marker-producing build-tool verifier declares the actual CI workflow as a
named relative-path-sensitive input. An applied-registration fixture checks that
a workflow-only edit reruns executable policy checks and an invalid edit removes
the old success marker. Ownership fixtures verify a different numeric UID rather
than assuming a system file is foreign; production ownership checks are unchanged.

The `optional-integrations`, `core-quality`, `mutation-tests`, `security-check`,
`build-logic-validation` and `go-validation` jobs run
independently on separate `ubuntu-26.04` runners with JDK 25; heavy commands are
never backgrounded together in one checkout. Core retains host quality,
kernel/fresh-JVM and exploitation gates; independent security retains the real
vulnerability scan. Mutation uses host Gradle without Podman
or another container-cache archive. Core, mutation and security independently filter code
changes, including `.github/workflows/ci.yml`. A docs-only mutation step may skip
while its job succeeds; the required join is never job-level skipped.

Build-logic validation is an independent unconditional Ubuntu26.04/JDK25 lane,
with the existing setup-gradle policy, no extra heap/worker/fork allowance and
always-uploaded diagnostics. It runs:

```bash
python3 scripts/ci_resource_probe.py --output build/reports/ci-resources/build-logic.json -- \
  ./gradlew buildLogicCheck --configuration-cache --configuration-cache-problems=fail \
  --no-daemon --max-workers=1 --no-parallel
```

The actual executed-suite receipt must qualify with known >=2147483648-byte
headroom and zero additional OOM kills. Unknown evidence is not a pass. An unchanged
UP-TO-DATE/cached nested verifier can demonstrate reuse but is not full-workload
memory evidence. Other CI lanes compile build logic as needed without implicitly
repeating its complete regression suite. Intentional bounded fixture builds can
reuse verifier task names; global task-name counts are not duplicate-suite proof.

Go validation installs Go from the canonical module's directive and pins
`govulncheck` v1.9.0. Race tests and vulnerability scans run serially for both
agentless modules when either tree, the CI workflow or resource wrapper changes.
Unrelated changes skip those steps, not the required job. Both modules are checked
even if one fails; any test, scanner, vulnerability or resource failure blocks the
publication join. The job bounds Go concurrency/memory and uses the existing
resource probe. `make unit` propagates the prepared modfile to capture-test child
commands; `make vuln` scans that same graph. No vulnerability suppressions or
report-only success fallback are configured.

Security uses a caller-owned host `GRADLE_USER_HOME/dependency-check-data`, not the
container's separate data mount. The security lane alone owns its data-only cache
and retained snapshot artifacts; setup-gradle excludes that directory. Compatibility keys include OS, architecture
and the actual catalog scanner version, with unique run-ID/attempt snapshots and
only the compatible restore prefix. No previous matched key is overwritten, no
cross-version/container fallback is allowed, and a cache hit never skips analysis.

On a genuine cache miss (empty matched key, not the exact-hit boolean), the lane
looks for the newest compatible, non-expired completed snapshot artifact. Artifacts
are retained for 14 days outside the evictable build-cache quota. Default-branch
consumers accept only same-repository default-branch push artifacts from this CI
workflow; PRs may additionally consume their own PR's artifacts, never another PR's.
Source run/repository/commit/attempt metadata and the exact compatibility prefix
are checked. Overall workflow success is not required: a completed database remains
usable when a later analysis or another job failed. Deleted-fork metadata is rejected.
API/download errors are failures, not evidence that no snapshot exists.

Snapshots contain a versioned compatibility manifest and SHA-256/size inventory of
all database files, including hidden cache files. Preparation and restoration reject
links, foreign ownership, non-regular files, missing/empty `odc.mv.db`, more than
10,000 entries, more than 2 GiB of data or a manifest larger than 2 MiB. Restoration
checks the inventory before admitting data through an owned temporary directory;
existing nonempty data is never overwritten. Snapshot age is not a freshness waiver:
the normal updater must still succeed before real analysis. With neither a compatible
cache nor retained artifact, an explicit cold bootstrap is still necessary.

The independent security job has a bounded 180-minute cold budget, including a
150-minute `dependencyCheckUpdate` step. This separates the observed slow full NVD
bootstrap from already successful native work; it is not a latency guarantee.
After a successful update, caller ownership is checked before explicit snapshot
save and a separate retained-artifact upload. Artifact preparation/publication has
its own success guard and nonempty artifact-ID assertion before analysis; persistence
failures leave the lane red. Failed/cancelled updates and foreign ownership never publish a snapshot;
there is no permission repair or partial-database resume assumption.

Because cache/save may warn and exit successfully after upload failure, a supported
lookup-only restore checks the identical new primary key/path without fallback,
with fail-on-cache-miss. An explicit assertion also requires literal `cache-hit ==
true`, rejecting false/unset/nonexact results even when unavailable cache features
return without an action error. This proves membership only, not archive integrity,
retention or successful future restoration. Missing/evicted/not-yet-visible data
fails closed rather than invoking a retry or accepting warning-only upload success.

After successful update and ownership, `dependencyCheckAnalyze` runs even if save,
lookup or assertion failed, unless cancelled. Persistence failures keep the job
red; successful analysis cannot erase them. Failed update/ownership prevents data
use/save/analysis. Existing CVSS 7 threshold, suppression/configuration/demo/OSSIndex
selection, normal freshness checks and scanner errors remain unchanged. Missing or
blank NVD credentials and docs-only changes retain the existing no-scan policy;
an eligible-key flag or a green skipped job is not evidence of executed analysis.
Always-scheduled ownership and diagnostics retain update/analysis logs and reports.

Both existing required check names remain: `Koog and Spring Integration Acceptance`
and `Build, Test, Security Scan & Publish`. The latter is the `build-test-publish`
join with `needs: [core-quality, mutation-tests, security-check, build-logic-validation, go-validation]`
and `if: always()`; its shell requires all five results to equal `success`.
Failure, cancellation, skipping or a
missing result cannot turn the join green. It forwards
`core-quality.outputs.container_cache_hash` unchanged.

Publication remains a separate default-branch-push-only job with `needs` edges to
optional acceptance and that required join. Its normal success condition prevents
publication after a failed, skipped or cancelled prerequisite; PRs cannot publish.
Only publication has package-write permission. It consumes the forwarded core
cache identity and retains the same container runner, publication command and
credential bindings; it does not rerun host quality checks as another gate.

Superseded PR runs cancel within their PR-specific concurrency group. Push events
use unique run-ID groups and do not cancel or replace separate release runs.
Serial merge handling remains with the existing operator/orchestrator control
plane. Fresh native tests remain uncached; parallel scheduling and cached build
inputs must not be described as reuse of old native pass results. Measure actual
hosted job overlap and durations before claiming an end-to-end speed improvement.
Hosted acceptance needs a cold and an unchanged-head warm run with independent
lane starts, qualified mutation receipts, all three real PIT reports and the same
native/adapter assertions. Compare wall time, queue/setup/cache/execute time and
total runner minutes against run `39327392885`; unknown evidence or unsafe budgets
stop rollout rather than automatically raising limits or dropping targets. Local
focused checks do not qualify the GitHub runner budget or release publication;
the latter needs a later approved default-branch run.

The completed-data repair additionally requires actual trusted-default cold
update/ownership/save/exact-membership/analysis evidence and compatible warm
restoration followed by real analysis. A later update-due observation must show a
new completed snapshot; a run inside the scanner's normal validity window does
not prove refreshed NVD data. PR-only caches cannot seed default-branch bootstrap.
Record cache size/ref/version, durations and task outcomes; eviction and external
API latency remain possible. A failed security lane and its dependent gates are
the bounded recovery unit, not a reason to replay successful native pipelines.
Retained recovery additionally needs a real cache-miss/artifact-download/validated
restore followed by update and analysis. Offline fixture round trips do not establish
GitHub artifact availability, retention or an executed vulnerability scan. Qualify
this by rerunning the security job, not successful native lanes; do not delete shared
caches to manufacture the miss. Artifacts can also expire or be deleted, and their
storage usage is separate from the build-cache quota. The historical cache-removal
cause remains unproven; recovery does not depend on assuming eviction.

Root verification follows source plugins rather than optional module paths.
Non-demo grouping projects retain lazy child `build` and `check` dependencies
without acquiring Java source sets or typed Kotlin analysis. Actual Java modules
retain tests, SpotBugs and coverage gates; Kotlin JVM modules additionally retain
`detektMain`. This includes optional Java-only Spring and Kotlin Koog leaves.
Conventional source modules retain early Java task providers because core build
scripts query sibling providers before evaluation. Plugin callbacks also admit
source-free Java starters and modules that declare custom source roots.
Root `unitCheck` and `kernelCheck` select only child gates that actually exist.

`./gradlew -p buildSrc verificationActionsTest` executes the production root
convention in a real TestKit fixture with implicit/explicit grouping projects,
Java and Kotlin leaves. It checks child gate selection and executes real Java
tests, SpotBugs and typed Detekt; injected test and analyzer failures must fail.
The existing aggregate fixture separately proves configuration-cache reuse,
and native freshness fixtures remain mandatory in the same executable harness.

Automatic `runTriage` observes failed subproject **Test tasks in the current build**,
including assertion failures, worker crashes and failures before XML is written.
Its per-invocation build service receives task-completion events rather than cached
Boolean providers or old XML. The `testFailureEventsReady` ordering barrier follows
all selected Tests; the finalizer waits for that barrier's completion event before
reading the failure flag. Gradle delivers these events asynchronously, serially in
completion order. The wait is bounded to 60 seconds and interruption/timeout is a
visible error. Test exceptions and the failed build exit are preserved. Successful,
up-to-date and no-source Tests do not start the collector, even with stale failure
XML. An existing report is not automatically removed; its presence alone is not
evidence that triage ran in this invocation.

`./gradlew -p buildSrc failureTriageTest` runs just the covering executable regression
without replaying unrelated native fixtures. The mandatory `verificationActionsTest`
also runs it. It consumes the production root registration, service and collector
sources in real Gradle/TestKit builds: configuration-cache first/reused failures,
success/staleness/up-to-date controls, pre-XML and predicate failures, worker crash,
parallel successful siblings, non-Test failure, and deliberately delayed completion
delivery. Failed cases must produce actual `DiagnosticTriageRunner` JSON while
retaining the original failed Test/build. The collector's JVM snapshot remains
[self-only](../profiler/profiler-design.md#self-only-diagnostic-report-scope).

Use the owning module's named host tests while changing a contract:

```bash
./gradlew :enforcer:test --tests '*ReviewedOperationArtifactTest*' --tests '*OwnedRuntimeTest*'
./gradlew :enforcer:checkKotlinAbi :enforcer:detektMain
```

Build and configuration caches are already enabled. Repeating the same focused
verification on unchanged inputs reuses tests and analysis: the measured 2026-10-07
warm run completed in 5s (`run-695d03175908`, tests/Detekt UP-TO-DATE). Changing the
test filter changes task inputs; do not interpret an earlier focused XML report as
complete-suite evidence. Use `--info --profile` to inspect task invalidation and
`build/reports/profile/` rather than repeatedly running a full gate to diagnose timing.

Run the full merge gate at a coherent verified checkpoint, after focused checks pass.
During iteration, run kernel verification only for changed installation/native/protocol
behavior or a new kernel contract. The full merge graph already schedules module
integration suites; adding identical tasks to the same invocation does not duplicate
their execution, but repeating the full invocation reruns externally sensitive suites.
Do not cache kernel/endpoint success as if host configuration were ordinary source input.
The root test convention enforces this for `integrationTest*` Test tasks: they are
neither up-to-date reused nor restored from the build cache. Named ordinary host
tests retain reuse. An executable Gradle fixture checks unchanged repeat execution,
deleted-output/cache behavior and host-test reuse; a successful container command
with cached kernel task outputs is not current container protection evidence.

The CET C fixture uses local up-to-date compilation reuse with source/script and
effective compiler/header/linker/environment inputs. The native library is not stored
as portable cached protection evidence. `bash scripts/tests/cet_fixture_cache_test.sh`
verifies unchanged reuse and source/header/compiler/missing-output invalidation in an
independent Gradle fixture.

## Privileged kernel quality

`./gradlew kernelCheck` runs `integrationTest` and `integrationTestFreshJvm`.
The latter is isolated because filter installation is process-wide. It emits
coverage from only the integration execution files and verifies the combined
native-contract thresholds. These checks require the documented Linux kernel,
capabilities, and container profile.

Fresh-JVM selection is annotation-driven before Gradle forks. This prevents a
tag filter from launching a separate empty JVM for every ordinary integration
class, while retaining a process boundary for every test that needs one.
Selection includes inherited ordinary, parameterized, repeated, template and
factory methods, and nested test containers through each concrete discovery root.
Declared fresh markers without an executable selection fail with a diagnostic;
an unmatched explicit filter is not treated as successful execution.

The shared isolated-process harness cleans up after successful parent exits as
well as timeout, interruption and failure. It retains observed descendant handles,
terminates descendants before the parent and reaps with bounded waits, preserving
interruption and suppressed cleanup errors. Its process-tree observations cannot
prove discovery of a child that forks and reparents entirely between observations;
this is a cooperative test harness, not an arbitrary-process containment boundary.

## Build and evidence gates

`unitCheck` is the fast host-safe development gate. `./gradlew build` is the full
completion gate for included modules: formatting, lint/static analysis, tests,
and configured coverage thresholds. Its task graph already selects kernel and
fresh-JVM integration suites. Select owning-module integration tasks for focused
iteration, or use `./scripts/run_containerized_tests.sh kernelCheck` as the containerized test runner;
equivalent test commands are alternatives, not additional gates after a successful
full build. The container runner requires a usable configured Podman or Docker
engine. A missing runner or failed kernel test is recorded as a failed/unavailable
gate, never as passing coverage. Include `-PincludeOrchestrator=true` when verifying
orchestrator changes. Documentation-only changes require reference/link checks,
`git diff --check`, and backlog validation; executable or build-wiring changes
require their focused tests and final build.
The runner requests an init/reaper
so observed orphan descendants are actually reaped. Without it, fresh supervised tests
can leave zombies under container PID 1 and correctly fail termination assertions.
Do not treat zombies as completed ownership or relax cleanup checks to hide that failure.

`refactorFirstReport` remains optional manual architectural analysis at version
0.10.0. It analyzes committed production sources in an isolated history-preserving
snapshot and writes `build/reports/refactorFirst/refactor-first-report.html`.
Neither it nor Maven/snapshot preparation is attached to ordinary `build`, `check`,
`unitCheck` or `kernelCheck`. Verify this from `build --dry-run` when build wiring changes.

Test-health records identify module, task and fork. Cached or up-to-date task
outputs are reuse evidence, not fresh execution. Record exact task execution counts
alongside XML test counts; XML left by an earlier focused invocation cannot prove
that an aggregate command reran that suite. The latest hardening gate record is
[review-hardening-verification.md](review-hardening-verification.md).

## Combined coverage and testability debt

`jacocoCombinedReport` is informational and combines host and kernel evidence.
Default and custom reports and verifiers select the same actual Java/Kotlin
`main` class output with the same exclusions; an initially empty custom task's
class collection is not a production denominator. The executable TestKit
contract checks that the combined report contains real main classes and that
the custom verifier rejects inadequate instruction coverage.

Enforcer's existing Boot deployment scenarios collect coverage with an explicit
agent from the test task's catalogued JaCoCo configuration. The task declares
the agent input and owned child output directory, clears that directory on
execution, and passes trusted configuration directly to the test worker.
Each launch uses a unique, non-appending execution file and requires nonempty
data on successful exit. The existing bounded wait and physical reaping remain
mandatory. Only mazewall classes are instrumented: Boot loader and finished
fixture JAR/resource bytes retain their original identities. Inherited JVM
options remain stripped; ordinary launches without configured child coverage
do not add an agent. Unit reports and verifiers include the same owned child
files, while combined reports additionally select their intended kernel files.
Native bridges may be excluded from unit coverage only when a kernel contract
test owns the behavior. If a branch cannot be tested with the existing
`NativeEngine` seam, inject the required engine or transport collaborator at
the constructor boundary; do not add static native calls or test-only
production bypasses.

## Test style

Tests state an observable contract in their name and assertions. Use
parameterized transition tables for state machines and policy combinations;
include every forbidden safety combination and pairwise legal interactions.
Do not use an assertion-free loop that merely invokes every state/event pair as
"exhaustive coverage": every table row must name and assert its state,
effects, and ownership outcome.
Extract fixtures only when repeated setup hides the scenario. Shared fixtures
may depend only on platform-level test seams and must restore exactly the
state they mutate; module-specific mutable globals stay with their owning
module. Do not add tests named `*CoverageTest`: coverage is a result, not a behavior. Replace a
coverage-only probe with a behavior-level specification when its production
seam is available.
