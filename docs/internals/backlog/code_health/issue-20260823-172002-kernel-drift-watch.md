---
title: "Kernel-Drift Watch: io_uring Ops, LSM Stacking, seccomp NOTIF API Growth"
severity: "LOW"
status: "open"
priority: medium
component: "ci"
target_modules:
  - ":enforcer"
  - ":profiler"
  - ":tools:orchestrator"
target_files:
  - ".github/workflows"
  - "docs/internals/research"
effort: "medium"
autonomy: "supervised"
open_questions: false
dependencies: []
---

# 🟡 [Severity: LOW]: Kernel-Drift Watch

**Context:** Kernel-interface handling today is exemplary where it was designed in (probe-based
`KernelFeatureMatrix`, dynamic Landlock ABI detection), but there is no mechanism that *notices*
when Linux grows surfaces mazewall must reason about. Concrete live risks:
- io_uring: `IORING_OP_*` additions and SQPOLL change which operations can bypass
  open/openat restrictions (the codebase already special-cases `io_uring_setup` — new ops or
  `IORING_SETUP_SQPOLL` variants may need policy updates).
- LSM stacking (landlock+apparmor/selinux composition) can alter errno precedence the violation
  detector reasons about.
- seccomp USER_NOTIF API growth (e.g. `SECCOMP_IOCTL_NOTIF_ADDFD` vs current SCM_RIGHTS fd passing)
  could simplify/replace supervisor plumbing but also changes TOCTOU semantics.

**Needed:**
1. A quarterly (or release-train) drift review checklist doc under `docs/internals/research/`,
   enumerating: LWN kernel cuticles, seccomp/Landlock/io_uring merge-window summaries, and for each
   item a decision row: "no impact / tracked issue N / action taken".
2. A CI job (schedule-triggered) that diffs kernel uapi headers (`linux/seccomp.h`,
   `linux/landlock.h`, `linux/io_uring.h`) against a committed snapshot and opens an orchestrator
   issue on drift.
3. Wire findings into the existing knowledge-map flow so each decision links to source files.

