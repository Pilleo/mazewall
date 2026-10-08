# Published ordinary-JAR Spring JDBC adoption fixture

This standalone Java 25 consumer resolves **published Maven artifacts**, never a
project substitution or source output. The application and its foreign-origin
negative fixture are ordinary finished JARs. It uses Boot 4.1.1 dependency management,
Spring 7.0.9 and real H2 2.4.240; the adapter publication does not depend on H2.

`measureCandidate` resolves genuine resources in an uncontained trusted JVM and emits
candidate documents only. `approveFixture` is the explicit external test authority,
after the finished JAR; it hashes the actual JAR/document and approves the known fixed
fixture's deployment generation. `verifyNative` loads those external expected identities
and document approval in another **disposable** JVM. This fixture reviewer is not a
production review service and must never be turned into automatic application approval.

The native JVM explicitly opens its application-owned runtime with the process-safe
NO_EXEC_HOTSPOT baseline. No adapter/startup side effect creates or weakens that baseline.
It resolves `PreparedOperationResources` and prepares through `DomainLifecycle`'s typed
owner constructor. It retains the existing singleton JDK proxy and advisor chain.

Assertions cover:

- Actual owned platform-worker call; real same-datasource `JdbcTemplate` insert/query/commit.
- Exact template read; actual denied template mutation and sibling read, unchanged bytes,
  actual JDBC rollback rows and transaction receipts. The failed opens are observed;
  library diagnostics remain `INFERRED_PERMISSION_FAILURE`, not confirmed kernel provenance.
- Uncaptured Classes from the same JAR and a distinct actual JAR reject even when reviews
  repeat identical policy/JAR-hash/resource-generation metadata.
- Virtual caller interruption requests cooperative cancellation. Held lexical cleanup
  keeps the actual worker and genuine resource owner live, with Incomplete drain. Release,
  actual captured physical-worker join, complete runtime drain and retired public owner
  demonstrate physical end separately from caller delivery.
- Stop-before-start and externally reviewed stale implementation digest close actual
  unclaimed resources; failed preparation never becomes ready.
- Published normal starter can run the same fixture without Security; the ordinary
  consumer explicitly asserts Security absence/no linkage at runtime.
- Explicit `verifySecurityNative` uses a separate ordinary finished security JAR and
  actual overridden implementation Class/typed owner/external review. Actual upstream
  Pre/PostAuthorize denies Bob after SQL but before commit, rolls back real rows, then
  Admin reuses the same worker. Ordinary error, filesystem denial and virtual-caller
  cancel each restore clean context; real captured-worker physical retirement is separate.
  It uses the actual public bridge. Fixture-only instrumentation observes before/after
  context and does not invent worker/descriptor ownership or emulate kernel success.

No assumptions/skips/fallbacks: required native feature, permission or assertion failure
fails the command. This is ordinary-JAR proof only. Executable nested Boot and exploded
origins remain unsupported in this reviewed baseline and reject. No complete ACE, shared
heap, preopened-FD, immutable-content/redefinition or file/network rollback claim.

## Reproduce locally

Run every command under the fleet's shared verification lock. In the assigned worktree:

```bash
./gradlew -PincludeSpringIntegration=true \
  -I tests/consumers/stage.init.gradle \
  -PconsumerVersion=0.1.0-task9.92da1536 \
  -PconsumerRepository="$PWD/build/task9-maven" \
  :platform:publishMavenJavaPublicationToConsumerBaselineRepository \
  :enforcer:publishMavenJavaPublicationToConsumerBaselineRepository \
  :integrations:spring:mazewall-spring-boot:publishMavenJavaPublicationToConsumerBaselineRepository

./gradlew -p tests/consumers/spring-jdbc \
  -PconsumerVersion=0.1.0-task9.92da1536 \
  -PconsumerRepository="$PWD/build/task9-maven" verifyNative
```

External authority and candidate files live in this consumer's `build/native-proof`.
Regenerate/approve after changing any JAR or deployment resource identity. Never supply
a class-resource digest as a finished-JAR digest. No Docker or full-build gate is implied.

## Optional normal starter / Security native variant

Include only the task-owned starter using the init script while the root settings
patch awaits coordinator integration; publish real normal JARs to a fresh local version:

```bash
./gradlew -PincludeSpringIntegration=true \
  -I tests/consumers/spring-jdbc/optional-modules.init.gradle \
  -I tests/consumers/stage.init.gradle \
  -PconsumerVersion=0.1.0-task9.s3-final \
  -PconsumerRepository="$PWD/build/task9-s3-final-maven" \
  :platform:publishMavenJavaPublicationToConsumerBaselineRepository \
  :enforcer:publishMavenJavaPublicationToConsumerBaselineRepository \
  :integrations:spring:mazewall-spring-boot:publishMavenJavaPublicationToConsumerBaselineRepository \
  :integrations:spring:mazewall-spring-boot-starter:publishMavenJavaPublicationToConsumerBaselineRepository

./gradlew -p tests/consumers/spring-jdbc -PuseStarter=true \
  -PconsumerVersion=0.1.0-task9.s3-final \
  -PconsumerRepository="$PWD/build/task9-s3-final-maven" verifyNative verifySecurityNative
```

The optional Security source set adds Security7.1.1 explicitly; the ordinary source
set/runtime remains Security-free. Its external authority files live in
`build/security-native-proof`. Neither path launches a Boot executable or claims
nested-origin support. Wrap all fleet commands in the shared verification lock.
