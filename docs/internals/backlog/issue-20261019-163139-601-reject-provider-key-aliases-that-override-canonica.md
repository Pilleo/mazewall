---
title: "Reject provider key aliases that override canonical HotSpot pins"
severity: "MEDIUM"
status: "open"
priority: high
dependencies: []
component: "profiler"
target_modules: [profiler]
target_files:
  - "hotspot-bridge/wire/json.go"
target_symbols:
  - "requiredRecordFields"
open_questions: false
---

# 🟡 [Severity: MEDIUM]: Reject provider key aliases that override canonical HotSpot pins

**Context:**
PR542 review reproduced successful start, ready and enrichment consumption with a wrong canonical provider pin overwritten by an uppercase alias accepted by encoding/json. Preserve Provider comparability and unknown raw extensions.

**Needed:**
1. Add both-schema all-provider-field alias/order regressions, reuse canonical-key validation on lifecycle and evidence providers, require no Capture on contradiction.

---
<!-- id: issue-20261019-163139-601-reject-provider-key-aliases-that-override-canonica  file: issue-20261019-163139-601-reject-provider-key-aliases-that-override-canonica.md -->
