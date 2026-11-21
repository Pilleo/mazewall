---
title: "Older-kernel support"
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

# 🟡 [Severity: MEDIUM]: Older-kernel support

**Context:**
Support sandbox installation before JVM startup where Landlock TSYNC (ABI 8) is unavailable, without degrading the process-wide security guarantee. (Java4Shell-roadmap P2 #16)

**Needed:**
1. Map the minimum Landlock ABI/TSYNC availability matrix across supported kernels and JDKs.
2. Design the pre-TSYNC install path at JVM startup; preserve the process-wide security guarantee through readiness evidence.
3. Add regression tests for pre-TSYNC installation where the kernel supports it.
4. Wire the new install paths into the readiness/qualification gates.
5. Run owning focused checks and the project completion gate.

---
<!-- id: issue-20261120-170411-175-older-kernel-support  file: issue-20261120-170411-175-older-kernel-support.md -->
