---
title: "Secure crash recovery"
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

# 🟡 [Severity: HIGH]: Secure crash recovery

**Context:**
Ensure crashed or malicious workers are terminated and reaped; fix replacement ownership races, apply restart budgets, prevent repeated crashes from creating unlimited replacements. (Java4Shell-roadmap P1 #9)

**Needed:**
1. Build crash-injection fixtures (crashing and malicious workers) and a crash counter.
2. Fix replacement ownership races and add a restart budget with a repeated-crash ceiling.
3. Verify crashed-worker termination, reaping, and bounded replacement behavior.
4. Add recovery regression tests: crash, replacement, repeated-crash budget enforcement.
5. Run owning focused checks and the project completion gate.

---
<!-- id: issue-20261120-170351-066-secure-crash-recovery  file: issue-20261120-170351-066-secure-crash-recovery.md -->
