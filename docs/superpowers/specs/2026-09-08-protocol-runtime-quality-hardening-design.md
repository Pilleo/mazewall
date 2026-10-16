# Protocol, Runtime, and Quality Hardening Design

## Goal

Make internal protocols bounded and fail closed, isolate mutable runtime state per
session, make shutdown completion observable, and ratchet architecture quality
without changing public APIs, native layouts, portal wire frames, or the broker/
worker authority boundary.

## Constraints

- Exclude `tools/orchestrator`.
- Preserve Landlock-before-Seccomp, virtual-thread rejection, and `EPERM`/
  `EACCES` fail-closed behavior.
- Add no dependency or generic state-machine framework.
- Preserve source and ABI compatibility; new types are internal unless an existing
  public boundary requires exposure.
- Close only owned descriptors. Do not invent ownership around a raw integer.

## Design

### 1. Bounded internal protocol decoding

`SupervisorValidationDecoder` and `TraceEventDecoder` will be pure internal
decoders. They validate counts, byte lengths, type tags, and UTF-8 framing before
allocation. Their sealed result distinguishes a decoded message from malformed,
truncated, and limit-exceeded inputs. Invalid input becomes a terminal protocol
event; it never silently desynchronizes a stream.

### 2. Fail-closed validation handshake

The JVM validation channel will have explicit `AwaitingHandshake`, `Active`, and
terminal rejection states. Only the expected ACK enters `Active`; bad ACK, EOF,
timeout, or malformed preamble closes the channel. No verdict is produced before
successful activation.

### 3. Session-scoped mutable runtime

Profiler and supervisor mutable registries will be introduced through internal
context objects, while existing singleton APIs remain compatibility facades.
Contexts own thread registrations, listeners, diagnostics, clocks, and injected
operations. Test overrides are lexical and restored in `finally`.

### 4. Atomic native runtime and descriptor audit identity

Native consumers will capture one immutable engine snapshot for a logical
operation. Descriptor audit history will use `(fd, generation)` identity and will
be bounded/retired without weakening stale-token protection.

### 5. Completion-aware shutdown

Daemon and trace-listener shutdown will model interrupt, bounded drain, resource
close, and termination separately. A monotonic deadline determines a typed terminal
outcome; closing resources never relies on an unconfirmed worker stop.

### 6. Pure interpreter boundaries

Large I/O adapters will delegate decoding, authorization, dispatch, and lifecycle
decisions to small pure components with sealed outcomes. Effects execute I/O;
interpreters do not assign protocol state directly. Public behavior is unchanged.

### 7. Quality ratchet and deterministic verification

Analysis will reject newly introduced warning-level debt while allowing the reviewed
baseline. Generated diagrams are checked deterministically rather than mutated by
ordinary builds. Informational analysis excludes worktrees/build output and records
tool failures clearly instead of presenting partial analysis as clean.

## Acceptance Evidence

- Oversize, negative, truncated, and unknown-tag frames cannot allocate unbounded
  memory or reach business logic.
- Invalid supervisor ACK produces no verdict and closes the channel.
- Parallel profiling/supervisor sessions cannot observe each other's registries.
- An engine swap cannot mix capabilities within one native operation.
- Reused FD integers do not inherit an older audit ownership record.
- Shutdown tests prove resources close only after workers drain or a typed timeout
  outcome is recorded.
- Reducer/decoder matrix tests are exhaustive for all sealed states and events.
- A newly introduced Detekt warning or nondeterministic generated artifact fails a
  focused quality check.
