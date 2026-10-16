---
title: "Reject incomplete generated native preparation artifact inventories"
severity: "MEDIUM"
status: "resolved"
priority: high
dependencies: []
component: "core"
target_modules: []
target_files:
  - "profiler/hotspot-agentless/internal/tooling/prepare.go"
  - "experiments/hotspot-agentless/internal/tooling/prepare.go"
target_symbols: []
open_questions: false
---

# 🟡 [Severity: MEDIUM]: Reject incomplete generated native preparation artifact inventories

**Context:**
During Task7 Go migration, the existing Python preparation verifier accepts an empty .mw-epoch-artifacts.json because it only checks listed entries. That removes all generated native-byte checks while the preparation marker remains present. Regression run-b9fcb8493647 reproduces empty-inventory admission in the literal port. The new owner must require exactly support/types.go and both amd64/arm64 tracer artifacts before reuse.

**Needed:**
1. Require the complete fixed generated-artifact inventory, preserve byte-hash and provenance refusal checks, verify corrupted/empty inputs fail, and retire the legacy verifier only after the Go preparation owner is wired and accepted.

## Resolution evidence

- `run-b9fcb8493647`: regression rejects the literal old behavior accepting an empty inventory.
- Both Go preparation owners now require exactly the generated support/types.go and
  amd64/arm64 tracer artifacts, checking every actual byte hash before reuse.
- `run-7d3053c95ee9` / `run-a831828cc58a`: owning race-enabled provenance, mutation,
  incomplete inventory and incompatible-mode regressions pass in both trees.
- `run-ec6e3e9a8fa6`, `run-954547ebd104`, `run-87f336f427a1`: actual normal,
  diagnostic and fault-mode preparation/rebuild pass with distinct expected identities.
- `run-53e6d4380737`: fresh exported standalone experiment passes actual Go-owner
  dependency preparation, owning/upstream race suites, stripped build and graph checks.
  Both legacy Python preparation drivers were retired after acceptance.

---
<!-- id: issue-20261014-192004-715-reject-incomplete-generated-native-preparation-art  file: issue-20261014-192004-715-reject-incomplete-generated-native-preparation-art.md -->
