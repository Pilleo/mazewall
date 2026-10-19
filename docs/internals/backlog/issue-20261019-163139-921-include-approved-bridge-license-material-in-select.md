---
title: "Include approved bridge license material in selected-module distributions"
severity: "MEDIUM"
status: "open"
priority: high
dependencies: []
component: "profiler"
target_modules: [profiler]
target_files:
  - "profiler/hotspot-agentless/internal/tooling/distribution.go"
target_symbols:
  - "releaseDistribution"
open_questions: true
---

# 🟡 [Severity: MEDIUM]: Include approved bridge license material in selected-module distributions

**Context:**
PR543 review executed the unchanged selected-module notice check against the actual published v0.1.0 and candidate bridge directories; both lack license material and block strict distribution. Root README declares Apache 2.0 but no root LICENSE exists. Owner confirmation remains required; immutable versions cannot be edited.

**Needed:**
1. Obtain owner confirmation before adding legal material; test the real selected SDK notice plus a missing-notice copy without weakening the fail-closed distribution check.

---
<!-- id: issue-20261019-163139-921-include-approved-bridge-license-material-in-select  file: issue-20261019-163139-921-include-approved-bridge-license-material-in-select.md -->
