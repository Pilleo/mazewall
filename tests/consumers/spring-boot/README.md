# Finished executable Boot adoption proof

The application is the [clean standalone demo](../../../demos/spring-boot-sandbox/README.md).
The root Kotlin/JUnit harness is an **external private known-fixture reviewer**
and disposable non-web `java -jar` acceptance test. It never modifies an operator's
deployment or unknown files: each case creates and preserves its own private temporary
deployment. Evidence destinations must be new. No sibling producer outputs, expanded
classpath, source-project substitution, fallback or required skip are accepted.

It checks Boot's final manifest/launcher version, selected actual application class and
all four nested Mazewall JARs against **real Maven publication bytes**. Producer sources
must match the supplied Git revision; tracked producer input hashes and demo/harness
source hashes are recorded. It measures genuine resources in the actual deployed JAR,
then externally writes a fixture approval of the whole outer SHA, canonical document SHA
and deployment generation. Missing/stale review and an externally reviewed unsupported
final required annotation fail. The successful native JVM proves the advertised singleton,
virtual-caller platform body, H2/report/template effects, denials, and physical retirement.

Review is explicitly **fixture trust**, not automatic production self-approval. The app's
measurement process cannot approve itself. The harness's private fixture authority is
not a signing service or arbitrary-JAR safety analysis. Digests identify artifacts; they
do not attest to trustworthy source, immutable loaded bytecode or all kernel activity.

## Reproduce in the assigned worktree

Use absolute paths for fleet work. Every Gradle, native acceptance and commit command is
serialized with the same lock. The following shell variables contain no credentials:

```bash
ROOT="$PWD"
REVISION=$(git rev-parse HEAD)
VERSION="0.1.0-cleanup.${REVISION:0:12}"
REPOSITORY="$ROOT/build/boot-maven-$REVISION"
# Independent checkouts default to the shared Git common-directory lock.
LOCK="$(git rev-parse --path-format=absolute --git-common-dir)/mazewall/verification.lock"
# The active fleet passes its existing verification.lock path instead.
mkdir -p "$(dirname "$LOCK")"
cd "$ROOT"

flock "$LOCK" ./gradlew -PincludeSpringIntegration=true \
  -I "$ROOT/tests/consumers/stage.init.gradle" \
  -PconsumerVersion="$VERSION" -PconsumerRepository="$REPOSITORY" \
  :platform:publishMavenJavaPublicationToConsumerBaselineRepository \
  :enforcer:publishMavenJavaPublicationToConsumerBaselineRepository \
  :integrations:spring:mazewall-spring-boot:publishMavenJavaPublicationToConsumerBaselineRepository \
  :integrations:spring:mazewall-spring-boot-starter:publishMavenJavaPublicationToConsumerBaselineRepository \
  --no-configuration-cache --no-parallel

flock "$LOCK" ./gradlew -p "$ROOT/demos/spring-boot-sandbox" \
  -PconsumerVersion="$VERSION" -PconsumerRepository="$REPOSITORY" \
  bootJar --no-configuration-cache --no-parallel

# Internally locking acceptance; do not wrap it in another flock on this path.
./gradlew consumerNativeCheck --tests '*SpringBoot*' \
  -PverificationLock="$LOCK" -PconsumerRevision="$REVISION" \
  -PconsumerVersion="$VERSION" -PconsumerRepository="$REPOSITORY" \
  -PconsumerBootJar="$ROOT/demos/spring-boot-sandbox/build/libs/report-application.jar" \
  -PconsumerEvidence="$ROOT/build/boot-evidence-$(date +%s%N)"
```

Choose a fresh local version/repository when producer inputs change. The producer
revision is the library source baseline, not a claim that new uncommitted demo sources
were present at that revision. Demo/harness source identity is recorded independently.
The producer must be committed and match the declared producer input inventory;
dirty or unidentified producer files fail before fixture approval. A private owned
clone can qualify an uncommitted cleanup snapshot with its own local commit and
fresh coordinate, without changing the operator's branch or historical publication.
This focused pass has its own source/JAR/evidence identity; the full root/container
gates retain their separate roles.

The evidence directory records every command/cwd/exit code, actual measurement and
native logs, missing/stale/unsupported negative logs, candidate documents, actual
external sidecars, sidecar SHA, deployed whole-JAR SHA, actual generation, publication
hashes and source input inventories. The printed deployment path is retained for
inspection. Caller completion is not labeled physical end, initialization/warmup is
not labeled protected body, and SQL rollback is not labeled rollback of external effects.

## Ownership and failure checks

Each missing/stale review, changed whole-JAR/template and unsupported-declaration
negative gets a new private deployment. There is no shared deployment to borrow or
restore. Inputs and external sidecars are created exclusively in those owned directories;
the original executable and operator checkout remain untouched. Evidence destinations
must be new. These are known private fixtures, not arbitrary operator-file rewrite or
production review capabilities.

Every executable invocation records argv/cwd **before launch**, direct separate stdout
and stderr files through retained exclusive descriptors, PID, exit code, timed-out and
reaped state. On timeout, partial streams and the timeout state are persisted before
bounded terminate/kill/reap of the acquired child and observed ancestry. Unrelated
processes are preserved. The combined log and all separate streams are retained.

Focused commands, using the same ROOT/LOCK/VERSION/REPOSITORY above:

```bash
flock "$LOCK" ./gradlew -p "$ROOT/demos/spring-boot-sandbox" \
  -PconsumerVersion="$VERSION" -PconsumerRepository="$REPOSITORY" \
  ownershipTest bootJar --no-configuration-cache --no-parallel
./gradlew consumerHarnessTest
# consumerNativeCheck above also executes all14 finished-JAR ownership negatives.
```

The Java gate uses real filesystem collisions, symlinks/hardlinks, replacement identities,
complete-byte publication and actual permission-denied cleanup. Kotlin checks real
no-clobber evidence acquisition and timeout/kill/reap with partial streams while a separately
owned unrelated process survives. The finished-JAR negatives exercise both measurement
entry points and operator-file/directory aliases; the original eight acceptance cases
remain intact. Required failures are not skipped when filesystem/permission features are
unavailable. The measurement pair's per-file atomicity/stable topology limits are in the
demo guide; external finished-JAR approval remains independent trusted fixture authority.

## Declared optional producer

Root settings already include both existing Spring producer modules under
`includeSpringIntegration=true`. The standalone executable continues resolving
published coordinates. `optional-modules.init.gradle` is retained in the historical
source/input inventory; current staging requires no extra inclusion patch.
