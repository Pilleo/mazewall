---
title: "Include build verification source inputs in rootful transfer"
severity: "HIGH"
status: "resolved"
priority: high
dependencies: []
component: "core"
target_modules: [":profiler"]
target_files:
  - "profiler/src/tooling/kotlin/io/mazewall/profiler/tierE/tools/TierERootfulSourceTransfer.kt"
  - "profiler/src/test/kotlin/io/mazewall/profiler/tierE/tools/TierERootfulSourceTransferTest.kt"
target_symbols:
  - "TierERootfulSourceTransfer"
open_questions: false
---

# 🟡 [Severity: HIGH]: Include build verification source inputs in rootful transfer

**Context:**
PR531 rootful CI job116376769532 transfers a source subset omitting integrations and tests. Mandatory buildSrc GradleSourceGuardTest reads two actual build scripts there, so the guest fails with NoSuchFileException before native acceptance. Copy required source roots with existing Git-scoped deletion, filename and permission handling; do not skip the verification.

**Needed:**
1. Extend the archive-content regression to cover tracked optional integration source and untracked consumer build source, then include both source roots.

## Resolution

The actual source-transfer regression now requires a tracked integration build
script and an untracked consumer build script. It fails against the old source
scope (`run-036340f8de36`). Including the `integrations` and `tests` roots fixes
the incomplete guest source custody. Existing Git-scoped deletion, raw filename,
symbolic-link and permission-failure handling is preserved.

All five source-transfer cases pass (`run-173b2f4fd234`), including extraction and
exact byte comparison of both required scripts. Mandatory build verification
remains enabled in the guest; no missing-file fallback or test skip was added.

The full included-module build passes (`run-bd93ef1797a4`, 832,953 ms). The
published follow-up must pass the actual remote rootful acquisition before merge.

---
<!-- id: issue-20261015-104822-523-include-build-verification-source-inputs-in-rootfu  file: issue-20261015-104822-523-include-build-verification-source-inputs-in-rootfu.md -->
