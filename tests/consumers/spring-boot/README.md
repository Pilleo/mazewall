# Finished executable Boot adoption proof

The application is the [clean standalone demo](../../../demos/spring-boot-sandbox/README.md).
This Python-standard-library harness is an **external private known-fixture reviewer**
and disposable non-web `java -jar` acceptance test. It never modifies an operator's
deployment or unknown files: each run creates and preserves its own private `mkdtemp`
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
ROOT=/home/leanid/Documents/code/java/jseccomp/.worktrees/fleet-20261008-boot-demo
LOCK=/home/leanid/Documents/code/java/jseccomp/.superpowers/sdd/2026-10-06-mazewall-integration-foundation-plan/verification.lock
VERSION=0.1.0-task13.a8307928
REPOSITORY="$ROOT/build/task13-maven"
cd "$ROOT"

flock "$LOCK" ./gradlew -PincludeSpringIntegration=true \
  -I "$ROOT/tests/consumers/spring-boot/optional-modules.init.gradle" \
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

flock "$LOCK" python3 "$ROOT/tests/consumers/spring-boot/verify.py" \
  --jar "$ROOT/demos/spring-boot-sandbox/build/libs/report-application.jar" \
  --java /home/leanid/.sdkman/candidates/java/25.4.4+1-graal/bin/java \
  --evidence "$ROOT/build/task13-evidence-new" \
  --revision a83079282691a98d0a5b07df55da6a1176db7806 \
  --version "$VERSION" --repository "$REPOSITORY"
```

Choose a fresh local version/repository when producer inputs change. The producer
revision is the library source baseline, not a claim that new uncommitted demo sources
were present at that revision. Demo/harness source identity is recorded independently.
No full root build or container gate is implied by this focused consumer pass.

The evidence directory records every command/cwd/exit code, actual measurement and
native logs, missing/stale/unsupported negative logs, candidate documents, actual
external sidecars, sidecar SHA, deployed whole-JAR SHA, actual generation, publication
hashes and source input inventories. The printed deployment path is retained for
inspection. Caller completion is not labeled physical end, initialization/warmup is
not labeled protected body, and SQL rollback is not labeled rollback of external effects.

## Ownership and failure checks

`fixture_io.py` creates private-fixture files with genuine `O_EXCL|O_NOFOLLOW`
descriptors. It retains those acquisitions for sidecar/JAR/template mutations, checks
the exact named inode and single-link identity, and restores every independent original
in a failure boundary. Replaced/symlink/hardlink names are preserved; restoration failures
are attached to the original failure rather than masking it. A restoration cannot stop
another independent restore. These are private fixture tools, not general operator-file
rewrite or production review capabilities.

Every executable invocation records argv/cwd **before launch**, direct separate stdout
and stderr files, PID, exit code, timed-out and reaped state. On a timeout, partial streams
and the timeout state are persisted before signalling the exact owned `Popen` child;
bounded terminate/kill/wait reaps it and records the final outcome. No process group or
unrelated process is signalled. The combined log and all separate streams are retained.

Focused commands, using the same ROOT/LOCK/VERSION/REPOSITORY above:

```bash
flock "$LOCK" ./gradlew -p "$ROOT/demos/spring-boot-sandbox" \
  -PconsumerVersion="$VERSION" -PconsumerRepository="$REPOSITORY" \
  ownershipTest bootJar --no-configuration-cache --no-parallel
cd "$ROOT/tests/consumers/spring-boot"
flock "$LOCK" python3 -m unittest -v test_fixture_io
flock "$LOCK" python3 "$ROOT/tests/consumers/spring-boot/test_measurement.py" \
  --jar "$ROOT/demos/spring-boot-sandbox/build/libs/report-application.jar" \
  --java /home/leanid/.sdkman/candidates/java/25.4.4+1-graal/bin/java \
  --evidence "$ROOT/build/task13-measurement-new"
```

The Java gate uses real filesystem collisions, symlinks/hardlinks, replacement identities,
complete-byte publication and actual permission-denied cleanup. Python checks genuine
restoration failures and a real timeout/kill/reap with partial streams while a separately
owned unrelated process survives. The finished-JAR negatives exercise both measurement
entry points and operator-file/directory aliases; the original eight acceptance cases
remain intact. Required failures are not skipped when filesystem/permission features are
unavailable. The measurement pair's per-file atomicity/stable topology limits are in the
demo guide; external finished-JAR approval remains independent trusted fixture authority.

## Coordinator-only root patch

The executable already builds standalone; no root inclusion is needed for the proof.
To register the existing optional normal starter under the Spring-integration opt-in,
the minimal root settings patch is:

```diff
 if (providers.gradleProperty("includeSpringIntegration").orNull == "true") {
     include(":integrations:spring:mazewall-spring-boot")
+    include(":integrations:spring:mazewall-spring-boot-starter")
 }
```

`optional-modules.init.gradle` supplies this scoped inclusion for local publication.
The standalone executable continues resolving published coordinates; it needs no root
catalog/index edit or application auto-configuration API delta.
