# Published Koog/Portal native consumer

This is an independent Gradle build: **stable Kotlin 2.4.20**, JDK25, upstream
`ai.koog:agents-tools:1.3.0`, coroutines1.10.2 and host serialization1.10.0.
It consumes pinned Maven artifacts from an explicit local file repository; no
included source builds, metadata-skip flags or remote publication. The worker
has its independent producer serialization1.9.0 closure and no Koog/Spring.

The checked-in lockfile selects `0.1.0-task10`. Reproduction uses the Task10
production-wiring revision recorded in the shared report, based on `92da1536`;
the coordinate labels this local fixture and is not a release. Use a new coordinate and `--write-locks` explicitly
when changing publication bytes, then verify again without rewriting locks.

## Declared source workflow and local staging

Run from the assigned worktree. Settings declare the opt-in adapter/demo includes;
the runtime declares its actual publication. The wrapper builds/exports the real
plugin before demo buildscript evaluation, checking source inventory and JAR hashes.
No source-verification init script is used. The generic local staging init only
selects a fixture version/repository for already-declared publications, in both
producer and selected phases. The test-only API/guest requires its separate flag.

```bash
root="$PWD"
lock=/home/leanid/Documents/code/java/jseccomp/.superpowers/sdd/2026-10-06-mazewall-integration-foundation-plan/verification.lock
repository="$root/build/task10-repository"
version=0.1.0-task10

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
  verifyOperationRoot stageFreshProbe verifyPublishedClosure

bash "$root/tests/consumers/koog-portal/run-fresh-native.sh" \
  /usr/lib/jvm/java-25-openjdk-amd64/bin/java
```

The external javac probe must reject an arbitrary-submitter operation constructor
with exactly the constructor-access diagnostic; a positive external Java probe
still compiles the public submit facade. The public operation class has a genuine
package-private Java constructor. Runtime.prepare is the normal construction root;
same-package fake construction exists only in test sources, not production jars.
The native consumer independently verifies real runtime preparation and dispatch.

The native script acquires the same lock, copies only the curated consumer JARs
to a new `/tmp` directory, renames enumerated original module output/repository
**pathnames** aside, and runs a disposable JVM in its own session. This is not
filesystem access revocation for the backup directories. A pre-effect object-
identity ledger owns every move. EXIT cleanup attempts every restoration even
after an error, requires an absent exact destination, and never nests/overwrites
unknown output. Conflicting/missing/unowned paths and recoverable backups are
reported and preserved. A native failure remains the primary exit status; cleanup
failures are also reported, or make an otherwise successful run fail (exit70).

INT/TERM/HUP terminate the owned native session/descendants and then attempt all
restores. SIGKILL, host death and uncatchable failures require recovery from the
reported backup paths; they are not promised transactional recovery. Parent/init
reaping of descendant zombies is separate from stopped execution. Fresh probe
directories and fault-injection fixtures are retained as evidence. Required
failures fail the command; there are no native skips or fallbacks.

```bash
flock "$lock" bash tests/consumers/koog-portal/native-restoration-test.sh
```

This executes the actual runner against temporary fixture directories and injects
destination conflicts (including a race during restore), replaced/unowned backups,
first-restore failures, missing parents, native plus cleanup failures, hide moves
that effect before returning an error, TERM with a descendant, and TERM while
the owned launcher has not yet established its session. It asserts
filesystem contents/identity preservation, aggregate/primary status and process
state rather than inspecting script text.

The probe exercises the exact upstream registry tool, strict host JSON decoding,
generated typed service, 64KiB text inputs, 64KiB result rejection, 128KiB request
and response limits, completed remote filesystem denial, terminal crash/wire
failure, pre-crash original owner/PID, independent group, coroutine cancellation
and physical child shutdown. A focused broker native regression also samples the
monotonic lifetime spawn count before crash and checks it after joined close, so
short-lived/pre-delivery reconstruction cannot hide between process snapshots.
The denied/crash/oversized service is test-only and never advertised to the model.
Selector-looking text is data. Host mappings/codecs remain outside protection.

## Production packaging check

```bash
flock "$lock" bash scripts/koog-build.sh -- \
  :integrations:koog:ktlintCheck :integrations:koog:detekt \
  :demos:koog-contained-tool:jar :demos:koog-contained-tool:portalCheck
python3 tests/consumers/koog-portal/check-worker-package.py \
  demos/koog-contained-tool/build/libs/koog-contained-tool-0.1.0-SNAPSHOT.jar
```

The package checker requires actual format2 resource metadata and matching ZIP
digest, generated dispatcher/implementation in the worker, no loadable host guest
classes, no Koog/Spring or host TextTools in the worker, and no test service in the
production manifests/ZIP. `--native-fixture` checks the separate native package.

## Bootstrap freshness and failure checks

```bash
python3 tests/consumers/koog-portal/bootstrap-test.py
python3 tests/consumers/koog-portal/producer-exclusion-test.py
python3 tests/consumers/koog-portal/fixture-ownership-test.py
```

This test acquires the fleet lock itself. It executes actual Gradle builds,
checks default-core exclusion, adds/changes/removes a temporary producer resource,
verifies its real plugin JAR contents after refresh, rejects missing/changed JARs,
wrong-worktree/version/missing receipts and cached demo evaluation, and injects
an actual producer build failure plus a missing selected task. Producer failure
must not admit the second stage; each failure must propagate its original output
and nonzero status. Inputs are exclusively created and mutated through retained
owned descriptors; exact no-clobber restoration attempts preserve unknown
replacements and original backups rather than deleting or overwriting them.
The ownership fault suite verifies real filesystem replacement/race conflicts.
The exclusion suite adds real Kotlin source and requires rejection of direct
excluded/conditionally skipped compilation plus all supported wrapper exclusion
forms before the selected stage. Individual logs/recovery files remain under the
printed same-filesystem `build/task10-evidence/round1/` evidence directories.

## Evidence and remaining gates

Task7/Task10 checkpoints/reports in the shared plan folder record exact commands and
run logs. Focused production quality and native fixture pass on Linux
7.2.9-x64v3-xanmod1 / OpenJDK25.0.4.1. Required baseline receipts and actual
generation/source-cache verification come from the integrated base; this consumer
does not substitute synthetic readiness records.

Production wiring requires independent scoped review before main integration.
Operator launch-profile/environment/descriptor
review, C4 resource preparation, combined release/publication and human usability
gates are pending. This is first-party bounded process-service evidence, not a
release-ready or general hostile-code/environment isolation claim.
