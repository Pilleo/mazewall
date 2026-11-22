---
title: "Verified process-wide Seccomp"
severity: "HIGH"
status: "open"
priority: high
dependencies: []
component: "enforcer"
target_modules: []
target_files: []
target_symbols: []
open_questions: false
---

# 🟡 [Severity: HIGH]: Verified process-wide Seccomp

**Context:**
Harden the existing security boundaries: process-wide seccomp installation must require TSYNC success, prove coverage of existing and new threads, and fail closed rather than silently accepting thread-only fallback. (Java4Shell-hardening roadmap P0 #1)

**Needed:**
1. 1. Reproduce any silent thread-only fallback and confirm no verified process-wide installation path exists. 2. Implement TSYNC success requirement and thread coverage proof in the installer. 3. Add kernel-aware tests across supported JDKs and container runtimes. 4. Wire into required readiness gates. 5. Run owning focused checks and the project completion gate.

---
<!-- id: issue-20261120-165855-557-verified-process-wide-seccomp  file: issue-20261120-165855-557-verified-process-wide-seccomp.md -->
