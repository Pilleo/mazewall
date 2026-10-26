# Published Koog native consumers

This is an independent Gradle build: **stable Kotlin 2.4.20**, JDK25, upstream
`ai.koog:agents-tools:1.3.0`, coroutines1.10.2 and host serialization1.10.0.
It consumes pinned Maven artifacts from an explicit local file repository; no
included source builds, metadata-skip flags or remote publication. The worker
has its independent producer serialization1.9.0 closure and no Koog/Spring.

The root lockfile preserves historical `0.1.0-task10` unchanged. New exact
producer coordinates use `locks/<consumerVersion>/gradle.lockfile`. Generate
that new namespace once with `--write-locks`, then run strict acceptance without
rewriting it. Consumer version names are validated before namespace selection.

The current complete source-bound workflow is:

```bash
./gradlew optionalIntegrationCheck --console=plain --no-configuration-cache
```

It owns the verification lock, uses a disposable normal clone, requires committed
producer inputs, stages a fresh exact candidate and executes actual Koog, JDBC/
Security and finished-Boot native consumers. No operator paths are hidden or moved.

## Declared source workflow and local staging

Run from the current source checkout. Settings declare the opt-in adapter/demo includes;
the runtime declares its actual publication. The wrapper builds/exports the real
plugin before demo buildscript evaluation, checking source inventory and JAR hashes.
No source-verification init script is used. The generic local staging init only
selects a fixture version/repository for already-declared publications, in both
producer and selected phases. The test-only API/guest requires its separate flag.

```bash
root="$PWD"
lock="$(git rev-parse --path-format=absolute --git-common-dir)/mazewall/verification.lock"
mkdir -p "$(dirname "$lock")"
revision=$(git rev-parse HEAD)
repository="$root/build/koog-repository-$revision"
version="0.1.0-integration.${revision:0:12}"

flock "$lock" bash scripts/koog-build.sh -I "$root/tests/consumers/stage.init.gradle" \
  -PconsumerVersion="$version" -PconsumerRepository="$repository" -- \
  -PkoogNativeFixtures=true \
  :integrations:koog:test :demos:koog-contained-tool:test \
  :demos:koog-contained-tool:portalCheck \
  :platform:publishAllPublicationsToConsumerBaselineRepository \
  :enforcer:publishAllPublicationsToConsumerBaselineRepository \
  :portals:runtime:publishAllPublicationsToConsumerBaselineRepository \
  :integrations:koog:publishAllPublicationsToConsumerBaselineRepository \
  :demos:koog-contained-tool:service-api:publishAllPublicationsToConsumerBaselineRepository \
  :koog-denied-api:publishAllPublicationsToConsumerBaselineRepository \
  :demos:koog-contained-tool:publishAllPublicationsToConsumerBaselineRepository

flock "$lock" ./gradlew -p "$root/tests/consumers/koog-portal" \
  -PconsumerVersion="$version" -PconsumerRepository="$repository" \
   --write-locks --write-verification-metadata sha256 verifyOperationRoot stageFreshProbe verifyPublishedClosure --no-configuration-cache

flock "$lock" ./gradlew -p "$root/tests/consumers/koog-portal" \
  -PconsumerVersion="$version" -PconsumerRepository="$repository" \
  verifyFreshNative --no-configuration-cache

flock "$lock" ./gradlew -p "$root/tests/consumers/koog-portal" \
  -PconsumerVersion="$version" -PconsumerRepository="$repository" \
  verifyFreshThreadNative --no-configuration-cache
```

The external javac probe must reject an arbitrary-submitter operation constructor
with exactly the constructor-access diagnostic; a positive external Java probe
still compiles the public submit/diagnostics facade. The public operation class has
genuine package-private Java constructors. Runtime.prepare is the normal construction root;
same-package fake construction exists only in test sources, not production jars.
The native consumer independently verifies real runtime preparation and dispatch.

`verifyFreshNative` stages JARs from the actual resolved published closure and the
consumer's own JAR, rejects class-directory inputs, and launches a real child JVM
from a fresh acquired working directory. It does not borrow producer source
outputs or rename/hide operator paths. A new worker cache is required; required
native failures remain failures with no skip/fallback. The owning Kotlin harness
retains command/timeout/reap evidence and checks the original checkout unchanged.

The probe exercises the exact upstream registry tool, strict host JSON decoding,
generated typed service, 64KiB text inputs, 64KiB result rejection, 128KiB request
and response limits, controlled read/write/symlink, loopback-connection and process-launch
denials with outside-worker positive controls, terminal crash/wire
failure, pre-crash original owner/PID, independent group, coroutine cancellation
  and physical child shutdown. A focused broker native regression also samples the
monotonic lifetime spawn count before crash and checks it after joined close, so
short-lived/pre-delivery reconstruction cannot hide between process snapshots.
The denied/crash/oversized service is test-only and never advertised to the model.
Selector-looking text is data. Host mappings/codecs remain outside protection.
The probe additionally observes a genuine READY generation before submission,
immutable counter snapshots, and CLOSED/retirement-complete only after actual
runtime close. Acquired worker `ProcessHandle`s must be dead after joined close.
Only after all assertions and retirement checks does it emit `KOOG_CONTAINMENT_V1_OK`;
the parent gate rejects missing, duplicate or altered summaries. See the
[containment contract demonstration](../../../benchmarks/koog-containment/README.md)
for the scenario matrix, expected summary, disposable-environment recipe and limits.
These operational observations are not denial-provenance or general agent-isolation claims.

## Separate thread-backed qualification

`verifyFreshThreadNative` launches `KoogThreadProbeKt` in its **own fresh JVM** and
working directory from the same staged published JAR closure, never producer
class directories. It is non-cacheable and always executes. Keep the preceding
Portal command unchanged and separate: thread bootstrap explicitly opens
`ManagedRuntime` with permanent process-wide `Policy.NO_EXEC_HOTSPOT`, which
forbids later Portal child startup/replacement. Neither close nor test completion
removes that baseline. Never install it in Gradle or the Portal probe JVM, or
weaken it to combine these qualifications. Required native setup or denial failure
is fatal/unavailable, never a successful skip or fallback.

The upstream thread registry exposes only `read_note`, bound to its real owner and
fixed read domain with `DomainLimits(1, 8)`, JVM-classpath reads, its owned allowed
directory and an explicit `Syscall.SOCKET` block. The probe checks strict bounded
UTF-8 reads, a denied host-readable sibling and a host-positive live loopback
control versus denied socket creation. Permission exceptions are classified as
`INFERRED_PERMISSION_FAILURE`, not authenticated kernel-denial provenance; the
socket check makes no already-open-FD or universal networking claim.

It also checks same-JVM/distinct platform-worker execution, passive generation
diagnostics, unchanged host/validator/virtual/Unconfined callers, invalid-input
non-admission, result bounds, single business-failure execution, strict unknown
JSON-field rejection, equal-generation foreign-owner rejection, nested rejection
and closed-owner rejection. Test-only socket/oversized/held controls are not
advertised. The separate held domain uses `DomainLimits(1, 2)` to require overload
rejection and zero queued-body entry after cancellation. Running cancellation is
cooperative: while lexical cleanup is held, the worker stays alive, work remains
unfinished and drain is incomplete. Only cleanup release, captured-worker joins
and canonical complete drain establish retirement; caller cancellation alone does
not. Native setup/close budgets are 10 seconds, latch/join waits 5 seconds and the
intentional incomplete-drain observation 20 ms.

Only after all assertions, runtime drains and captured worker death does the
thread probe emit exactly:

```text
KOOG_THREAD_V1_OK registry=upstream worker=platform fs=isolated socket=denied caller=unchanged virtual=true unconfined=true validation=pass overload=rejected cancellation=cooperative ownership=checked retirement=complete fallback=false replay=false
```

The existing optional aggregate adds `strict-fresh-native-koog-thread` after the
unchanged Portal phase, requires exit zero and exactly one exact `KOOG_THREAD_V1_`
line, and prints only the validated thread summary into its existing parent JUnit
artifact. Missing, duplicate, partial, altered or contradictory summaries fail;
host summary fixtures test that gate and are **not native evidence**. See the
[adapter guide](../../../integrations/koog/README.md#explicit-thread-backed-tools)
for the explicit owner/domain API, bounded reader and limits. This is trusted
synchronous first-party data-plane restriction, not arbitrary Java/ACE, heap or
tenant isolation, hard CPU/RAM isolation or guaranteed termination.

## Real agent loops without a model account

This consumer alone adds `ai.koog:agents-core:1.3.0`; the adapters' public dependency
graph and the worker bundle are unchanged. `verifyFreshThreadAgent` and
`verifyFreshPortalAgent` run actual upstream `AIAgent` loops from staged JARs in
separate fresh JVMs/directories. Run the complete committed-source qualification
with `optionalIntegrationCheck`, or run these tasks after the local staging above.

The first local staging command now explicitly writes verification metadata as
well as the new version's lockfile. Review that diff: only the freshly built local
fixture coordinates and deliberately approved dependencies may gain checksums;
unexpected changes to existing external approvals are not acceptable. Ordinary
strict runs do not rewrite metadata. The parent aggregate adds checksums of its
exact fresh producer's seven staged coordinates in the disposable clone, retaining
historical and external approvals byte-for-byte.

The bounded scripted executor verifies actual tool-result history, call identity
and error status, permits only two model requests, and uses five graph-node steps
for one complete tool round. Its self-check rejects missing history and extra
requests without invoking protected work. No network model, provider credentials,
provider client or `agents-test` dependency is used.

Both modes cover success, malformed/missing/invalid arguments, unknown tools,
oversized materialized results and genuine kernel-denied host-readable files.
Koog converts ordinary tool failures to error observations: the probe checks both
the model-facing error status (or default missing-tool text) and the original
failure event. Agent completion is not operation success. Model-visible errors
are not automatically sanitized; these synthetic probes do not certify live-provider
data handling. One fixed model request leads to one adapter submission; a later
explicit model request is not adapter replay and requires ordinary authorization.

The thread mode retains `DomainLimits(1,1)`, rejects overload without another body
entry, and qualifies both agent cancellation and a one-second caller deadline.
Held lexical cleanup keeps work unfinished and canonical drain incomplete. The
Portal mode also qualifies cancellation/deadlines, holds the original host mapping's
cleanup, and verifies unknown completion is terminal with no reconstruction of
the lost stateful service. Mapper entry does not authenticate when the child body
started. Existing setup/close budgets stay ten seconds, latch/join waits five
seconds and the deliberate incomplete thread drain twenty milliseconds.

Each mode emits its exact `KOOG_AGENT_V1_OK boundary=...` summary only after all
assertions and captured worker retirement checks. The existing optional aggregate
requires both complete summaries and rejects absent, duplicate, partial, altered
or contradictory evidence. This supplements, never replaces, direct-tool, JDBC,
Security and finished-Boot gates. It is deterministic contract evidence, not a
real-model usability, hard resource isolation or arbitrary-code security claim.

## Bootstrap freshness and generated packaging checks

```bash
flock "$lock" bash scripts/koog-build.sh -- \
  :integrations:koog:ktlintCheck :integrations:koog:detektMain \
  :demos:koog-contained-tool:jar :demos:koog-contained-tool:portalCheck

# The task owns the lock; do not wrap it in another flock on the same path.
./gradlew consumerBootstrapCheck -PverificationLock="$lock" --console=plain
# Host-safe process, fixture and malformed-package regressions:
./gradlew consumerHarnessTest
```

The Kotlin bootstrap suite acquires the shared lock itself. On independent
checkouts its default is `mazewall/verification.lock` under the Git common
directory; the active fleet uses the explicit override above. It executes actual
Gradle builds in a fresh disposable clone of the current tracked/untracked source
inventory, retaining executable modes and checking the original checkout unchanged.
No producer output is borrowed or hidden. It
checks default-core exclusion, adds/changes/removes a temporary producer resource,
verifies its real plugin JAR contents after refresh, rejects missing/changed JARs,
wrong-worktree/version/missing receipts and cached demo evaluation, and injects
an actual producer build failure plus a missing selected task. Producer failure
must not admit the second stage; each failure must propagate its original output
and nonzero status. The exclusion cases add real Kotlin source and require rejection of direct
excluded/conditionally skipped compilation plus all supported wrapper exclusion
forms before the selected stage, preserving the immediately preceding valid receipt.
The initial cold configuration and first real two-stage producer both have ten-minute
setup windows: listing projects does not compile the codegen/library closure. Warm
subsequent commands have four-minute bounds, and the
suite has a thirty-minute deadline on its owning cleanup thread. It remains an
explicit, non-cacheable acceptance task.

Both actual production and native-fixture packages are generated and inspected.
The checker requires format2 resource metadata and matching ZIP digest, generated
dispatcher/implementation in the worker, no loadable host guest classes, no
Koog/Spring or host TextTools in the worker, and the exact fixture-dependent service
and denied-probe inventory. Native containment is verified by the independent
consumer procedure above.

All argv/cwd, separate streams, exits and timeout/reap outcomes remain under the
printed clone's `bootstrap-evidence/` directory. Evidence descriptors are exclusively
acquired and retained; timeout state is recorded before bounded cleanup of acquired
process ancestry. Clones and evidence are retained on failure for inspection.
Ordinary host verification runs only the self-contained harness regressions.

## Evidence and remaining gates

Task7/Task10 checkpoints/reports in the shared plan folder record exact commands and
run logs. Focused production quality and native fixture pass on Linux
7.2.9-x64v3-xanmod1 / OpenJDK25.0.4.1. Required baseline receipts and actual
generation/source-cache verification come from the integrated base; this consumer
does not substitute synthetic readiness records.

Current production wiring and library publication are declared in source. The
dedicated optional CI lane checks both adapter builds and this source-bound
native workflow; it gates the existing required core job and master publication.
Operator production review/launch/cgroup authority and independent human usability
remain separate from known-fixture engineering acceptance. This is first-party
bounded process-service evidence, not general hostile-code/environment isolation.
