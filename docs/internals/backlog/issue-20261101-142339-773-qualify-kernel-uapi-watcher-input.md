---
title: "Qualify kernel UAPI watcher input"
severity: "MEDIUM"
status: "open"
priority: high
dependencies: []
component: "ci"
target_modules: []
target_files:
  - ".github/workflows/kernel-drift-watch.yml"
  - "scripts/check_kernel_uapi.sh"
  - "scripts/tests/test_kernel_uapi.py"
  - "docs/internals/research/kernel-drift-watch.md"
  - "docs/internals/research/uapi-snapshot/linux/landlock.h"
target_symbols: []
open_questions: false
---

# 🟡 [Severity: MEDIUM]: Qualify kernel UAPI watcher input

**Context:**
Scheduled run [41307728608](https://github.com/Pilleo/mazewall/actions/runs/41307728608)
filed [#564](https://github.com/Pilleo/mazewall/issues/564) because an Ubuntu 24.04
`linux-libc-dev` package was older than the committed Ubuntu 26.04 header snapshot.
This is independent of PR563 cold-cache CI efficiency work.

**Needed:**
1. Qualify the watcher input on Ubuntu 26.04 against the reviewed package baseline.
2. Preserve strict header diffs and retain package/diff diagnostics for real drift.
3. Reject unqualified older packages and tool/header failures without filing a drift issue.

**Result:** Ubuntu 26.04 input qualification and strict byte diffs retain version,
tool-error and header-diff artifacts. Reviewed `.42` update is one Landlock comment;
no ABI changes. All 11 executable fixtures and independent source review pass.
Hosted watcher qualification and managed landing remain pending.

---
<!-- id: issue-20261101-142339-773-qualify-kernel-uapi-watcher-input  file: issue-20261101-142339-773-qualify-kernel-uapi-watcher-input.md -->
