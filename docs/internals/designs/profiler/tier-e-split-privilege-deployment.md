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

### Installed operator helper

The installed `tier-e-target-helper` writes a v1 registration and sends an ATTACH using the existing
modified-UTF control protocol. The operator must use the **same** operator-owned registration for
the helper and daemon, and an operator-controlled socket and directory. V1 does not attest the
daemon's registration mode, registration ID or artifact filename: those fields are not in ATTACH or
its PID-only success reply. The registered daemon independently matches the wire PID, session,
library path and offset and checks its registered incarnation. The helper checks the local file and
live incarnation; neither a numeric PID nor a successful reply proves PID-namespace translation or
that the target mapped the selected library. Run the helper with the collector's actual host-PID
and filesystem visibility, and configure equivalent agent mounts explicitly.

For example, on a host with JDK, an existing built agent, and operator-created trusted directories,
the following target waits for a gate until attachment. Set absolute paths before running it. The
collector requires root and the kernel/BPF privileges described above; the target need not have
them. Here both processes use the same namespace and paths; cross-namespace deployments must map
the agent and definition directories deliberately. This example is not cross-UID qualification.

```sh
(
set -eu
BIN=/absolute/path/to/profiler/build/install/tier-e-daemon/bin
AGENT=/absolute/path/to/libmazewall_stack_agent.so
ROOT=/absolute/path/to/operator-owned-run-directory
DEFS=/absolute/path/to/target-and-collector-definition-directory
EVIDENCE=/absolute/path/to/operator-owned-evidence-directory
mkdir -p "$ROOT" "$DEFS" "$EVIDENCE"
test ! -e "$ROOT/go"
test ! -L "$ROOT/go"
test ! -e "$ROOT/control.sock"
test ! -L "$ROOT/control.sock"
test ! -e "$ROOT/daemon.pid"
test ! -L "$ROOT/daemon.pid"
# Authenticate before starting either background process; subsequent sudo calls never prompt.
sudo -v
TARGET=
DAEMON=
cleanup() {
  status=$?
  trap - EXIT
  set +e
  if [ -n "$TARGET" ] && kill -0 "$TARGET" 2>/dev/null; then
    if kill "$TARGET"; then
      wait "$TARGET" 2>/dev/null || :
    else
      echo "Target cleanup failed; operator action required" >&2
    fi
  fi
  if [ -n "$DAEMON" ] && kill -0 "$DAEMON" 2>/dev/null; then
    if [ -s "$ROOT/daemon.pid" ]; then
      COLLECTOR=$(sudo -n cat "$ROOT/daemon.pid")
      case "$COLLECTOR" in
        ''|*[!0-9]*) echo "Invalid collector PID; operator cleanup required" >&2 ;;
        *) sudo -n kill "$COLLECTOR" || echo "Collector cleanup failed; operator action required" >&2 ;;
      esac
    else
      kill "$DAEMON" 2>/dev/null || :
      echo "Collector PID unavailable; inspect daemon.log and clean up if needed" >&2
    fi
  fi
  exit "$status"
}
trap cleanup EXIT
cat > "$ROOT/GateTarget.java" <<'EOF'
import java.nio.file.*;
class GateTarget {
  public static void main(String[] args) throws Exception {
    while (!Files.exists(Path.of(args[0]))) Thread.sleep(20);
    for (int i = 0; i < 100; i++) Files.readString(Path.of("/proc/self/status"));
  }
}
EOF
javac "$ROOT/GateTarget.java"
java -agentpath:"$AGENT"=session=4d5a,mode=full-stream,session-bound-definitions,definitions="$DEFS/orders.mzse" \
  -cp "$ROOT" GateTarget "$ROOT/go" &
TARGET=$!
"$BIN/tier-e-target-helper" register --registration-id orders --pid "$TARGET" \
  --marker-path "$AGENT" --session-tag 4d5a --artifact-file orders.mzse --output "$ROOT/target.reg"
sudo -n sh -c 'echo "$$" > "$1"; shift; exec "$@"' sh "$ROOT/daemon.pid" \
  env TIER_E_SOCKET="$ROOT/control.sock" TIER_E_TARGET_REGISTRATION="$ROOT/target.reg" \
  TIER_E_DEFINITION_ROOT="$DEFS" TIER_E_EVIDENCE_ROOT="$EVIDENCE" TIER_E_ALL_TARGET_CAPTURE=1 \
  "$BIN/tier-e-daemon" > "$ROOT/daemon.log" 2>&1 &
DAEMON=$!
attempt=0
while [ ! -S "$ROOT/control.sock" ]; do
  if ! kill -0 "$DAEMON" 2>/dev/null || [ "$attempt" -ge 100 ]; then
    echo "Collector failed or socket readiness timed out; see $ROOT/daemon.log" >&2
    exit 1
  fi
  attempt=$((attempt + 1))
  sleep 0.1
done
if sudo -n "$BIN/tier-e-target-helper" attach --registration "$ROOT/target.reg" --socket "$ROOT/control.sock"; then
  touch "$ROOT/go"
else
  echo "ATTACH failed; target gate remains closed" >&2
  exit 1
fi
wait "$TARGET"
TARGET=
# The daemon automatically retires the registered target on exit; allow up to its 30-second artifact wait.
sleep 31
sudo -n cat "$EVIDENCE/orders-19802.tier-e.json"
sudo -n cat "$EVIDENCE/orders-19802.events.jsonl"
)
```

The example requires fresh paths: registration and event publication do not clobber existing files;
owner-only registration staging is validated before an atomic hard link. Unsupported hard links,
unsafe v1 values (including whitespace in the marker path), stale/exited PIDs, duplicate options,
bad ELF, permissions, EOF, `ERR`, and malformed or nonmatching-PID replies fail. No directory
permissions or privileges are adjusted. Socket access and evidence access are independent: defaults
are owner-only, while `TIER_E_SOCKET_GID` and `TIER_E_EVIDENCE_GID` are explicit separate choices.
Inspect the terminal reason, independent completeness dimensions, event counts and
`policy_eligibility=false`; a file alone is not completeness proof. Target-exit retirement replaces
a helper detach command (none is provided); physical retirement and failed cleanup remain distinct
as described below. The example's exit trap attempts termination of its owned target and collector.
The operator must verify actual process exit and physical retirement even when SIGTERM succeeds,
not only when cleanup reports failure. Signal delivery is not exit proof. The trap never opens the
gate after setup or ATTACH failure. This host-sudo recipe is illustrative: its syntax/failure
branches were checked, but host sudo authentication, prompting/policy and privileged execution
were not qualified. The configured-engine qualification below does not establish those properties.

### Configured rootful installed-command qualification

The 2026-11-03 A4c qualification uses the existing owned Docker fixture, not host sudo:

```sh
TIER_E_QUALIFICATION_ARTIFACTS="$PWD/build/a4c-retained" \
MAZEWALL_DOCKER_HOST=unix:///var/run/docker.sock \
./gradlew :profiler:rootfulContainerTest \
  --tests '*installed all-target daemon qualifies evidence access*' --console=plain
```

Run from the repository root with the configured local rootful endpoint, native compiler/CMake,
host `setfacl`/`getfacl`, and image `mazewall-tier-e-rootful:latest`. The existing fixture prepares
checkout-bound native assets and `installDist`; no optional pre-existing agent property can satisfy
this test. Missing privileges/assets/locality are not qualification. The tested image was amd64
Temurin 25.0.5+8 on Linux 7.2.11-x64v3-xanmod1; other mappings/filesystems need their own execution.

The fixture runs `TierEAllTargetWorkload` as UID/GID 20000 with capabilities dropped in its own
PID namespace. A separate privileged collector container joins the host PID namespace; its mounts
provide the **same agent inode**, installed distribution at `/installed`, definitions at
`/fixture/work`, and evidence at `/fixture/evidence`. After resolving the actual host PID from
the owned target's Docker state and checking `/proc` starttime, `NSpid` and mapped library, the
actual collector-side command sequence is:

```sh
# Executed inside the owned collector; PID and AGENT come from the checked fixture identity.
/installed/bin/tier-e-target-helper register --registration-id orders --pid "$PID" \
  --marker-path "$AGENT" --session-tag 4d5a --artifact-file definitions.mzse \
  --output /fixture/target.reg
env TIER_E_SOCKET=/fixture/control.sock TIER_E_TARGET_REGISTRATION=/fixture/target.reg \
  TIER_E_DEFINITION_ROOT=/fixture/work TIER_E_EVIDENCE_ROOT=/fixture/evidence \
  TIER_E_ALL_TARGET_CAPTURE=1 TIER_E_SOCKET_GID=20002 /installed/bin/tier-e-daemon
# Separate exec, only after daemon readiness; gate release follows successful ATTACH only.
/installed/bin/tier-e-target-helper attach --registration /fixture/target.reg --socket /fixture/control.sock
touch /fixture/work/first.gate
# Wait for the independent attributed-phase oracle, then release the ambient phase.
touch /fixture/work/second.gate
```

This is a description of commands executed by the test, not a standalone background/lifecycle
runner. Selected-group cases additionally set `TIER_E_EVIDENCE_GID=20002` **at daemon launch**;
default cases omit it while retaining socket GID 20002. Both final files are parsed as non-root
UID 20003/GID 20002 in selected-group mode and denied to UID 20001/GID 20004. Default `0600`
denies both readers. Readers have no supplementary groups, effective/permitted/bounding/inheritable/
ambient capabilities, or user-namespace UID remapping; traversal is independently exercised.
Inherited-default-ACL cases first prove that named UID 20001 can read a `0640` counterexample,
then prove denial on both finals and unchanged directory defaults. Selected finals remove the
named ACL; default finals retain an ineffective mask. Setup ACL utilities are test-only.

The fixture retains complete command output, registration, JSONL/terminal/dictionary bytes,
UID/GID/capability and permission/ACL receipts, image/runtime identity and cleanup evidence.
It checks exactly 20 `openat` observations through each of the two independent managed paths,
ambient rows, submitted/admitted/retained equality, zero loss/unknown-marker counts, dictionary
digest, and target-exit lifecycle closure. `terminal_reason=COMPLETE` and complete capture/export
do **not** imply complete attribution: these ambient captures honestly retain
`evidence_integrity=INCOMPLETE`, `attribution_coverage=false`, and `policy_eligibility=false`.
Wrong wire session and namespace-local PID are rejected before gates/attributed collection;
the existing rootful stale/reuse/session-artifact negatives remain separate checks.
The workload installs uncaught-failure handlers before starting its two platform workers. After
both physical joins, either worker failure reaches main with both causes retained and prevents
`attributed-complete` and `completed`. Normal execution still performs exactly 20+20 cold reads
and the later 20 reads. This corrected workload is a successor source/build identity; historical
samples do not acquire this stronger completion guarantee retroactively.
Collector termination opens a pidfd **before** checking the exec-preserved process incarnation and
the cgroup bound to the owned collector's immutable container ID. Only that pinned handle receives
SIGTERM; mismatches/unsupported operations fail without a numeric-PID fallback. The collector uses
the host cgroup namespace so the check is explicit. Signal delivery is followed by actual exec
completion and host `/proc` absence checks; native FD retirement and creation closure remain separate.

Every attempted daemon launch requires its recorded, validated startup identity for cleanup,
even when the owned exec client has already exited or launch/readiness failed. Missing identity
fails quiescence before export; client death and target stop do not prove collector stop. Cleanup
never learns a replacement identity from an arbitrary late PID. With known identity, only verified
daemon absence or pinned termination followed by physical absence permits export; exec-client
retirement is checked separately afterwards. If no launch was ever attempted, daemon stop is skipped.

Writer-stop failure prohibits export and recursive ownership restoration. Required dictionary,
final-file, identity and command exports must succeed, and copied bytes are read back before
ownership restoration. Only actual ENOENT before artifact creation is optional; permission, command,
transport and local-write failures reject qualification. Any qualification/quiescence/export failure
uses the existing lifecycle's `retainOnFailure=true`: close creation and stop for recovery, **retain**
the exact containers/source tree rather than deleting the only evidence. `recovery.txt` records the
owned names/IDs/path and actual inspect receipts, not a claim of successful deletion or physical stop.
An operator recovering that exact sample must verify creation closure, immutable ownership and
stopped writers, complete and verify recovery export, then explicitly retire the owned resources.
The fixture does not auto-delete a failed sample merely because a later cleanup/export attempt worked.
Successful qualification separately checks owned container removal and scratch absence. The original
successful A4c samples did not qualify these fault paths; fix-round host regressions cover stop,
export/write, fatal-folding and stale pinned-identity failures before renewed rootful acquisition.
The issues remain open for independent review and the controller-owned combined full build.

### Cheap evidence preflight and offline replay

Use the Kotlin tooling checker before an expensive diagnostic or review. It requires an explicit
absolute root and named regular-file members; it never guesses a historical schema or walks a
tree. `schema_version: 1` manifests have `domain` and nonempty `entries`, each with `path`, `bytes`
and lowercase `sha256`. Domains have deliberately different comparison rules:

| Domain | Meaning |
|---|---|
| `live` | Complete bytes plus canonical absolute path, device/inode, permissions, UID/GID and mtime/ctime nanoseconds. Relocation/replacement is a mismatch. |
| `snapshot` | Complete bytes at the named relative member beneath the supplied root. Relocation and metadata differences are allowed. |
| `prefix` | Exactly the captured first `bytes` bytes. Later appended bytes are outside the claim; truncation or changed prefix fails. |

Live entries require `canonical`, `device`, `inode`, `mode`, `uid`, `gid`, `mtime_ns`, `ctime_ns`.
Unknown informational fields are ignored, not compared as whole objects. Missing required fields,
duplicate members/JSON keys, unsupported versions, traversal and symlink members are refused.
Files must be quiescent during each check. A newly captured manifest establishes a current-byte
baseline, not historical provenance; authenticate the manifest's reference separately.

```bash
# EVIDENCE and REPORTS are absolute existing directories; REPORTS is outside EVIDENCE.
./gradlew :profiler:tierEEvidence \
  --args="capture snapshot '$EVIDENCE' '$REPORTS/inputs.json' \
  work-definitions.mzse work-capture-yield.mzcy work-open-calls.diag \
  evidence-boundary.bpfdiag evidence-ptrace-open.log \
  evidence-orders-19802.events.jsonl evidence-orders-19802.tier-e.json"
./gradlew :profiler:tierEEvidence \
  --args="verify '$EVIDENCE' '$REPORTS/inputs.json' '$REPORTS/preflight.json'"

./gradlew :profiler:tierEA4Replay \
  -PtierEReplayDirectory="$EVIDENCE" -PtierEReplayTargetPid="$TARGET_PID" \
  -PtierEReplayStartTicks="$START_TICKS" -PtierEReplayOutput="$REPORTS/replay.json"

./gradlew :profiler:tierEEvidence \
  --args="verify '$EVIDENCE' '$REPORTS/inputs.json' '$REPORTS/postflight.json'"
```

This A4-specific replay uses the existing MZSE/MZCY/native/boundary decoders, exact 20+20 cold
selection and `classifyRetainedProduction` for registration `orders`, session `19802`. Supply the
retained **host** PID and process start ticks explicitly. It checks bounded regular inputs and
unchanged bytes across classification, including the final trace newline. It retains open-event
filtering, creation-mode semantics and conservative cross-thread FD lifetime guards. It establishes
neither writer retirement nor runtime custody; those remain the fixture's operational gates.

All reports are versioned and say `qualification=false`. Identity outcomes are `VERIFIED` (exit 0),
`MISMATCH` (1), `INVALID_MANIFEST` (2). Replay outcomes are `INCONCLUSIVE_BASELINE` (0), `INCOMPLETE`
(1), `LOCALIZED_BOUNDARY` (1), `INVALID_EVIDENCE` (2). A clean replay does not reproduce or explain
an intermittent missing event. Gradle reports nonzero child exits as task failure: inspect the JSON
for the diagnostic outcome rather than calling the task failure a new runtime failure.

Manifests are limited to 4096 members/8 MiB. JSON reports are limited to 64 KiB **before** exclusive
creation; existing outputs are never replaced. Reports contain counts, input digests/references and
at most 20 sampled reasons/IDs, with explicit truncation. Raw files stay in their original packet.
The diagnostic's 6 MiB incremental-storage, 16 MiB trace and 64 KiB tracer-stderr limits still apply.
The checker uses the existing tooling source set, JSON parser and JDK file APIs. Its JUnit
regressions run in `:profiler:test`, `unitCheck` and `build`; no Python runner is needed for this
checker. Replay itself is explicit and is not an ordinary build prerequisite.

For each executable milestone, keep one owner through command settlement. Its compact handoff
must name changed paths, exact commands and exit statuses, per-task execution state (`EXECUTED`,
`FROM_CACHE`, `UP_TO_DATE`, `NOT_RUN`), evidence file references and the next unresolved gate.
Use the JSON outcome separately from execution state. Review the small diff against its preserved
dirty-checkout baseline and inspect referenced failures; do not embed raw stdout or repeatedly
rehash an already authenticated packet without a changed input or a concrete unresolved concern.
Keep ordinary build failure, incomplete evidence and pending research explicit in the roadmap.

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

Evidence access is independent of socket access. With `TIER_E_EVIDENCE_GID`
absent, both `.events.jsonl` and `.tier-e.json` are owner-only `0600`. An
explicit decimal host GID in `0..2147483647` selects that group and exact `0640`
for both, including incomplete/finalization-failed terminal reports. Empty,
signed, nonnumeric and out-of-range values reject daemon startup rather than
falling back; no socket group is implicitly reused. The operator must provide
the intended host/container GID mapping and directory traversal permissions.
Evidence roots must be existing non-symlink, operator-owned directories, with
ancestors protected from unrelated writers; the daemon does not chmod/chown
directories or establish mappings.

Each owned temporary file is completely written and closed before access is
applied. In selected-group mode the daemon first removes inherited extended
access ACLs, then assigns the group, then sets exact permissions. `0640` alone
is not group-only: its ACL mask can activate inherited named-user grants.
Directory/default ACLs are untouched. ACL removal accepts only success or
ENODATA (attribute absent); permission/unsupported errors reject publication.
Default `0600` does not widen the inherited ACL mask. Partial/failed event
temporaries remain unpublished and are restored to owner-only permissions;
failure to restore is also propagated.

Event publication uses same-directory atomic hard-link creation, which rejects
an existing/racing destination (including a symlink) without replacing it.
Filesystems lacking this operation fail closed. After the commit the temporary
name is unlinked; an unlink failure is observable cleanup failure and can leave
two names for the same complete, access-controlled inode, not partial output.
Terminal reports intentionally atomically replace their final name (a symlink
name is replaced, never followed); unsupported atomic rename fails without a
non-atomic fallback, preserving an existing final. Publication provides atomic
visibility, not a power-loss/directory-fsync durability guarantee. Actual
distinct-UID daemon/default-ACL qualification for this access option was executed
in the configured A4c fixture above; host mode/ACL assertions alone remain insufficient.

A malformed, stale, symlinked, unclosed, unavailable, or PID-reused artifact is fail-closed. It
cannot produce complete transport or lifecycle evidence. An empty ring is never producer-closure
proof.

Collection termination and physical retirement are separate. If finalization throws, the daemon
attempts independent cleanup and retains its closing owner until the worker has joined and owned
resources have retired. `ATTACH` cannot replace that owner; another `DETACH` or shutdown retries
only outstanding cleanup, not finalization or evidence publication. Successful cleanup never
upgrades failed collection. Interrupted joins preserve interruption, and cleanup aggregation keeps
the original failure unless a fatal error takes precedence, retaining distinct suppressed failures.

Direct and protocol shutdown close control admission under the service lock before cleanup.
All subsequent commands, including `STATUS` and repeated protocol `SHUTDOWN`, reject with `ERR STATE`;
direct shutdown remains the cleanup-retry and original-failure interface. A failed retirement cannot
reopen admission. Reentrant shutdown during synchronous attachment construction/use explicitly fails
with retirement pending, without closing resources still in use. The single acquisition owner adopts
late-returned resources, stops further acquisition and retires them after the acquisition unwinds;
failed retirement remains owned for direct shutdown retry. No asynchronous attachment registry is used.

Control metrics read a safely published immutable snapshot of the last completed poll/readiness
update, never the monitor held by an in-progress native poll. The immutable session epoch also
requires no poll/session monitor. Thus terminal metrics and `STATUS` cannot turn a bounded worker
join into an unbounded control wait; a blocked interruption-resistant drain keeps its source owned
until an explicit retry can join it. Native engine cleanup and acquisition rollback use the same
fatal-first failure fold, including an `Error` thrown by a later independent native-close seam.
Every attempted raw FD close still clears ownership and is never retried; failed mappings remain
owned for their existing per-mapping retry.

A registered collection-finalization failure attempts exactly one terminal report with the actual registration,
session, host PID and process incarnation: `evidence_integrity=INCOMPLETE`,
`terminal_reason=FINALIZATION_FAILED`, zero claimed resolved syscalls, every proof dimension false,
and `policy_eligibility=false`. It does not manufacture a profile or publish a completed
`.events.jsonl`. Before shutdown, `STATUS` retains the failure; the triggering `DETACH`/`SHUTDOWN` replies with an
error, and direct shutdown propagates it. A terminal-report write failure is logged and retained
with the original error, without another report attempt on cleanup retry. An unregistered legacy
failure logs and returns an error without inventing registered evidence.

Required event-file delivery is part of finalization, not an ignorable optional
consumer. If a successfully collected result cannot publish its event file,
the daemon retains the actual publication exception, increments the existing
terminal-delivery failure accounting and attempts one transport-incomplete
terminal report with `terminal_reason=FINALIZATION_FAILED`. Successful terminal
publication cannot turn the failed event publication into a successful control
reply. A second terminal-publication failure is folded into the original with
fatal-first ordering; cleanup retries never repeat either publication. If an
already-exited/reused registration is rejected before resource acquisition,
failed stale-identity terminal publication is likewise logged and retained with
the attach rejection, without creating a resource owner. Fatal publication
errors propagate by identity; ordinary failures remain observable through
status/direct shutdown, and existing final evidence is not discarded.

Host fault tests exercise the real daemon/runtime/service/session owner chain, including failed
attachment acquisition, target exit, interrupted joins, marker detach and terminal counter failures,
independent clock cleanup, fatal-first aggregation, and retry of the real ring reader's failed unmap.
These tests do not establish privileged attachment or kernel qualification.
For opt-in recovery-map collection, physical reader termination is distinct from scan failure.
Both exclusive final-scan handoff and source close keep the reader, scanner, queue reservation and
source/memory owner intact when the existing one-second join times out or is interrupted. A live
reader prevents source close/unmap and overlapping exclusive scans; cleanup retry joins it before
retirement. A terminated failed reader can retire normally while recovery diagnostics remain
incomplete. Host faults exercise these paths with shared source memory, including daemon ownership
and once-only failed reporting across a recovery timeout. Experimental opt-in requirements and
kernel qualification remain unchanged.

Final A1 verification on current dirty source passed the full build in 13m 27s (242 tasks: 75
executed, 167 up-to-date), after independent Sol review and scoped re-review of the fixes and Sol
approval of Terra's mechanical status correction. Profiler host tests discovered 1,471, executed
1,467 and skipped four; rootful tests ran 22 without failures, errors or skips. Regular integration
discovered 16, executed seven and skipped nine; fresh-JVM integration discovered 24, executed 19
and skipped five. All selected results had zero failures or errors. The amd64 rootful image was
Temurin 25.0.5+8-LTS, `sha256:8047b78d7af43aa5fa9dcfd61d5bbdcf47044e0a154221b2e8a3ba28c4fa02e1`,
on host kernel 7.2.11-x64v3-xanmod1. The review and build logs are local scratch, not published
receipts. This is not clean-commit or historical pinned/source acceptance, does not qualify every
optional native/HotSpot asset, and does not explain A3's historical 71/72 failure. A1 verification
is complete but its issue remains open for orchestrator-managed review/integration; no A2/A4
behavior, API, performance or policy eligibility is promoted. Report-only evidence remains
`policy_eligibility=false`.

Final A2 verification on the same current dirty-source/rootful-image/kernel/JDK
envelope passed independent A2a and A2b Sol reviews with no findings and the
combined `MAZEWALL_DOCKER_HOST=unix:///var/run/docker.sock ./gradlew build`:
exit 0 in 14m 7s, 242 tasks (79 executed, 163 up-to-date), local
`a2-full-build.log`. Profiler host discovered 1,484, executed 1,480, skipped four;
rootful executed 22 with no skips; regular integration discovered 16, executed
seven and skipped nine; fresh-JVM discovered 24, executed 19 and skipped five.
No failures or errors. This is not clean-commit or historical qualification;
both A2 issues remain open for orchestrator review/integration. The current
delayed-artifact case pass does not explain A3's historical 71/72 or qualify its
reference lane, and no A4 functionality is claimed implemented.

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
