# Read-only execution diagnostics

## Contract

`ExecutionDiagnosticsSource` identifies one explicitly configured host operation
and provides an immutable `ExecutionDiagnostics` sample. Spring `DomainLifecycle`
and Koog `PortalToolAdapter` implement this interface. Direct core consumers use
`InProcessDomain.diagnostics()`; prepared Portal consumers use
`PreparedPortalOperation.diagnostics()`.

Diagnostics never install restrictions, prepare/start a runtime, contact a worker,
validate native resources, admit/retry/cancel work, or drain/retire ownership.
They are operational observations, not kernel attestation, permission authority,
or an arbitrary-code isolation boundary. Application diagnostic sources remain
trusted collaborators.

## Identity and lifecycle

- `UNKNOWN`, `PREPARING`, `READY`, `DRAINING`, `CLOSED`, and `FAILED` are the closed
  diagnostic states. Their projection comes from the existing owner, not counters.
- An in-process identity is its actual fixed domain generation. Portal identity
  is the input/manifests/runtime hash tuple from successful required readiness.
  A configured profile, failed handshake, or legacy test constructor cannot
  supply a ready generation; unavailable identity is explicit.
- A failed generation remains failed even after physical retirement. Failed
  status and `retirementComplete` describe different facts.
- `retirementComplete` requires the owner's canonical completion boundary.
  Polling cannot turn a previously incomplete drain into a successful drain,
  even after worker threads subsequently end. Captured samples never upgrade.
- Live-worker and unfinished-invocation counts retain the existing owner's
  meaning. In-process live counts include a retained resource-retirement thread.
  Portal live counts describe currently published worker processes; failed or
  preparing lifecycle still covers provisional ownership separately.

## Invocation counters

The owner records admitted requests, admission/dispatch rejections, cooperative
cancellation requests, producer-observed result completions, and execution-end
completions. Repeated requested cancellations are requests, not unique bodies.
Automatic generation-failure interruption is not an explicit cancellation request.
Known-not-started handles have an execution-end outcome too; rejected and completed
handle counts are not mutually exclusive partitions.

Counters are monotonic observations. A producer records a completion after its
existing completion call returns, so user completion callbacks can delay the
observed counter. Concurrent fields are not a globally atomic event log. Neither
delivered results nor end counters establish physical process/thread retirement.
Existing admission release, failure identity, cancellation and cleanup remain
owned by the original runtime.

## Spring and optional Actuator

Lifecycle sampling does not acquire the monitor held during preparation/drain.
The underlying genuine prepared domain supplies execution observations. Explicit
uncontained host characterization never becomes protected readiness.

`mazewall.diagnostics.enabled=true` opts into conditional observation beans.
Health and endpoint APIs are independently optional compile-only dependencies;
ordinary Spring consumers and the basic starter do not acquire an Actuator runtime.
The `mazewall` endpoint declares read-only access and has only a read operation.
HTTP/JMX exposure and authentication remain application configuration.

Both adapters inspect already-created singletons only. They never use bean lookup
that initializes lazy application bootstrap. Spring's own `SmartLifecycle`
discovery/startup semantics remain independent of these probes.

Health is UP only when every observed source is READY, has an available actual
generation, and reports a live worker. Empty, unavailable, preparing, draining and
closed sources are OUT_OF_SERVICE; failed sources are DOWN. Counter completion
cannot make them healthy. Source failures propagate rather than being omitted.

Endpoint records retain source bean name, explicit operation ID and typed sample;
duplicate operation IDs do not erase another owner. Results are immutable and
contain no payloads, resource paths, environment entries or exception text.

## Verification boundaries

Host state-machine and conditional-configuration tests prove projection,
immutability, missing-API behavior, passive polling and conservative health.
Owned-thread tests hold lexical cleanup across cancellation, observe actual thread
end, then require the canonical drain before complete diagnostics. Portal native
tests compare exposed generation against the actual launch/readiness tuple,
reject unverified generation, and observe actual child retirement. Published
consumer/showcase gates separately qualify normal dependency and packaging paths.
