---
title: "Require shadow-stack support for the CET probe"
severity: "MEDIUM"
status: "open"
priority: high
dependencies: []
component: "enforcer"
target_modules:
  - ":enforcer"
target_files:
  - "enforcer/src/main/kotlin/io/mazewall/RealPlatformProvider.kt"
  - "enforcer/src/main/kotlin/io/mazewall/Platform.kt"
effort: "small"
autonomy: "autonomous"
related_pr: 512
related_thread: PRRC_kwDOScnnEM7kBdS
---

# 🟠 [Severity: MEDIUM]: Require shadow-stack support for the CET probe

**Context:** On an x86 CPU that exposes IBT but not shadow stacks, the CET probe reports support even though `lockIntelCet` specifically enables `ARCH_SHSTK_SHSTK`. The status syscall only verifies that the kernel interface exists, so assessment can report `installable=true` before installation fails at `ARCH_SHSTK_ENABLE`. The new provider probe explicitly accepts the independent `ibt` feature as a substitute for `shstk`.

**Problem:**
- Probe checks for CET interface existence, not specific feature
- IBT-only CPU passes probe but fails at ARCH_SHSTK_ENABLE
- Assessment reports installable=true when it's not
- Installation fails later with confusing error

**Impact:**
- Misleading assessment results
- Installation fails after assessment passes
- User confusion about CET support

**Needed:**
1. Require shadow-stack CPU flag (`shstk`) for CET probe
2. Reject IBT-only CPUs as not supporting lockIntelCet
3. Make probe check match the actual installation requirement

**Codex PR Comment:** https://github.com/Pilleo/mazewall/pull/512#discussion_r3825587180
