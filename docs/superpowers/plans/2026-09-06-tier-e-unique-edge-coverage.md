# Tier E Unique Stack x Syscall Coverage Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use `superpowers:executing-plans` task-by-task.

**Goal:** Add an opt-in Tier E mode that emits one representative observation per exact logical Java stack and syscall number, suppressing later duplicates without claiming a full syscall trace.

**Architecture:** Each wrapped native boundary retains a bounded JVMTI raw-frame walk (`jmethodID` plus BCI) to prove exact logical-stack identity. A hit skips metadata resolution and definition creation. BPF suppresses only a known `(canonical context, syscall number)` edge; unmarked syscalls remain visible as UNKNOWN.

**Tech Stack:** JVMTI/JNI C++20, Kotlin FFM eBPF, BPF task storage, BPF LRU hash.

**Spec:** `docs/internals/designs/profiler/tier-e-design.md`

## Constraints

- `FULL_STREAM` remains the default and preserves current behavior.
- Unique-edge mode never infers stack identity from a partial frame or timestamp.
- `UNIQUE_EDGES` is not suitable for counts, argument-sensitive coverage, policy generation, or enforcement.
- Loss, failed metadata capture, and agent/collector configuration mismatch are terminal incomplete evidence.

### Task 1: Configuration and guarantees

- [ ] Add `TierEEmissionMode { FULL_STREAM, UNIQUE_STACK_SYSCALL }` and `TierEOptions`.
- [ ] Thread mode through the session, daemon ATTACH command, BPF installation, and agent options.
- [ ] Add `CaptureGuarantee { FULL_STREAM, UNIQUE_EDGES, INCOMPLETE }`; preserve `complete=true` only for full streams.

### Task 2: Canonical stack contexts

- [ ] Cache an exact raw-frame sequence plus capture flags in the native agent.
- [ ] Reuse a canonical context on a cache hit; resolve names and write definitions only for new stacks.
- [ ] Guard cached raw identities with weak class references so unloaded classes cannot reuse stale `jmethodID` values.

### Task 3: eBPF and collector edge deduplication

- [ ] Add a 65,536-entry per-session LRU hash keyed by canonical context and syscall number.
- [ ] Suppress a cache hit before ring reservation; on misses, only mark the edge after successful argument reads and reservation.
- [ ] Deduplicate concurrent or post-eviction duplicate emissions in userspace and expose suppression/degradation counters.

### Task 4: Verification and documentation

- [ ] Cover default/full-stream behavior, exact stack reuse, divergent deeper stacks, class-unload invalidation, edge uniqueness, BPF branches, and configuration mismatch.
- [ ] Run privileged repeated-I/O coverage smoke tests and benchmark no profiling, full stream, unique edge, and USER_NOTIF.
- [ ] Document representative-argument semantics and unique-edge guarantees.
