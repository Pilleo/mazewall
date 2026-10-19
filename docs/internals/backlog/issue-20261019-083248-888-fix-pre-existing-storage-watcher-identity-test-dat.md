---
title: "Fix pre-existing storage watcher identity test data race"
severity: "MEDIUM"
status: "open"
priority: high
dependencies: []
component: "profiler"
target_modules: []
target_files:
  - "docs/plans/2026-10-19-hotspot-companion-local-evidence.md"
target_symbols:
  - "TestFileSystemStorageWatchReturnsDistinctWatchers"
open_questions: false
---

# 🟡 [Severity: MEDIUM]: Fix pre-existing storage watcher identity test data race

**Context:**
Storage full race suite exposes reflect.DeepEqual through assert.NotEqual on live watcher structs, racing context/cancel internals. Reproduced independently in exact committed storage61f0be2 archive with go test -race ./pkg/registry/file -run '^TestFileSystemStorageWatchReturnsDistinctWatchers$' -count=1. Keep separate from operator-approved ContainerProfile Create response change; retain failure logs.

**Needed:**
1. In a separately scoped storage change, assert pointer/channel identity without deep-reflecting live synchronization state and own/join disposable watchers; prove race suite no longer fails while preserving distinct watcher contract

---
<!-- id: issue-20261019-083248-888-fix-pre-existing-storage-watcher-identity-test-dat  file: issue-20261019-083248-888-fix-pre-existing-storage-watcher-identity-test-dat.md -->
