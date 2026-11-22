---
title: "Restrict inherited file descriptors"
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

# 🟡 [Severity: HIGH]: Restrict inherited file descriptors

**Context:**
Verify exactly which FDs workers receive at launch; preserve only explicitly authorized IPC and capabilities; remember that Landlock does not revoke already-open descriptors. (Java4Shell-roadmap P0 #7)

**Needed:**
1. Verify the current launch path and record exactly which FDs workers inherit.
2. Make the launch-time FD inventory deny-by-default; allow only explicitly authorized IPC and capability descriptors.
3. Document the authorization rules for each FD class (IPC transport, capabilities, other).
4. Add regression tests: unauthorized inherited FD class fails launch; required IPC FD still works.
5. Run owning focused checks and the project completion gate.

---
<!-- id: issue-20261120-170350-203-restrict-inherited-file-descriptors  file: issue-20261120-170350-203-restrict-inherited-file-descriptors.md -->
