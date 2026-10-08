# Testing Strategy

mazewall has three intentionally separate test tiers. A green coverage number
is meaningful only when its tier is named.

## Host-safe unit quality

`./gradlew unitCheck` runs the ordinary `test` source set and verifies only
`build/jacoco/test.exec`. It is the required gate for CI runners that cannot
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

`./gradlew :enforcer:pitest :platform:pitest :profiler:pitest` adds an
assertion-quality gate for the deterministic policy, protocol, and profiling
slices. It runs only named host unit tests and enforces line reach, mutation
score, and test strength independently. The current floors are:

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

`unitCheck` is the fast host-safe development gate. `build` is the full merge gate;
kernel changes additionally require `integrationTest`, `integrationTestFreshJvm`
and `./scripts/run_tests.sh` on a suitable host/container environment. The last
command requires a usable configured Podman or Docker engine. A missing runner or failed kernel test is recorded as a
failed/unavailable gate, never as passing coverage.
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
