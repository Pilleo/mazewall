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
  unclaimed resources; a reviewed nonexistent JVM member also rejects before readiness
  or body entry, with the actual contract reason and genuine owners closed.
- `verifyOriginControls` resolves the actual ordinary-loader exploded class directory
  with an explicit directory read grant, validates its selected class resource and
  records the bounded tree hash/generation. A selected-file-only grant rejects rather
  than authorizing the directory. An actual subclassed URLClassLoader rejects even
  with matching directory URLs/grants. These resolution controls enter no body.
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
fails the command. Native JDBC proof uses ordinary JARs; the separate exploded control
qualifies resolution, not an installed exploded JDBC worker. Current resources support
explicitly granted bounded ordinary exploded trees (4096 descendants, depth 64,
256 MiB, no descendant symlinks) and the pinned Boot 4.1.1 packaging. Other loaders
and unsupported packaging still reject; this fixture does not replay Boot proof. No complete ACE, shared
heap, preopened-FD, immutable-content/redefinition or file/network rollback claim.

## Reproduce locally

The complete current source workflow is:

```bash
./gradlew optionalIntegrationCheck --console=plain --no-configuration-cache
```

It owns its lock, stages current code under a fresh exact local version and executes
this fixture plus the finished-Boot and Koog consumers. For manual reproduction,
first stage the four current publications using the
[finished-Boot producer recipe](../spring-boot/README.md#manual-finished-boot-reproduction).
Then supply its exact absolute repository/version; serialize these manual commands
with the shared verification lock:

```bash
: "${repository:?Set the absolute staged Maven repository}"
: "${version:?Set its exact current producer version}"
./gradlew -p tests/consumers/spring-jdbc \
  -PconsumerVersion="$version" -PconsumerRepository="$repository" \
  verifyNative --no-configuration-cache
```

External authority and candidate files live in this consumer's `build/native-proof`.
Regenerate/approve after changing any JAR or deployment resource identity. Never supply
a class-resource digest as a finished-JAR digest. No Docker or full-build gate is implied.

## Optional normal starter / Security native variant

Reuse the same immutable published repository with the normal starter and optional
Security fixture; the fresh Security chain measures/reviews its own finished JAR:

```bash
./gradlew -p tests/consumers/spring-jdbc -PuseStarter=true \
  -PconsumerVersion="$version" -PconsumerRepository="$repository" \
  verifyNative verifySecurityNative presentationTest --no-configuration-cache
```

The optional Security source set adds Security7.1.1 explicitly; the ordinary source
set/runtime remains Security-free. Its external authority files live in
`build/security-native-proof`. Neither path launches a Boot executable or claims
nested-origin support. Wrap all fleet commands in the shared verification lock.

Both variants additionally assert that ordinary publications do not force Actuator
linkage. The actual public lifecycle supplies READY, held DRAINING and canonical
CLOSED diagnostic evidence, preserving operation identity and unfinished physical
ownership separately from caller delivery. The Security showcase prints these
checks beside its independently verified principal/SQL effects.
