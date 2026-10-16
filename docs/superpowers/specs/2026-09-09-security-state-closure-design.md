# Security-State Closure Design

## Goal

Close the remaining fail-open, shared-state, cleanup, and verification gaps found in
the post-hardening audit without changing public policy APIs, native ABIs, portal frames,
or the broker/worker authority boundary.

## Constraints

- Exclude `tools/orchestrator`.
- Preserve Landlock-before-Seccomp, virtual-thread rejection, and TSYNC/NEW_LISTENER exclusion.
- Treat unknown supervisor input and native `EPERM`/`EACCES` as terminal, not as capability absence.
- Keep FFM and raw native calls behind `NativeEngine` / `io.mazewall.ffi`.
- Add no dependency or generic state-machine framework.
- Existing public constructors remain source compatible; new implementation types are internal.

## Decisions

### 1. Supervisor authorization is total and fail closed

Replace the `Syscall.OPEN` fallback with a pure resolution outcome. A number absent from the
architecture's `Syscall` table becomes `Unknown`, is logged with its architecture, and yields an
`EPERM` verdict. It never reaches a caller-supplied `OPEN` handler.

### 2. Supervisor runtime is installation scoped

`SupervisorRuntimeContext` owns the TID-to-thread registrations used by its JVM listener.
`SupervisorSession.close()` unregisters its own TID. The legacy installer registry remains only as
a compatibility facade where necessary, not as the listener's authority source.

### 3. Route work returns terminal decisions

Supervisor route helpers return sealed `SupervisorReplyDecision` values. A single reply interpreter
writes `CONTINUE` or `EPERM`; helpers may perform owned-resource work but cannot reply directly.
The session reducer remains the source of reply lifecycle state.

### 4. Landlock distinguishes unsupported from failed

A pure `LandlockCapabilityProbe` preserves a syscall error. Only kernel-unsupported outcomes may
consult opt-in fallback. Permission, malformed, and unexpected failures remain rejected. A small
plan/result boundary isolates capability classification from the native probe.

### 5. Descriptor close has a typed result

`SeccompConnection` receives its socket manager immutably and reports a sealed aggregate close
result. It marks an owned descriptor closed only after close succeeds, so a failed close is visible
and a later cleanup may retry.

### 6. Runtime registries publish immutable snapshots

Portal dispatcher registration builds a frozen map before dispatch; duplicates and malformed
bootstrap entries are startup failures. Tier-E collector updates and drain snapshots are atomic,
including event loss and completeness flags.

### 7. Quality evidence identifies findings, not counts

The Detekt ratchet compares stable finding fingerprints rather than only per-module totals.
The backlog summary has one generated/validated count source, and timing-sensitive tests use
observable barriers rather than fixed sleeps where practical.

## Acceptance evidence

- An unknown supervisor syscall is denied and never invokes an `OPEN` authorization handler.
- Two supervisor sessions resolve only their own registered threads; close removes the registration.
- Every reply route produces one decision and reply failures are terminal.
- `EPERM` from Landlock capability probing is not presented as “unsupported”.
- Close failures are returned and retriable; no descriptor is silently marked closed.
- Duplicate portal method IDs fail bootstrap; concurrent Tier-E drops have exact accounting.
- Replacing one Detekt warning with another fails the ratchet; backlog counts cannot diverge.
