# Owned Portal tools for Koog 1.3

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

## Verification and delivery

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
production tool. This is bounded contract evidence, not an AISI benchmark score or
general AI-agent containment.
