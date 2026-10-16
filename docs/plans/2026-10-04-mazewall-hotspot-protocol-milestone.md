# HotSpot readiness/control and pure lifecycle — milestone 3

The canonical collector explicitly selects production schema 2 through
`--protocol-version=2`. It requires strict interpreter/Serial admission, owned
full drain and the verified worker/queue/deadline bounds. Schema 1 stays the
separate experimental/replay contract; Kotlin's bounded reader selects its
expected schema explicitly. No public strategy/session dispatch is introduced.

## Readiness proof and identity

Pinned Gadget sorts data operators by ascending priority. OCI priority -1000
starts its ebpf image operator synchronously; ebpf Start opens native readers,
starts receiver owners and attaches programs before returning. The structured
operator's priority-100 OnStart therefore follows successful native Start.
Production adds a fresh context/fatal/exec/pidfd/start-generation check there,
following strict JVM discovery and established symbolizer/enrichment owners.

The early `session_started` record states a requested PID/profile and omits admitted
JVM identity. `collector_ready` carries actual PID generation, BOOTTIME sample,
exact runtime build/hash and provider pins. It is distinct from useful-frame
availability. Events from an already running target may precede the ready marker.
The real lifecycle projection is retained under profiler test resources with provenance.

`collector_error` carries native errno when available and preserves the Go error
chain on return. A held/unjoined event callback retains its writer/native leases;
production deliberately does not append an error through that still-active writer.
EOF without final summary and failed process exit therefore remain mandatory
failure paths. Kernel failures are not turned into readiness or warnings.

## Control and pure Kotlin decisions

SIGTERM/SIGINT feed the existing graceful request context. Explicit
`--control-stdin` owns a parent-lifetime relationship through borrowed stdin:
exactly stop newline requests drain while keeping the independent definition/
enrichment lifetime alive; EOF/invalid input aborts it. A finite poll worker joins
without closing the borrowed descriptor. Signal-only launches have no implicit
parent-death guarantee. The later owned Kotlin adapter will configure this pipe.

The pure sealed Kotlin state/event/effect machine implements Created, Starting,
Ready, Running, Finishing, Failed and Completed. It separates readiness from
user-work permission; validates session identity/order; preserves failure causes;
keeps one finish owner under duplicate finish/close; retains failed ownership until
explicit joined cleanup. Healthy summary alone cannot complete: stdout EOF, stderr
join and zero process exit must also be observed. Startup is 10 seconds, session
60 seconds, stop at most two remaining seconds; nanoTime subtraction is wrap-safe.
Semantic coverage remains incomplete even when physical completion succeeds.

Profiler sealed variants have a closed-set architecture test in the profiler
module. Enforcer ArchitectureTest deliberately imports only enforcer/platform
outputs and must not acquire a profiler dependency. Exhaustive dispatcher/progress
helpers have no I/O or interpreter-owned state mutation.

Strict/owned target exit now returns an explicit abort failure; a genuine pidfd
child-exit test observed the former erroneous nil result before correction.
The historical unowned unknown-identity schema-1 process-lifetime window keeps its
natural-exit behavior through an explicit scope decision. Permission and all
strict/production exit failures remain errors.

## Verification evidence

- Intended red tests: absent lifecycle/protocol implementation; actual child exit
  previously returned nil; no strict/historical lifetime decision existed; omitted
  production readiness/control implementation. Focused green: eight lifecycle,
  six protocol and one closed-set Kotlin tests, existing replay/asset tests, and
  Go readiness/permission/control-pipe/actual-exit race tests.
- A test JSONL fixture initially contained pretty-printed newlines and was corrected
  to valid one-record-per-line before protocol assertions. Initial lifecycle
  complexity warnings were removed with focused pure progress transitions; the
  production Detekt and merge warning-ratchet gates pass.
- Full experiment `make verify`, full profiler tests, native event finalization,
  8/5000 definition tails and auxiliary perf/netlink ownership controls pass.
- Explicit pinned-image build/verify/install produces collector/wire version 2,
  preserving ABI 2 and overlay fingerprint
  `e908bb2689936481f8a696dd2347457cc44b6344c1fa78ca65b37772032703eb`.
  Installed helper SHA-256:
  `848ab0de219fcf38d5ab575f6812b3cc59ee4aa860bb71ac02a782ce60e13d1e`.
  Gadget digest remains
  `sha256:3ae6007bdf574e1c082b5f185f38d3f60bb678d9e964f05a658da4ac5670de68`.
  Installed bundle passes Kotlin asset admission; stale version-1 bundle is refused.
- Real ordinary collector invocation refuses actual native permission errno 1,
  exits nonzero and emits no ready. Startup attempt `capture.R2WZSb` used a stale
  personal-cache image reference, fails explicitly without ready and is retained.
- Fresh ordinary-user pinned interpreter/Serial targets plus explicit privileged
  external collectors pass production ready/real SIGTERM drain acceptance:
  `capture.O5KKc5` has 400 terminals (384 resolved,16 unavailable); installed
  pinned-image helper `capture.P5Bsck` has 400 (386 resolved,14 unavailable).
  No marked operations precede ready, every event has its own terminal, event/
  definition/auxiliary joins and drains succeed, and loss remains partial.
- `./gradlew build` passes after an initial 300-second timeout and a completed
  bounded repeat. Backlog/whitespace checks and scoped local commit complete the gate.

Actual owned JVM process/pipe I/O, blocked readout/cancellation supervision and
public attachment sessions remain milestone 4. This milestone neither changes
AUTO/USER_NOTIF/Tier E behavior nor expands JVM/syscall/complete-loss support.
