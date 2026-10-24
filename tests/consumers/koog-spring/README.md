# Published Koog → Spring Security/JDBC contract

This independent **test consumer** uses upstream Koog 1.3.0, stable Kotlin 2.4.20,
coroutines 1.10.2, Boot 4.1.1 dependency management, Spring 7.0.9, Security 7.1.1
and test-only H2 2.4.240 on Linux/JDK 25. All six MazeWall modules resolve from
an explicit, exact-version Maven publication repository, never sibling sources.
It adds no production adapter or library API.

## One protected execution owner

The consumer-local `SpringServiceTool` is an upstream
`Tool<SpringServiceArguments, String>`. Koog requires an object argument schema;
its sole `mode` field is data, never identity, bean, method or policy authority.
JSON rejects unknown keys. The Tool bounds nonblank, NUL-free input at 4,096 UTF-8
bytes and materialized output at 65,536 UTF-8 bytes, then calls the **bound Spring
proxy**, not its raw target:

```kotlin
val outcome = runInterruptible { runCatching { service.run(mode) } }
val result = outcome.getOrThrow()
```

There is deliberately **no dispatcher argument**: Spring must inspect the actual
original caller for existing transactions/resources and capture that caller's
Security context before its owned-worker hop. Boxing business outcomes inside
the interruptible region and unwrapping afterward preserves an original business
`InterruptedException` without relabeling it coroutine cancellation.

The application chooses a suitable host platform or virtual caller **before**
invocation; one host waiter blocks per active synchronous call. This is not a
general nonblocking library adapter and is unsuitable for an event-loop thread.
Do not stack `ThreadToolAdapter` or `PortalToolAdapter` around this Spring proxy:
Spring remains the sole protected dispatcher. The existing supported Security
bridge captures context references, not deep immutable authentication snapshots;
concurrent mutation of captured context is unsupported.

## Required assertions

Each caller variant runs in a separate, fresh, JAR-only JVM. A platform bootstrap
resolves genuine resources for the finished ordinary JAR and fixed read-only
template. A separate Gradle fixture-review step pins the measured candidate,
JAR SHA-256 and resource generation. That known-fixture reviewer is **not
production approval authority**. The application explicitly opens one
`ManagedRuntime` with the process-wide `Policy.NO_EXEC_HOTSPOT` baseline and one
`DomainLifecycle` with `DomainLimits(1, 4)`; neither the Tool nor starter installs
the baseline. Closing cannot undo permanent kernel restrictions.

The actual upstream registry invokes the approved singleton through unchanged
dispatch, JDBC transaction, diagnosis and pre/post-authorization advice. Assertions
cover:

- Admin SQL commits once; unauthenticated input enters no body and writes no SQL.
- A non-admin post-authorization denial rolls back real SQL; a later admin call
  reuses the same clean platform worker.
- Business interruption retains exact identity and an active caller coroutine.
- An original-caller transaction rejects before admission/body; it is not moved.
- A real sibling-file open is denied and its SQL rolls back; the host can still
  read that sibling before and after.
- Coroutine cancellation reaches the same owned invocation. Held lexical cleanup
  keeps the worker and genuine resources live through an incomplete 50 ms drain.
  Release, actual captured-worker death, complete canonical drain, closed resources
  and cancelled SQL rollback are all required before the success summary.

The runtime budget is ten seconds; latch/join waits remain five seconds.
Cancellation runs **last**, because drain permanently closes admission. There is
no retry, replay, fallback or claim that cancelled delivery proves execution end.
Exactly one complete `KOOG_SPRING_V1_OK` summary for each caller is required by the
parent; missing, duplicate, truncated, wrong-caller or false-success output fails.

This qualifies trusted synchronous first-party methods in immutable ordinary
JARs. It adds no Boot-web/reactive/suspend-method, general plugin/ACE, heap/tenant,
hard CPU/RAM or forced-termination guarantee. Direct/self invocation, shared memory,
helper executors, preopened descriptors and detached/lazy work remain outside the
proxy body. Permission diagnostics are inferred, not authenticated kernel proof.

## Verification

The existing acceptance gate stages exact committed publications, appends checksums
**only** for those six new first-party coordinates, creates a new coordinate's
strict lock namespace and executes host tests plus both native variants. Historical
locks and all external checksum approvals remain unchanged. It retains the existing
Portal, thread, JDBC/Security and finished-Boot gates and owns its lock internally:

```bash
./gradlew consumerHarnessTest --tests '*KoogSpring*'
./gradlew optionalIntegrationCheck --console=plain --no-configuration-cache
```

For a pre-staged candidate with its reviewed checksums and strict locks already
present, set the absolute repository path and pinned version explicitly:

```bash
./gradlew -p tests/consumers/koog-spring \
  -PconsumerRepository=/absolute/path/to/staged/repository \
  -PconsumerVersion=0.1.0-integration.723e1b4e6b88 \
  test verifyPublishedClosure verifyFreshNative --no-configuration-cache
```

Individual native tasks are `verifyFreshPlatformNative` and
`verifyFreshVirtualNative`. Required kernel/setup/denial failures fail the task;
there are no conditional skips. See the [Koog guide](../../../integrations/koog/README.md)
and [Spring contract](../../../integrations/spring/mazewall-spring-boot/S3-contract.md).
