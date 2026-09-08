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

## Privileged kernel quality

`./gradlew kernelCheck` runs `integrationTest` and `integrationTestFreshJvm`.
The latter is isolated because filter installation is process-wide. It emits
coverage from only the integration execution files and verifies the combined
native-contract thresholds. These checks require the documented Linux kernel,
capabilities, and container profile.

Fresh-JVM selection is annotation-driven before Gradle forks. This prevents a
tag filter from launching a separate empty JVM for every ordinary integration
class, while retaining a process boundary for every test that needs one.

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
