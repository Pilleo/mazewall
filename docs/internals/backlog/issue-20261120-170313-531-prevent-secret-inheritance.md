---
title: "Prevent secret inheritance"
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

# 🟡 [Severity: HIGH]: Prevent secret inheritance

**Context:**
Launch Portal workers with a minimal explicit environment; do not implicitly inherit database credentials, cloud tokens, or other secrets from the broker. (Java4Shell-roadmap P0 #5)

**Needed:**
1. Inventory broker environment variables and classify which could reach workers.
2. Implement an explicit allowlist launch environment at the Portal spawn boundary.
3. Add a test proving sensitive names cannot reach worker-visible environment.
4. Wire into required readiness/launch gates.
5. Run owning focused checks and the project completion gate.

---
<!-- id: issue-20261120-170313-531-prevent-secret-inheritance  file: issue-20261120-170313-531-prevent-secret-inheritance.md -->
