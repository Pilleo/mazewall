# Tier E split-privilege deployment contract

**Status:** Implemented foundation; rootful two-container qualification remains required.

## End goal

Run a JVM application as a non-root user with no BPF capabilities while a separate privileged
collector performs Tier E attachment, ring draining, dictionary joining, acknowledgement, and
terminal evidence emission. The resulting evidence has the same decoded stack/syscall meaning as
the development topology without making the application container privileged. This contract is
platform-neutral: a future node agent need only implement registration and artifact mounts.

## Trust and privilege boundary

| Component | Runs as | May do | Must not do |
|---|---|---|---|
| Target JVM | application UID; no BPF capabilities | load JVMTI agent, emit markers, write terminal dictionary | load BPF, attach probes, choose arbitrary target |
| Collector | privileged sidecar or node component | validate registration, load BPF, drain and resolve evidence | infer target from untrusted request or infer closure from quiet ring |
| Deployment operator | trusted control plane | create immutable registration and mounts | grant BPF privileges to target JVM |

`policy_eligibility` remains `false`: Tier E output is profiling evidence, never direct policy
input.

## Explicit target registration

The collector is configured with one operator-owned registration file through
`TIER_E_TARGET_REGISTRATION`:

```properties
version=1
registration_id=orders
pid=4242
session_tag=4d5a
process_incarnation=9876
marker_path=/collector/agent/libmazewall_stack_agent.so
marker_offset=20
artifact_file=orders.mzse
```

`process_incarnation` is Linux `/proc/<pid>/stat` start-time. The collector requires an exact
match before installation and checks it again at terminalization. An absent PID means the target
exited; a different start-time means PID reuse and makes evidence incomplete. This is a current-boot
identity. Persisted registrations must be bound to host boot ID before reuse.
The 2026-09-23 bounded normal-host probe did not observe host PID reuse in 256
real process lifetimes (`pid_max=4194304`); the earlier rootful test forced a
**simulated** observed-incarnation change. A later
[disposable-VM fixture](../../evidence/tier-e-real-host-pid-reuse-20260924/README.md)
observed real guest-host PID reuse while the rootful collector was paused,
then verified that it rejected the new incarnation. This qualifies the
identity-rejection behavior for the observed window, not the natural frequency
or latency of an unpaused race. The normal host PID controls were unchanged.

For experimental marker v2 only, registration `version=2` adds a safe sibling
`marker_proof_file=orders.marker-proof`. The native agent's launch plan adds
`marker-version=v2,marker-version-proof=/run/mazewall/definitions/orders.marker-proof`.
At startup, after native bind setup, the agent atomically writes a bounded format-v1
proof containing its selected marker version, session tag, and `/proc/self/stat`
start time. The collector reads it once with `NOFOLLOW_LINKS`, verifies v2 and
the registered session/process incarnation, and rechecks live identity before
constructing the BPF engine. Missing, stale, malformed, symlinked, or v1-selected
proofs reject `ATTACH` with an incomplete `MARKER_VERSION_MISMATCH` terminal
report **before** declared workload admission. Existing registration v1 remains
unchanged. See [retained rootful proof fixtures](../../evidence/tier-e-marker-proof-20260923/README.md).

This is an agent-authored diagnostic selection proof, not authentication against
a malicious target that can forge files under its own UID. Tier E remains
`policy_eligibility=false`.

`pid` is the host PID, not the container-local PID. `bpf_get_current_pid_tgid()` reports the
host-global identity, so the privileged collector must resolve `/proc/<host-pid>` from the host
PID namespace and the target control client must use that same registered value. A container-local
PID such as `1` is insufficient even when the collector can see the target through a joined PID
namespace: it would correctly fail the BPF target-TGID filter and yield no attribution.

Control-protocol v1 stays compatible. The daemon accepts an `ATTACH` only when PID, session tag,
marker path, and marker offset exactly match the registration. A target client cannot redirect the privileged
collector through legacy request fields.

The registration and collector artifact root are operator-owned. An application-writable volume
alone is not authorization: a future dynamic provider must bind any post-start registration to the
intended container/cgroup identity before collector admission.

## Target launch contract

The target needs only native-agent options. `TierETargetLaunchPlan` derives:

```text
session=4d5a,session-bound-definitions,definitions=/run/mazewall/definitions/orders.mzse
```

`session-bound-definitions` writes one terminal `MZSE v2` envelope at agent unload and does not
start the live publisher. `live-definitions` remains a separate opt-in for continuous snapshots.
The payload stays raw `MZSD v2`; existing readers remain compatible.
When the v2 hybrid collector explicitly enables `TIER_E_LIVE_SNAPSHOT_ADMISSION=1`, it reads
those existing 100 ms native snapshots as bounded, session-bound dictionary prefixes before
terminalization. This does not introduce incremental wire delivery or allow a live snapshot
to prove producer closure; the terminal artifact and lifecycle barrier remain authoritative.

The target sends `ATTACH` and `DETACH` over the shared `TIER_E_SOCKET` Unix-domain socket via
`TierETargetControlClient`. The client derives PID, session tag, marker path, marker offset, and
emission mode from the registration; it has no arbitrary-target API. The daemon preserves the v1
modified-UTF frame protocol and its 30-second idle and whole-frame deadlines over this socket.
The daemon sets the socket to owner-only `0600` by default. A cross-UID target requires the operator
to set `TIER_E_SOCKET_GID`; the daemon then assigns that group and uses `0660`. The socket directory
must itself be operator-owned and non-writable by unrelated identities.

## Artifact handoff and closure

The target and collector share only a narrow artifact volume. They may mount it at different paths:
the target uses its launch-plan path, while the collector resolves only the approved
`artifact_file` below `TIER_E_DEFINITION_ROOT`.

At finish the collector checks process identity, opens the approved artifact once with
`NOFOLLOW_LINKS`, decodes that same descriptor with bounded limits, then requires MZSE v2, matching
session epoch, and the producer-closed flag. It passes the prevalidated immutable contents into
`TierECollectorRuntime`; the runtime never reopens that path.

For registered targets, `TierETargetExitCoordinator` observes the host PID incarnation. On exit or
PID reuse it finalizes exactly once. Exit waits up to 30 seconds for the producer-closed artifact;
reuse never admits an artifact. The daemon remains available after finalization, reports its
terminal state through `STATUS`, and may attach a later valid registration.

When `TIER_E_EVIDENCE_ROOT` is configured, terminal evidence is atomically written as
`<registration-id>-<session-epoch>.tier-e.json`. The report includes the host PID, process
incarnation, session epoch, validated artifact byte length and SHA-256, terminal reason, resolved
count, every verdict dimension, and `policy_eligibility=false`. Its digest is computed from the
same bounded bytes read through the validated descriptor, not by reopening the artifact path.

A malformed, stale, symlinked, unclosed, unavailable, or PID-reused artifact is fail-closed. It
cannot produce complete transport or lifecycle evidence. An empty ring is never producer-closure
proof.

### Opt-in per-syscall evidence handoff (Kubescape SBoB PoC)

Set `TIER_E_ALL_TARGET_CAPTURE=1` on the **collector** with
`TIER_E_TARGET_REGISTRATION` (v1), `TIER_E_DEFINITION_ROOT`, and
`TIER_E_EVIDENCE_ROOT`. Launch the registered JVM with the existing v1 JVMTI
agent, `session-bound-definitions`, and `mode=full-stream`; attach with the
registered host PID, marker path/offset and session tag. The default marker-gated
Tier E mode is unchanged. The collector enables its independent capture gate
only after attaching, disables it before producer quiescence and final ring
drain, then validates the closed MZSE dictionary as above.

For an admitted, completed registered session, the collector writes
`<registration-id>-<session-epoch>.events.jsonl` beneath the evidence root.
Only consume the final filename after atomic publication; a `.tmp` file is
incomplete. The existing `.tier-e.json` terminal report is written separately.
The event file has one UTF-8 JSON object per line:

| Row | Fields and interpretation |
|---|---|
| `header` | `schema_version=1`, registration ID, decimal session epoch, target host PID, process incarnation, `clock_domain=CLOCK_MONOTONIC`, `observation_scope=SYS_ENTER_TRACEPOINT`, `marker_version=v1`, `stack_transport=session-bound-mzse-v2`, `policy_eligibility=false` |
| `event` | Registration/session and decimal ordinal; original unsigned kernel timestamp (nanoseconds), host TGID/TID, syscall number, **six unsigned raw arguments** (decimal strings), raw `capture_flags`, `arguments_valid`, invocation ID (or null), attribution and resolution status, stack/execution IDs and context when known, `frames` with class-loader identity, class, method, descriptor, bytecode location and frame kind; always `policy_eligibility=false` |
| `terminal` | Event/attributed/unattributed counts, observed ring loss, ambient and marker-state-unavailable counts, admitted and submitted kernel counts, terminal reason, separate `capture_complete`, `transport_complete`, `attribution_complete`, `export_complete`, `policy_eligibility=false` |

Repeated syscall entries are separate rows. Unknown or unavailable attribution
keeps the raw observation with an empty frame array and explicit status; it
does not imply capture loss. `capture_complete` requires a complete terminal
reason, valid capture/lifecycle state, zero observed loss and equality of
admitted, submitted and delivered row counts. `export_complete` means only
that this bounded file was completely written and published; it does **not**
imply complete capture, transport, attribution or producer closure. If export
fails or exceeds its bounds (64 MiB or 100,000 rows by default), no final
`.events.jsonl` is published; the separate terminal report marks delivery
failure and an owned `.tmp` may remain. Consumers must reconcile the terminal
counts and flags and must not infer completeness from the presence of rows.

The scope is syscall entries on the registered target's threads that reach the
attached `sys_enter` probe **after capture admission and before admission
shutdown**. It excludes earlier/later syscalls, calls denied before that probe,
and syscall activity in another process or outside the registered host PID.
Java frames are available only when the v1 agent has published a matching
invocation and its terminal dictionary resolves it. This evidence is
diagnostic, never a directly eligible sandbox policy. The rootful source-dirty
development fixture `TierEAllTargetDaemonIntegrationTest` exercises the
separate daemon, target registration, two Java call paths for one syscall,
ambient rows and atomic file handoff; it does not qualify a two-container
deployment or supersede the source-pinned Tier E acceptance receipts.

## Collector lifecycle

The privileged component owns the existing `TierEProfilerSession` and `TierECollectorRuntime`;
split deployment does not fork attribution logic.

```text
operator registration
  -> exact ATTACH admission and process identity check
-> BPF install and marker attach
  -> collector-owned ring drain and dictionary join
  -> marker detach and quiescence drain
  -> one-descriptor terminal artifact validation
  -> runtime finalization and immutable evidence
```

Capture-domain validity, transport completeness, attribution coverage, dictionary integrity,
lifecycle validity, and runtime overhead remain independent verdict dimensions.

The successful-exec invalidation program is attached through `BPF_RAW_TRACEPOINT_OPEN` to
`sched_process_exec`. Its instructions are tracepoint-context independent and only clear the
current task's marker storage after target/session validation. This avoids a host-specific
`PERF_EVENT_IOC_SET_BPF` `EEXIST` failure observed for the lifecycle perf descriptor without
changing exec semantics. The marker uprobe and selected `sys_enter` paths retain their existing
attachments.

## Qualification still required

Before this is a supported production route, retain a rootful two-container fixture proving:

1. Target runs non-root and lacks BPF-related capabilities.
2. Collector alone loads BPF and attaches only to the registered target.
3. Target writes a closed session-bound artifact into the shared volume.
4. Collector resolves observations and rejects stale-session and PID-reused registrations.
5. Artifact retains image digests, capabilities, mounts, kernel, logs, and terminal dimensions.

The current rootful fixture proves host-PID registration, non-root target execution, privileged
collector attachment, active ring draining, and closed-artifact finalization by a separate
unprivileged control client after the target exits. It requires at least one resolved syscall and
requires `COMPLETE` terminal evidence with capture-domain, transport, attribution, dictionary, and
lifecycle dimensions all true. Active polling alone would not meet this qualification.

The fixture must prove the restrictive socket owner/group/mode admits the target UID but rejects an
unrelated pod/container user.

The independent 72-path coverage oracle is retained separately from this lifecycle fixture. The
split-topology short fixture now verifies the collector-owned bounded acknowledged-edge manifest
against that oracle at terminalization. Target-side operation counters and dictionary-definition
counts are not sufficient substitutes. Coverage `PASS` may coexist with an `INCOMPLETE` fault
terminal; it cannot imply complete transport or policy eligibility.

Rootless output is `INCOMPLETE`, not privileged evidence.

## Container build ownership and bounded exports

Local developer container builds run as the caller's UID/GID only after a
write-and-stat probe proves that bind-mount ownership maps back to that caller.
They use a project/toolchain-keyed container-specific Gradle cache and a
separate NVD database, never the host's ordinary `~/.gradle`. Rootless Podman
uses `keep-id`; an unverified or remote checkout mapping fails before the
writable checkout mount is admitted. Short JUnit rootful endpoint tests are
enabled on a reachable rootful Docker or Podman server and skipped otherwise;
rootless development containers remain distinct from privileged evidence.

The explicit Tier E rootful gate stages the current tracked and untracked
source through the engine into a per-run volume, builds with a persistent
engine-owned Gradle volume, and exports inspected artifacts via the client.
The transfer includes the tracked root `.editorconfig`: omitting it made a
disposable checkout apply default ktlint rules to otherwise unchanged
integration tests. The historical raw-key-only gate retains its failed v1
verdict on v2 evidence. The clean Kotlin Docker gate now passes the separately
versioned comparable phase contract (disjoint key construction and lookup,
exact capture counts and unchanged historical total bounds); see
[`tier-e-comparable-phase-v2-20260926`](../../evidence/tier-e-comparable-phase-v2-20260926/README.md).
For a separate fresh phase invocation, `tierERootfulPhaseProbe` now uses the
same absolute native build path as the full gate. The gate's explicit
`tierEPhaseArtifacts` option accepts only a `COMPLETE`, policy-ineligible
phase terminal with all six unsymlinked raw CTest samples and matching phase
verdicts. It checks the exact Git revision/source digest, Docker image ID,
native agent **and Java workload class SHA-256s**, compiler/JDK identity and
native CPU placement against its own build, then replays both modes against
its own unchanged v2 baseline. Phase input is read-only; imported copies and
the original terminal hash are retained in the aggregate evidence. A failing
or foreign phase receipt retains the gate's owned runner/volume and cannot
replace the 39 native CTests or fresh rootful/JVM integration suites. The
clean [separated gate receipt](../../evidence/tier-e-separated-phase-gate-20260927/README.md)
records one such fully bound passing run; default Tier E policy eligibility
remains false.
The rootful build has no writable host checkout or normal Gradle-cache mount.
The explicit Gradle native task mounts the native source read-only and writes
only to an isolated caller-owned sibling build directory. Its cache output
contains a verified agent, native test executables and Java workload classes;
no CTest result or CMake intermediate directory is stored as a cache hit.
When selected, the rootful and phase controllers mount that package
**read-only**, configure CMake metadata in each owned run volume, check
source/image/toolchain/build-option and per-file SHA-256 identities, then run
39 CTests or the six timing CTests afresh. Trustworthy bundle construction
uses a separately keyed Release/USDT package with the selected perf image;
its archived environment-only entrypoint still supports the legacy builder.
The [portable cache receipt](../../evidence/tier-e-gradle-portable-cache-20260927/README.md)
records real cross-checkout `FROM-CACHE` and fresh 39-CTest restoration.
An interrupted or failing container cannot deposit root-owned build outputs
there. Publication is checked stage by stage (copy, validation, host export,
cleanup); a failed export retains its source container or evidence volume and
prints its recovery identifier. Fresh result preflight rejects old `PASS`
receipts. Resource cleanup names only the run's own container and workspace.
The Docker ownership and short rootful container tests have been exercised on
the local rootful endpoint; runtime parity on a rootful Podman server remains
unverified until such an endpoint is available. This change does not promote
Tier E as a default or change `policy_eligibility=false`.
The short annotated JUnit suite also exercises the actual 72-path non-root
target/privileged collector fixture. Endpoint discovery resolves the selected
Docker context or Podman default connection to its server URI before admission.
The fixture then proves a local `unix://` connection, local `/proc` PID-namespace
identity, and a read-only bind-mount token before preparing native code. Remote
or unproven endpoints skip this fixture even though mount-free engine and
caller-owned export tests may run remotely. Kotlin owns the exact per-run
target and collector names independently of the shell controller. In this
JUnit mode, the shell's optional creation lock covers both `run -d` calls;
Kotlin terminates the controller, waits for in-flight creation and acquires
the same lock before reconciling names. Unsettled creation fails explicitly;
it only attempts to stop currently visible names, retaining containers and
shared scratch without export or removal so a later creation cannot be
mistaken for completed cleanup.
The shell does not delete Kotlin-owned containers or shared scratch before
export. Kotlin verifies each container stopped, saves its complete logs to
caller-owned files (preserving shell snapshots), then removes it only after
successful export. A failed export retains the stopped container and its
shared scratch for recovery, reports the exact name, and still handles the
other container. Kotlin allocates the run's exact shared directory before
starting the shell and passes it via `TIER_E_SPLIT_SHARED_DIR`; the shell's
path marker must match that allocation. Verified successful cleanup retires
only that directory, never another same-user `tmp.*` directory named in a
mutable marker. The command summary reads only a bounded prefix; full
logs remain on disk. Teardown covers the controller, both owned containers,
and the unrelated test sentinel before restoring the caller's interrupt
status, preserving original failures with suppressed cleanup errors. The
[short Docker teardown receipts](../../evidence/tier-e-kotlin-teardown-20260925/README.md)
include a delayed real creation, failed export, interrupted sentinel cleanup,
and bounded-heap output read. Native
fixture builds reuse only a checkout/configuration-bound
cache after checking actual CMake source, build type, compiler, and USDT option;
a foreign cache fails without deletion, a validated interrupted default
configure may publish its missing identity marker, and concurrent writers
serialize. Availability and image configuration are forwarded to the
JUnit worker on each invocation, so a prior skipped Gradle run cannot mask a
newly available endpoint.

The run owner, controller coordinator, and native fixture builder live in the
profiler test source set, shared with its integration tests. Generic endpoint
probing and bounded container-command execution remain in the root common
test helpers; neither is part of a published runtime artifact. The
[profiler-local lifecycle receipts](../../evidence/tier-e-kotlin-boundary-20260925/README.md)
cover unresolved creation, exact shared-path ownership, and the strengthened
real-Docker cancellation assertion.

The short fixture now resolves the [Tier E JSON smoke profile](tier-e-testing-migration.md)
in Kotlin. Its opt-in flock protects a persistent `open`/`closed` creation
state, so a queued creator cannot start after controller teardown closes
admission. The remaining shell controller is still substantive; the Kotlin
tooling migration has not yet removed its protocol and scheduling logic.

## Compatibility rules

- Keep control protocol v1 and legacy monolithic artifact behavior.
- Keep raw MZSD v2 unchanged.
- Keep dictionary/full-stream default and collector-authoritative unique-edge opt-in.
- Do not add Kubernetes, Kubescape, or OTel dependencies here.
- Do not make discovery implicit; future adapters must emit this explicit registration.
