---
title: "Resource containment"
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

# 🟡 [Severity: HIGH]: Resource containment

**Context:**
Enforce worker CPU, memory, process/thread and other relevant resource limits through cgroups; prevent a compromised worker from exhausting host resources. (Java4Shell-roadmap P1 #8)

**Needed:**
1. Enumerate the resource classes to contain: CPU, memory, process/thread counts, file descriptors, I/O.
2. Implement the containment limits through the available mechanism (cgroups or equivalent) at worker admission.
3. Add positive and negative containment tests: exceeding limits fails fast without damaging the host.
4. Wire containment into Portal readiness/startup gates.
5. Run owning focused checks and the project completion gate.

---
<!-- id: issue-20261120-170350-698-resource-containment  file: issue-20261120-170350-698-resource-containment.md -->
