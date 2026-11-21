---
title: "Stronger identity isolation"
severity: "MEDIUM"
status: "open"
priority: medium
dependencies: []
component: "portal-runtime"
target_modules: []
target_files: []
target_symbols: []
open_questions: false
---

# 🟡 [Severity: MEDIUM]: Stronger identity isolation

**Context:**
Evaluate distinct Unix UIDs, user namespaces, and additional process boundaries to reduce cross-process attacks, particularly where kernel feature availability varies. (Java4Shell-roadmap P2 #15)

**Needed:**
1. Survey available isolation boundaries: distinct Unix UIDs, user namespaces, mount namespaces, other process boundaries.
2. Prototype the strongest combination that keeps the broad baseline working; qualify on supported kernels.
3. Document the residual surface and why it remains acceptable where the boundary is unavailable.
4. Add kernel-aware tests where the boundary is available.
5. Run owning focused checks and the project completion gate.

---
<!-- id: issue-20261120-170410-619-stronger-identity-isolation  file: issue-20261120-170410-619-stronger-identity-isolation.md -->
