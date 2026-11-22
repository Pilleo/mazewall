---
title: "Controlled worker spawning"
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

# 🟡 [Severity: HIGH]: Controlled worker spawning

**Context:**
Resolve the conflict between the broker's no-exec policy and its need to replace workers; consider a dedicated, narrowly authorized launcher rather than weakening the entire broker. (Java4Shell-roadmap P1 #10)

**Needed:**
1. Map the broker no-exec policy vs. worker replacement spawn paths.
2. Implement the narrowly authorized launcher for replacements with audit on every spawn.
3. Add tests proving the launcher cannot spawn arbitrary host commands and spawns are audited.
4. Wire launcher admission into readiness/spawn gates.
5. Run owning focused checks and the project completion gate.

---
<!-- id: issue-20261120-170351-396-controlled-worker-spawning  file: issue-20261120-170351-396-controlled-worker-spawning.md -->
