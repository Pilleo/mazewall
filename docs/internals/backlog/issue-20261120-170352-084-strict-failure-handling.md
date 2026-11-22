---
title: "Strict failure handling"
severity: "HIGH"
status: "open"
priority: high
dependencies: []
component: "portal-runtime"
target_modules: []
target_files: []
target_symbols: []
open_questions: false
---

# 🟡 [Severity: HIGH]: Strict failure handling

**Context:**
Never fall back to host execution when Portal startup, policy installation, RPC validation, or worker availability fails; distinguish incomplete cleanup from successful process termination. (Java4Shell-roadmap P1 #13)

**Needed:**
1. Inventory Portal failure modes: startup, policy installation, RPC validation, worker availability.
2. Implement fail-closed behavior: no host fallback; distinguish incomplete cleanup from successful termination.
3. Add tests for each failure mode proving no fallback and correct cleanup behavior.
4. Wire failure handling into readiness/shutdown gates.
5. Run owning focused checks and the project completion gate.

---
<!-- id: issue-20261120-170352-084-strict-failure-handling  file: issue-20261120-170352-084-strict-failure-handling.md -->
