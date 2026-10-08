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

Focused tests, actual generated packaging, stable Kotlin2.4.20/JDK25 published
consumer and fresh-directory native verification pass in the scoped fixture.
Production root opt-in/plugin wiring and Portal runtime Maven publication need
the coordinator patches recorded in task-7-report.md. The producer still uses
the root Kotlin2.4.20-Beta1; no metadata/prerelease skip flags are used.

Operator launch-profile/environment/descriptor review, C4 resource preparation,
combined publication/release gates and human usability remain pending. These
checks do not establish credential/environment isolation or release closure.
