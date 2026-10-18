# Read-only execution diagnostics

## Contract

`ExecutionDiagnosticsSource` identifies one explicitly configured host operation
and provides an immutable `ExecutionDiagnostics` sample. Spring `DomainLifecycle`
and Koog's `PortalToolAdapter` and `ThreadToolAdapter` implement this interface.
Direct core consumers use `InProcessDomain.diagnostics()`; prepared Portal consumers use
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
- `ThreadToolAdapter.diagnostics()` delegates only to its supplied domain and
  `operationId()` is the fixed host-selected tool name. READY does not prove a
  proposed call is admissible: owner/domain mismatch, nested dispatch, overload
  and draining/closed ownership still reject through the core API. The adapter
  never prepares or retires ownership as a side effect of sampling or execution.
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

Both adapters inspect fully published singletons only, excluding in-creation
early-reference factories. They never use bean lookup
that initializes lazy application bootstrap. Spring's own `SmartLifecycle`
discovery/startup semantics remain independent of these probes.

Health is UP only when every observed source is READY, has an available actual
generation, and reports a live worker. Empty, unavailable, preparing, draining and
closed sources are OUT_OF_SERVICE; failed sources are DOWN. Counter completion
cannot make them healthy. Source failures propagate rather than being omitted.
This is readiness of the configured execution owner, not evidence that every
required application route/proxy was bound. Required-binding validation and
production review remain explicit application bootstrap responsibilities.

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

The published Koog thread consumer checks actual filesystem/socket denials and
host/validator/virtual/Unconfined caller boundaries on dedicated platform workers.
Its permission classification remains inferred, not authenticated kernel
provenance. Cancellation holds lexical cleanup to observe live unfinished work
and incomplete drain before release, captured-thread death and canonical complete
drain. Host summary-corruption fixtures validate evidence parsing, not native
enforcement. See the [adapter guide](../../../../integrations/koog/README.md).

## Integration CI and source-bound delivery

The dedicated optional-integration lane builds both adapters with strict module
locks/checksums and fatal configuration-cache checks, then repeats to exercise
cache reuse. `optionalIntegrationCheck` owns its lock and uses a disposable normal
clone, committed producer inputs and a fresh exact local publication coordinate.
It executes actual generated Koog packaging, external constructor/public-facade
compilation, separate fresh JAR-only native Koog Portal/thread consumers,
ordinary-JAR JDBC/Security and finished-Boot showcases, and finished-JAR
ownership/review negatives. Historical consumer locks are not rewritten for the
new coordinate.

The thread phase follows the unchanged Portal phase as a separate child command
and requires one exact `KOOG_THREAD_V1_OK` summary after zero exit, including
cooperative cancellation and complete retirement. Both validated summaries enter
the existing parent JUnit artifact. The thread JVM's permanent no-exec Tier 1
baseline is incompatible with later Portal launches; neither probe shares that
bootstrap with Gradle or the other probe. No fallback, replay or widened authority
is supplied by diagnostics or a successful summary.

Failure blocks the existing required core job through an explicit result guard;
the core job cannot silently skip into a green required status. Master publication
selects the optional library flags with `koogBootstrap=true`, so no demo/denied
fixture artifact is selected for normal release. Existing credential bindings
remain at the original publication boundary. Raw receipts stay local; CI uploads
ordinary owning JUnit diagnostics, not fixture approvals or benchmark archives.
