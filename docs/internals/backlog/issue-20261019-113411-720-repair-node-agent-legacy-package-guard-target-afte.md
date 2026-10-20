---
title: "Repair node-agent legacy package guard target after test relocation"
severity: "MEDIUM"
status: "open"
priority: high
dependencies: []
component: "core"
target_modules: []
target_files:
  - "docs/plans/2026-10-19-hotspot-companion-local-evidence.md"
target_symbols:
  - "TestLegacyPackagesDeleted"
open_questions: false
---

# 🟡 [Severity: MEDIUM]: Repair node-agent legacy package guard target after test relocation

**Context:**
C5 owning make gates build the package-based binary successfully, then check-legacy-packages fails because Makefile still runs deleted ./tests/containerprofilecache. The existing test is now pkg/objectcache/containerprofilecache/packages_deleted_test.go; its ../../../ loader root matches that location. Original gate failure frozen in build/kubescape-dev/node-companion/task5-make-owning-gates.log. Keep test logic and IG pin guard intact.

**Needed:**
1. Point the existing Make target at the actual owning package, run its real test and IG pin guard, and record exact-index source/binary evidence without committing the preserved node-agent prototype

**Local progress (not archive authority):** The corrected Make target now selects
`./pkg/objectcache/containerprofilecache`; the unchanged real test and IG pin guard
pass in the development workspace. The package-based node-agent binary also builds.
Receipts: `task5-owning-guards-corrected-result.json` and
`task5-make-owning-gates-result.json` under `build/kubescape-dev/node-companion/`.
The original missing-directory failure is retained. C5 commit72034696 records the
independent connected race suite, unchanged guards and ordinary binary build at
tree d958b50de57b3550ef31163f81b5dbda80a802ca, with its later added save/queue test
checked at final committed tree7670e4e903185f24df6a8fa79c7fec41378a0149. The test-only
delta cannot alter the binary inputs; valid prior checks are reused. Review/resolution stays with the existing
control plane rather than automatic archival.

---
<!-- id: issue-20261019-113411-720-repair-node-agent-legacy-package-guard-target-afte  file: issue-20261019-113411-720-repair-node-agent-legacy-package-guard-target-afte.md -->
