# Remaining Code-Quality Refactors Design

## Goal

Complete the approved, non-orchestrator code-quality backlog while preserving
the Linux containment fail-closed contract and making protocol/state changes
independently testable.

## Scope

Included backlog work is limited to the following existing code-health items:

- FD ownership, typed FD propagation, and syscall classification
  (`20260824-203500`, `20260907-080829`, `20260907-080949`).
- Pure request/planning extraction from native execution
  (`20260907-065311`, `20260907-065452`, `20260907-065528`).
- State-machine and effect separation for Landlock, containment registry,
  USER_NOTIF, profiler handshake, and portal RPC
  (`20260907-080955`, `20260907-080959`, `20260907-081005`,
  `20260907-081011`, `20260907-081045`).
- Profiler API isolation and typed control boundaries
  (`20260907-080818`, `20260907-080837`).
- Sealed portal methods and reviewable generated dispatch
  (`20260907-080845`, `20260907-081112`).
- Supervisor route decomposition, behavioral-test cleanup, arithmetic labels,
  containment-order documentation, and errno helper deduplication
  (`20260826-102722`, `20260826-180103`, `20260907-081057`,
  `20260907-081101`, `20260907-081116`).

Explicitly excluded: profiler-daemon coroutine conversion, supported-public-API
reduction/versioning, Tier-E daemon harness/timeout/metrics work, Codanna stale
memory verification, CI-only quality tooling, and every orchestrator item.

## Non-Negotiable Constraints

- Preserve fail-closed handling of `EPERM` and `EACCES`; no warning-only
  downgrade is permitted.
- Preserve Landlock-before-Seccomp installation ordering.
- Do not install Seccomp from virtual threads, use TSYNC with NEW_LISTENER, or
  change BPF layouts, 32-bit C field widths, or the BPF linear-scan design.
- Keep FFM layouts/downcalls in the FFI boundary, use confined arenas, and
  capture errno immediately after native calls.
- An FD may be closed only by a type-level owned token. No new closeable token
  may be manufactured from an arbitrary integer.
- Preserve the exactly-one USER_NOTIF reply invariant: every notification
  reaches CONTINUE, KILL_THREAD, or ABORT exactly once.
- Preserve portal numeric wire encoding; replace only Kotlin-side primitive
  dispatch with typed parsing and exhaustive handling.

## Architecture

### 1. FD foundation

`FileDescriptor` remains the ownership authority. API boundaries accepting a
descriptor use `FileDescriptor<*, *, *>` with the narrowest feasible ownership
state; integer values are retained only inside ABI adapters or map keys. Tests
with invented descriptor numbers must be equality-only or allocate a genuinely
owned descriptor via the project test guard.

The platform classifier accepts `SyscallNumber`, and the supervisor/Landlock
path carries descriptor tokens through argument parsing, path resolution, open
results, injection, and close. This batch precedes later supervisor and
Landlock extraction because it makes resource ownership explicit in their
contexts.

### 2. Pure plan plus native interpreter boundary

Deterministic validation and encoding become immutable plans/requests. Native
engines own caller-confined arena allocation, downcalls, immediate errno
capture, and translation of native results. Plan evaluation must neither mutate
session state nor issue syscalls.

This applies to installation self-verification, Tier-E BPF program load
encoding, and architecture syscall number resolution. Tests inspect plans and
encoded fields without requiring a kernel; kernel-sensitive executor tests
remain focused on the interpreter's existing contract.

### 3. Explicit protocol machines

Each sequential protocol exposes internal sealed state, events, effects, and a
pure `evaluate(state, event)` function. Interpreters perform poll/read/write,
SCM_RIGHTS, ioctl, and registry updates, then feed their results back to the
machine. State assignment occurs only from the evaluate result.

Machines cover Landlock installation, containment registry publication,
USER_NOTIF session lifecycle, profiler handshake/listener lifecycle, and
portal broker/worker RPC. Existing route machines may remain focused on route
selection; the session machine composes their decision with ACK lifecycle.

### 4. Typed and reviewable protocol edges

Profiler control commands parse once to a sealed variant, with an explicit
unknown variant. Mmap protection/flag bits get named boundary types. Portal
frame parsing yields a sealed `PortalMethod`; all dispatch and code generation
use exhaustive `when` without an `else`. Generated sources expose method names
and named granted-FD slots in reviewed snapshots.

Handshake and native-I/O implementation types are internal and absent from the
profiler API dump; no public profiler signature accepts `MemorySegment`.

### 5. Readability and assertion quality

`SupervisorSessionHandler` is decomposed by route with parameter objects; its
dispatcher becomes parse, classify, route, execute. Existing coverage-only
tests become behavioral tests or are removed only after replacement assertions
protect the same real invariant. Kernel ABI arithmetic and BPF inspection
steps receive named constants/comments, stale containment-order references are
corrected, and errno capture is centralized without delaying the capture.

## Delivery Order and Evidence

1. FD foundation: focused platform and supervisor tests, plus liveness/guard
   tests for any changed close path.
2. Pure planning: plan/encoder/resolver unit tests and module tests.
3. State machines: reducer transition matrices first, then focused interpreter
   tests; run kernel verification for changed Seccomp, Landlock, or USER_NOTIF
   behavior.
4. Protocol/API and handler decomposition: API dump/architecture tests plus
   portal/profiler/enforcer module tests.
5. Readability/test/documentation work: focused behavioral tests followed by
   coverage checks where tests were replaced.
6. Final `./gradlew build`, backlog validation, and a diff review limited to
   the approved files.

Each code change follows red-green-refactor: add a narrowly failing test,
observe the expected failure, implement the minimum behavior, then run the
focused test before moving to the next batch.

## Error Handling

Native and permission failures remain explicit result/error events. Interpreters
must not catch broad exceptions to continue containment; cancellation or
shutdown events become typed transitions. A failed effect produces the machine
failure state and the required final reply/cleanup effect exactly once.

## Success Criteria

- The included backlog items are implemented or shown obsolete by tests and
  source inspection, with their issue records updated only after evidence.
- New pure logic has direct host-unit transition/encoding coverage.
- Public profiler API no longer exposes handshake/native I/O or
  `MemorySegment`.
- No changed containment path weakens FD ownership, Landlock-before-Seccomp,
  errno capture, or the exactly-one-reply invariant.
- Focused checks, relevant kernel checks, and the final build pass.
