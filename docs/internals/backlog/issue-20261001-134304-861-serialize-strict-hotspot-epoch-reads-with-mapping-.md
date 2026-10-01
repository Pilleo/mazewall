---
title: "Serialize strict HotSpot epoch reads with mapping synchronization"
severity: "MEDIUM"
status: "open"
priority: high
dependencies: []
component: "experiment"
target_modules: []
target_files:
  - "docs/plans/2026-10-01-agentless-hotspot-epoch-design.md"
  - "docs/plans/2026-10-01-agentless-hotspot-epoch-plan.md"
  - "experiments/hotspot-agentless/results/identity-investigation.md"
target_symbols:
  - "SynchronizeMappings"
open_questions: false
---

# 🟡 [Severity: MEDIUM]: Serialize strict HotSpot epoch reads with mapping synchronization

**Context:**
Second review found that the proposed epochReader mutates hotspotInstance.rm.ReaderAt, but pinned processmanager/processinfo.go invokes SynchronizeMappings outside pm.mu. Existing locks do not serialize that shared-reader mutation or its error ledger.

**Needed:**
1. Choose a trace-local immutable reader path or explicit instance ownership and add a concurrent mapping/guard race regression before implementing epochReader.
2. Define lock ordering for begin, decoding, end/abort, synchronization and detach; avoid taking pm.mu recursively through symbolizeFrame/findMappingForTrace.

**Pinned source evidence:** profiler `processmanager/processinfo.go:522–528`
unlocks before `instance.SynchronizeMappings`; HotSpot synchronization accesses
`d.rm` and calls `updateStubMappings`. The plan's temporary ReaderAt replacement
therefore introduces a shared-field write into a concurrently readable instance.
This is a proposed-design race, not a claim of an implemented epoch feature.

**Closure evidence:** revised reader-ownership contract plus a deterministic
concurrent synchronization/guard regression under `-race`, executed through the
real provider dispatch path. Parent: captured interpreted method identity issue
`issue-20260930-220901-114`.

**Implemented; independent review pending:** the instance's RemoteMemory is never
replaced. Private metadata helpers receive the trace-local copy/ledger.
`TestHotSpotEpochReadersRemainImmutableDuringSynchronization` executes real
mapping synchronization concurrently with strict decoding under `-race`; the
full host/upstream gate passes. Strict live runs `pq4kaX`, `mBTB50`, `DOGzCx` and
final audited `FMeuii` pass. See the parent evidence report; keep open for review.

---
<!-- id: issue-20261001-134304-861-serialize-strict-hotspot-epoch-reads-with-mapping-  file: issue-20261001-134304-861-serialize-strict-hotspot-epoch-reads-with-mapping-.md -->
