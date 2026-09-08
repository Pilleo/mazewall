# Final Architecture Hardening Design

## Goal

Finish the seven post-hardening architecture improvements and fully decompose
`SupervisorSessionHandler` into typed, testable collaborators. Completion is behavioral and
structural: wrappers around an unchanged god object do not count.

## Scope

- In scope: `platform`, `enforcer`, `profiler`, `portal`, and `portal-worker`.
- Out of scope: `tools/orchestrator`, BPF linear-scan design, native layouts, portal wire bytes,
  mutable Java builders, and public policy signatures.
- Preserve Landlock-before-Seccomp, virtual-thread rejection, TSYNC/NEW_LISTENER exclusion,
  exactly-one USER_NOTIF reply, and owned-FD-only cleanup.

## Decisions

1. Kernel capability probes remain typed from native result through installation assessment.
   `Unsupported` is distinct from `Rejected(errno)` and `ProbeFailed(cause)`; only `Unsupported`
   may consult an explicitly selected fallback.
2. Portal dispatch configuration is built privately, validated once, and published as an
   immutable snapshot. No compatibility entry point can mutate the published table.
3. Spawn authorization is session-owned, bound to stable process identity, and consumed once.
4. Cleanup outcomes are interpreted by owners. A failed owned-descriptor close remains retriable
   and cannot be discarded by `AutoCloseable.close()` adapters.
5. Profiler dependencies, listeners, and TID mappings belong to a `ProfilerService`/session,
   while the `Profiler` object remains a compatibility facade only.
6. Path inspection produces an exhaustive result rather than encoding failure as an empty list.
7. Tests use observable barriers and generated state matrices; verification reports exact changed
   findings and guards architectural ownership rules.
8. `SupervisorSessionHandler` is a thin effect coordinator under 300 physical lines. Extraction,
   JVM exchange, route I/O, FD injection, secure exec, async socket work, and kernel reply writing
   live in focused collaborators. Exactly one interpreter owns every kernel reply operation.

## Compatibility

- Preserve existing public constructors, method descriptors, policy APIs, portal method IDs and
  frame encoding, and native ABI/layout values.
- New result and state types are internal unless a public typed capability view is strictly
  additive.
- Deprecated compatibility methods delegate to immutable/session-owned implementations and do
  not retain independent mutable state.

## Completion gates

- Each task has a focused red/green test and an independent commit.
- No `EPERM`/`EACCES` capability result becomes `Unsupported`.
- Portal dispatch cannot change after publication, through any API.
- Spawn tokens are consume-once and reject PID-reuse identity mismatch.
- Every cleanup failure is observable by its owner.
- Parallel profiler sessions cannot see one another's runtime hooks, listeners, or TIDs.
- Path failure reasons reach `ProfilingCoverage` without count-based inference.
- `SupervisorSessionHandler.kt` is at most 300 physical lines and contains no direct
  `UserNotifReply.send`, `sendSeccompError`, or `sendSeccompContinue` call.
- Focused tests, module tests, fresh-JVM USER_NOTIF tests, architecture checks, backlog validation,
  and the in-scope build all pass without a persistent Gradle daemon.
