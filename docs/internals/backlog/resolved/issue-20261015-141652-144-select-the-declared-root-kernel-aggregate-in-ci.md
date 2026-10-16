---
title: "Select the declared root kernel aggregate in CI"
severity: "HIGH"
status: "resolved"
priority: high
dependencies: []
component: "core"
target_modules: []
target_files:
  - ".github/workflows/ci.yml"
target_symbols:
  - "kernelCheck"
open_questions: false
---

# 🟡 [Severity: HIGH]: Select the declared root kernel aggregate in CI

**Context:**
After host correctness passes on the compatible Ubuntu26.04 runner, CI run38748172105 fails kernel task graph construction: bare kernelCheck selects demos:vulnerable-web-app:kernelCheck, which references an intentionally absent kernel coverage gate. Root :kernelCheck already defines the required participating-module boundary and excludes demos. Use that exact root selector rather than accidentally selecting every same-named compatibility task; retain assemble and all root aggregate kernel dependencies.

**Needed:**
1. Reproduce bare-selector graph failure, prove exact :kernelCheck graph selects all required module kernel gates and no demo kernel task, and update the CI invocation.

## Resolution

The bare selector reproduces CI's missing demo coverage task locally
(`run-e3c9888545fa`). Exact `:kernelCheck --dry-run` succeeds and retains enforcer,
platform, profiler and all three portal-module kernel/coverage gates without any
demo kernel gate (`run-fd1ea42e7626`). The CI command now passes `:kernelCheck`
to the existing container wrapper; assembly and every root aggregate dependency
remain unchanged. The previously successful full build already uses this exact
declared root aggregate. Remote execution remains the final CI merge gate.

---
<!-- id: issue-20261015-141652-144-select-the-declared-root-kernel-aggregate-in-ci  file: issue-20261015-141652-144-select-the-declared-root-kernel-aggregate-in-ci.md -->
