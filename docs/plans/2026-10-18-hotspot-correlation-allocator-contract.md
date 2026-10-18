# Bounded native correlation allocation contract

## Authority and purpose

The operator's “please continue” on 2026-10-18 approves the shared, non-reusing
atomic allocator correction described in the [identity evidence](2026-10-18-hotspot-correlation-identity-evidence.md).
It also identifies Kubescape stacktrace collection as the purpose of this work.
This contract makes the bounded implementation choices explicit; it grants no
new runtime profile, resolver namespace, public ABI or policy authority.

The original failed capture remains failed. Equal-clock allocation is an executed
sufficient cause, not a proved historical interleaving. The correction's source
implementation and isolated native checks are recorded in
[Task1 evidence](2026-10-18-hotspot-correlation-allocator-evidence.md).
Full-bundle/public-native qualification remains pending Task2; no runtime promotion
follows from those isolated checks.

## Kubescape compatibility boundary

Read-only inspection of the operator's node-agent checkout at
`/home/leanid/Documents/code/java/stack-attribution/node-agent`, HEAD
`1c4fb954cdd51edb98a2ad4100be392cd806e44e`, found an agent-assisted Tier E
completed-artifact importer. Its profile CallIDs hash scoped frame content;
they are not agentless native correlation references. The checkout's existing
tracked/untracked work is operator-owned and is not edited by this correction.

Preserve raw reference versus content identity, original occurrence/task/process
generation, exact frames, and explicit missing attribution. The agentless
schema-2 BOOTTIME stream is not the Tier E schema-1 MONOTONIC import protocol.
This correction supplies a sounder producer reference for a future integration;
it does not implement that bridge or demonstrate Kubernetes persistence.

## Allocation and lifetime

- One `BPF_MAP_TYPE_ARRAY` named `correlation_ids`, key `u32`, value `u64`, one
  entry, initially zero. This is eight bytes of identity state, not a delivery
  buffer. It is allocated and owned alongside the existing producer LRU.
- Every loaded entry/program variant sharing `stack_cache2correlation_id` must
  reference the same acquired allocator map. No per-program private copy, pinned
  external replacement, independent reset or new public reset API is introduced.
- The value records the last issued ID. Successful compare/exchange advances it
  strictly once; only the successful caller receives the new value. IDs are in
  `[1, INT64_MAX]`, preserving reserved zero and signed legacy export compatibility.
- Use at most 32 compare/exchange attempts per miss. A failed compare/exchange
  retries with a newly observed value; never use a stale value as an ID.
- At `INT64_MAX`, or an invalid greater value, return zero without changing the
  state. Never wrap. No unbounded spin, native lock, clock/random fallback or
  counter reset is permitted.
- The allocator map lifetime contains all producer programs, LRU entries,
  definition/waiter/pending references and resolver users of that namespace.
  A new independent collector/map/resolver instance may begin at one only after
  it has a separate namespace; no old task is rebound to the new instance.

## Cache and explicit failure semantics

The full PID/TID/HotSpot-identity/raw-frame key, uint64 value, LRU256 and trace/event
layouts stay unchanged. A hit returns its existing ID even when new allocation
is exhausted. A miss obtains one ID and carries that same value to the cache,
return slot and definition. Racing equal-key misses may consume different unique
IDs; they must not alias different definitions or rewrite an earlier occurrence.

Missing allocator state, invalid/exhausted state or 32 failed attempts yields
zero. On that path, leave/clear the shared return slot to zero, publish no new
cache entry or definition, and return through the existing missing-reference
outcome. The selected occurrence is still emitted/accounted and gets an explicit
unavailable terminal; the outcome does not claim complete stack attribution.
No new status field or successful substitute stack is fabricated. Existing
permission, identity and native-owner failures keep their original fatal handling.

The new allocator does not reset after cache eviction or failed publication.
Cache insertion/definition-output failure remains an availability concern under
the existing missing-definition/recovery contract, never permission to reuse a
number or overwrite an immutable definition. Characterize these boundaries and
retain actual native errors; this task does not introduce a new loss-proof claim.

Clock fields are independent: occurrence/deadline BOOTTIME and definition metadata
retain their existing meanings. Pruning remains based on successful LRU membership
scans and two absent cycles, not the numeric age of an ID. Existing exact
task/epoch/frame guards remain mandatory, including negative guard controls.

## Required qualification

1. Actual unchanged-production-C RED against repeated clock readings, corrected
   actual-C GREEN, and a clock-allocation mutant that fails the same invariant.
   Cover task/frame/epoch distinctions, hit reuse and reordered definitions.
2. Boundary values 1, `2^53+1`, `INT64_MAX`; reserved zero, invalid state,
   saturation, deterministic 32-attempt contention exhaustion and publication
   failure must not manufacture or reuse a positive ID.
3. Real kernel execution of the actual shared allocator code on multiple CPUs;
   verify atomic uniqueness, acquired map identity/layout, shared entry-variant
   ownership, no independent reset, and checked test resource retirement. A
   test-only runnable wrapper may isolate the actual allocator but is not full
   HotSpot/transport proof. Actual public capture supplies the latter coupling.
4. Immutable resolver/waiter/recovery/pruning checks with non-time references,
   including cross-task conflicts and native EPERM/EACCES propagation. Distinguish
   supported synchronous kprobe execution from hypothetical active perf/NMI nesting.
5. Renew actual current-source compile-ID characterization before advancing all
   fourteen Go/Kotlin pin consumers. Derive the ordered patch digest; preserve old
   inputs/artifacts, rebuild both embedded BPF architectures and Go layouts, and
   retain source/compiler receipts before builds and binary hashes before runs.
6. Reproducible locked helper and full OCI bundle, legacy rendered/pdata/API/ABI
   compatibility and explicit public-native runs in the existing pinned
   interpreter/Serial profile. Dense, global and lifetime qualifications stay
   independent. Report cached/skipped suites accurately.

The [remaining-work requirements](2026-10-17-hotspot-remaining-work-spec.md) and
their exact budgets/fail-closed rules continue to apply. The implementation plan
owns current task dispositions; no build or successful native outcome is claimed
by this contract alone.
