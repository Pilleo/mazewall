# Architecture Boundary Hardening Design

## Goal

Complete the next seven approved architecture improvements outside the
orchestrator while preserving public APIs, wire formats, native ABI behavior,
and fail-closed containment semantics.

## Decisions

1. Profiler raw FFM becomes an internal `io.mazewall.profiler.ffi` concern.
   Engine and event code consume heap values and typed native operations only.
   An ArchUnit rule prevents future leakage.
2. USER_NOTIF continuation has two explicit legal outcomes: published events
   require the JVM ACK; intentionally suppressed noise continues without one.
   No other path may bypass the ACK.
3. Supervisor and portal interpreters consume phase-specific commands rather
   than arbitrary state/effect products. Invalid command/state combinations
   cannot be constructed by reducers.
4. Containment diagnostics retain a structured provenance model. Extraction is
   graph-safe and prefers structured evidence over message heuristics.
5. Wire parsing is open at the raw boundary (`Known`/`Unknown` or malformed)
   and closed inside security machines, where unknown input fails closed.
6. ABI conformance is checked with platform-native probes/fixtures without
   changing production layouts or adding dependencies.
7. Backlog records are closed only where source inspection and focused tests
   establish that the concern is already satisfied; stale problem statements
   are corrected rather than treated as active defects.

## Constraints

- No generic FSM framework or new dependency.
- Keep Landlock before Seccomp; reject virtual-thread installation; never use
  TSYNC with NEW_LISTENER.
- Retain existing constructors, public result accessors, portal wire encoding,
  native signatures, and FD ownership rules.
- Confined arenas remain local to FFI operations; errno is captured immediately.
- Every USER_NOTIF path emits exactly one terminal kernel outcome.

## Verification

Each change starts with a focused failing test, then passes its module gate.
The final gate is `./gradlew --no-daemon --console=plain --quiet build`, with
the relevant fresh-JVM USER_NOTIF tests for altered profiler behavior.
