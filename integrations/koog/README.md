# Owned tools for Koog 1.3

`PortalToolAdapter<A,R>` is an actual upstream `Tool<A,R>`. The host binds a
`PreparedPortalOperation<A,R>`, Koog type tokens, fixed name/description and
argument/result validators. `execute` submits once, suspends awaiting the owned
result stage and forwards coroutine cancellation to that same handle.

`PreparedPortalOperation` exposes only public `submit`; its Java constructor is
genuinely package-private, not merely Kotlin-internal/JVM-public. Ordinary external
Java/Kotlin consumers obtain it through `PortalRuntime.prepare`. Host mapping
remains outside protection, and this boundary is not hostile-host reflection or
classpath-injection isolation.

There is no in-process fallback, retry or replay. In particular, an unknown
remote outcome is not permission to repeat an operation. A stateful singleton
that loses its worker rejects subsequent calls until the application explicitly
creates a new runtime. Result delivery and coroutine cancellation do not prove
physical process termination; Portal retains the execution/retirement owner.

## Execution boundary

Host validation, model JSON parsing, mapping, generated host codecs and coroutine
delivery are **outside worker protection**. The generated service implementation
runs in the child after the base Portal's required, generation-bound readiness
exchange verifies the newly installed static baseline receipt. A resource
`PortalWorkerProfile` controls heap/cgroup resource limits; it is not a per-tool
filesystem/syscall policy selector. Model-provided identifiers confer no
operation or policy authority.

The first-party example is [the bounded ASCII transformer](../../demos/koog-contained-tool/README.md).
It uses a normal independent service API plus a dedicated worker source set.
Koog and Spring are not worker dependencies. This is a bounded first-party JVM
service integration, not a general hostile-code/shell/MCP boundary.

## Explicit thread-backed tools

`ThreadToolAdapter<A, R>` is a separate upstream `Tool<A, R>` for trusted
**synchronous** first-party bodies. The application passes its actual
`ManagedRuntime`, a fixed `InProcessDomain` prepared by that same owner, Koog type
tokens, name/description, argument/result validators and an `action: (A) -> R`.
There is no arbitrary-submitter constructor, execution-mode switch, suspend-tool
interceptor or process-to-thread fallback. The existing Portal API is unchanged.

Open the owner at trusted **platform-thread bootstrap**, with an explicitly
reviewed process-wide Tier 1 baseline, and prepare the domain before exposing the
tool. `execute` does neither. It validates arguments on the caller, submits once
on the original invoking coroutine thread, waits for that original handle and
validates the materialized result in the host. Wrong owner/domain pairs, nested
dispatch, overload and a draining/closed owner reject through the core API; a
READY diagnostic sample is not permission to bypass those checks.

Only `action` runs on the dedicated restricted platform worker. Model JSON,
validators, result delivery and coroutine continuations are outside its protected
body. Call from normal host coroutine contexts, not an unmanaged already-restricted
thread. Virtual-thread and Unconfined callers do not install restrictions or run
the body on their caller/carrier threads. Waiting uses
`runInterruptible(Dispatchers.IO)` and occupies **one host IO thread per concurrently
waiting admitted invocation**; keep finite domain admission limits.

### Bounded UTF-8 reader

This self-contained command-line example uses only synthetic files. Run it in its
own fresh Linux/JDK 25 JVM with `--enable-native-access=ALL-UNNAMED`, the optional
adapter, upstream Koog and kotlinx serialization on the classpath, and the Kotlin
serialization compiler plugin. The application chooses the directory and static
policy; a model-provided path is only data. Required native setup/denial failures
are fatal, not skipped or retried.

```kotlin
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.serialization.typeToken
import io.mazewall.enforcer.api.Policy
import io.mazewall.enforcer.api.RuntimeProfile
import io.mazewall.enforcer.api.WorkerPolicyBuilder
import io.mazewall.enforcer.api.execution.DomainLimits
import io.mazewall.enforcer.api.execution.DrainReport
import io.mazewall.enforcer.api.execution.ManagedRuntime
import io.mazewall.koog.ThreadToolAdapter
import io.mazewall.platform.syscall.Syscall
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration

@Serializable
data class ThreadReadArguments(val path: String)

fun main() {
    // Trusted platform-thread bootstrap, before application pools or tool exposure.
    check(!Thread.currentThread().isVirtual)
    val directory = Files.createTempDirectory("koog-read-note-")
    val allowed = Files.createDirectory(directory.resolve("allowed"))
    val note = Files.writeString(allowed.resolve("note.txt"), "public é🙂")
    val hostOnly = Files.writeString(directory.resolve("host-only.txt"), "HOST_ONLY_CANARY")
    val budget = Duration.ofSeconds(10)
    val runtime = ManagedRuntime.open(
        RuntimeProfile.HOTSPOT_JIT, Policy.NO_EXEC_HOTSPOT, budget, budget,
    )
    runtime.use {
        val domain = runtime.prepare(
            WorkerPolicyBuilder.denyList(RuntimeProfile.HOTSPOT_JIT)
                .allowJvmClasspath().allowFsRead(allowed).block(Syscall.SOCKET).build(),
            DomainLimits(workers = 1, maxAdmissions = 8),
        )
        val tool = ThreadToolAdapter<ThreadReadArguments, String>(
            runtime = runtime,
            domain = domain,
            argsType = typeToken<ThreadReadArguments>(),
            resultType = typeToken<String>(),
            name = "read_note",
            description = "Read at most 64 KiB of strict UTF-8 from the fixed allowed directory.",
            validateArguments = {
                require(it.path.isNotBlank() && '\u0000' !in it.path)
                require(it.path.toByteArray(Charsets.UTF_8).size <= 4096)
            },
            validateResult = { require(it.toByteArray(Charsets.UTF_8).size <= 65_536) },
            action = {
                val bytes = Files.newInputStream(Path.of(it.path)).use { input -> input.readNBytes(65_537) }
                require(bytes.size <= 65_536) { "File exceeds 64 KiB" }
                Charsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString()
            },
        )
        val registry = ToolRegistry { tool(tool) }
        runBlocking {
            val result = registry.getTool<ThreadToolAdapter<ThreadReadArguments, String>>()
                .execute(ThreadReadArguments(note.toString()))
            check(result == "public é🙂")
            check(Files.readString(hostOnly) == "HOST_ONLY_CANARY")
        }
        check(runtime.drain(budget) == DrainReport.Complete)
    } // Bounded close belongs to the application owner, never to execute().
}
```

The read consumes at most 65,537 bytes, rejects more than 65,536, and decodes strict
UTF-8; the independent result validator enforces the 65,536-byte output bound.
Blank/NUL or over-4,096-byte UTF-8 paths reject before submission. Classpath/runtime
reads remain available; the policy grants data reads only to the owned allowed
directory. Blocking `SOCKET` checks new socket creation, not every networking
capability or access through an already-open descriptor. No per-call policy
selection or permission widening occurs.

`Policy.NO_EXEC_HOTSPOT` is a permanent process-wide baseline: this example cannot
subsequently launch or replace Portal child JVMs. Closing the runtime does not undo
it. Run no-exec thread and Portal qualification in **separate JVMs**; do not weaken
the baseline to combine them. A Portal application needs its own explicitly
reviewed launch-compatible bootstrap.

Coroutine cancellation requests cooperative cancellation of the original handle;
it does **not** force execution end, lexical cleanup or physical worker death.
There is no retry/replay or replacement to hide stuck work. The CLI fails if its
canonical drain/close cannot complete. A long-lived application must store the
runtime in its lifecycle owner (not an invocation), report `DrainReport.Incomplete`
or `IncompleteDrainException`, retain that same owner and retry bounded drain when
cleanup can progress. Result delivery and diagnostic polling cannot substitute for
canonical complete drain and actual retirement.

This is a data-plane restriction for trusted synchronous code, **not** heap/tenant
or arbitrary Java/ACE isolation, hard CPU/RAM isolation or guaranteed termination.
Shared memory, helper executors/common pools, already-open FDs, detached work,
suspension and lazy/streaming results remain outside the action contract. See the
[security considerations](../../docs/internals/designs/core/security-considerations.md)
and the [published native consumer](../../tests/consumers/koog-portal/README.md).

## Integration status

The optional source adapter is selected with `includeKoogIntegration=true`;
`koogBootstrap=true` selects the adapter without evaluating the demo's plugin
buildscript. The actual two-stage [source workflow](../../demos/koog-contained-tool/README.md#source-build)
builds and verifies the codegen plugin before demo evaluation. Runtime and adapter
Maven publications are declared in the build. The producer uses root
Kotlin2.4.20-Beta1; the independent consumer uses stable Kotlin2.4.20 with no
metadata/prerelease skip flags.

## Read-only diagnostics

`PortalToolAdapter` implements `ExecutionDiagnosticsSource`. `operationId()` is
the fixed host-selected tool name; `diagnostics()` samples its existing prepared
broker without submitting or contacting the worker:

```kotlin
val snapshot = tool.diagnostics()
println("${tool.operationId()}: ${snapshot.state}, admitted=${snapshot.invocations.admitted}")
```

Identity is the input/manifests/runtime tuple accepted by required readiness.
Cancellation/result delivery is separate from execution end and runtime retirement.
The old package-private test construction path reports unavailable ownership,
never invented native readiness. See the [diagnostics contract](../../docs/internals/designs/core/execution-diagnostics.md).

`ThreadToolAdapter` implements the same interface: `operationId()` is its fixed
host-selected name and `diagnostics()` delegates only to the supplied domain's
actual in-process generation. Sampling does not prepare, submit, cancel or retire
anything, and READY does not establish call admissibility or denial provenance.

## Verification and delivery

For fast host-only adapter contracts:

```bash
./gradlew -PincludeKoogIntegration=true -PkoogBootstrap=true :integrations:koog:test
```

`ThreadToolAdapterTest` uses actual owned workers/domains and the upstream Koog
registry, with native installation mocked through repository-only test fixtures.
It covers validation placement, once-only dispatch, wrong/closed owners, nested
dispatch rejection and cancellation that retains admission until physical cleanup.
These host tests do **not** exercise kernel denials or replace static-analysis,
published-consumer or native retirement gates.

The complete adapter and published-consumer gates are separate:

```bash
./gradlew -PincludeKoogIntegration=true -PkoogBootstrap=true :integrations:koog:build \
  --configuration-cache-problems=fail
# The exact-publication/native gate owns its lock internally.
./gradlew optionalIntegrationCheck --console=plain --no-configuration-cache
```

The dedicated optional-integration CI lane checks actual adapter builds, strict
dependency state, cache reuse and source-bound Koog/JDBC/finished-Boot consumers.
Its failure blocks the existing required core job and package publication. The
existing master publication selects optional libraries explicitly; denied/crash
fixture guests remain local test artifacts, outside the advertised registry and
normal release selection. Native gates still require the documented Linux host
and outer-runtime permissions. Production reviewer/launch/cgroup authority and
human usability are separate from these known-fixture engineering checks.

The [containment contract demonstration](../../benchmarks/koog-containment/README.md)
documents controlled read/write/symlink, loopback-connection and process-launch
denials with outside-worker positive controls and physical worker-retirement checks.
The required parent gate rejects missing, duplicate or altered containment summaries.
Dangerous methods remain test-only; `uppercase_ascii` is still the sole advertised
production tool in that Portal demo. The separate thread consumer advertises only
`read_note`; socket/oversized/cancellation controls stay test-only. Its required
`KOOG_THREAD_V1_OK` summary is emitted only after assertions, canonical drains and
captured platform-worker death; the parent rejects missing, duplicate, altered or
contradictory thread summaries independently of the unchanged Portal summary.
This is bounded contract evidence, not an AISI benchmark score or general AI-agent
containment.
