---
title: "Return persisted ContainerProfile payload from Create for live HotSpot gate"
severity: "MEDIUM"
status: "resolved"
priority: high
dependencies: []
component: "profiler"
target_modules: []
target_files:
  - "docs/plans/2026-10-19-hotspot-companion-local-evidence.md"
target_symbols:
  - "CreateWithConn"
open_questions: false
---

# 🟡 [Severity: MEDIUM]: Return persisted ContainerProfile payload from Create for live HotSpot gate

**Context:**
First approved live storage gate against source61f0be2 failed both exact JSON/protobuf Create comparisons: storage saveObject deliberately fills metadata-only response. Operator explicitly selected Return full Create object, scoped to ContainerProfile; preserve all other types and metadata watch/list behavior. Retain first failed live receipts, require full Create/Get values and checked UID cleanup.

**Needed:**
1. Add storage Create response regression, return persisted ContainerProfile under existing owned lock without changing metadata watchers; rebuild exact reviewed source and execute unchanged live gate with real image provenance

## Execution checkpoint

The operator explicitly selected **Return full Create object** when the existing
metadata-only response was identified. Scoped storage commit
`46c8444a4d9a0c78ca82058ee7d9563a00605ced` reads the persisted ContainerProfile under
the existing write lock for Create responses only. Other resource responses and
metadata watches remain unchanged. Local Create-response RED, focused race,
full `make test`, and tagged gate-helper race checks pass. A baseline watcher-test
race is reproduced at unchanged61f0be2 and filed separately; no clean full-race
claim is made. Gate comparison accounts only for canonical dummy roots and
group/sibling ordering, retaining every typed caller path, evidence field and
optional pointer value.

The exact committed server archive was rebuilt and deployed with its verified
image graph. Both JSON and Kubernetes protobuf Create/Get pass, with all six
actual exchanges, exact media types, full typed caller paths and unchanged budgets.
The gate's UID cleanup and an independent empty namespace profile inventory pass.
[Executed evidence](../../../plans/2026-10-19-hotspot-storage-live-api.md) preserves
the first rejected run, exact source/image IDs and receipt custody. This resolves
the scoped API response defect, not storage publication or final integration.

---
<!-- id: issue-20261019-082935-929-return-persisted-containerprofile-payload-from-cre  file: issue-20261019-082935-929-return-persisted-containerprofile-payload-from-cre.md -->
