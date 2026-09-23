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

The independent 72-path coverage oracle is retained separately from this lifecycle fixture. It
currently executes inside the privileged benchmark harness, where it proves exact decoded edge
recovery across dictionary and inline transports. It is not yet a split-topology coverage result:
that requires a collector-owned, bounded acknowledged-edge manifest at terminalization. Target-side
operation counters and dictionary-definition counts are not sufficient substitutes.

Rootless output is `INCOMPLETE`, not privileged evidence.

## Compatibility rules

- Keep control protocol v1 and legacy monolithic artifact behavior.
- Keep raw MZSD v2 unchanged.
- Keep dictionary/full-stream default and collector-authoritative unique-edge opt-in.
- Do not add Kubernetes, Kubescape, or OTel dependencies here.
- Do not make discovery implicit; future adapters must emit this explicit registration.
