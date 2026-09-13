---
title: "Extract probe harness modes from TierEDaemon.kt main()"
severity: "LOW"
status: "open"
priority: low
component: "tier-e"
target_modules:
  - "profiler"
target_files:
  - "profiler/src/main/kotlin/io/mazewall/profiler/tierE/daemon/ProbeMain.kt"
  - "profiler/src/main/kotlin/io/mazewall/profiler/tierE/daemon/TierEKotlinDaemon.kt"
effort: "small"
autonomy: "supervised"
open_questions: false
dependencies: []
paperclip_issue_id: "ef0ff37d-ad70-4710-b849-f77958c69160"
paperclip_identifier: "MAZ-1386"
---

# 🟢 [Severity: LOW]: Extract --probe/--probe-stdin/--probe-cmdfile modes to ProbeMain.kt

**Context:** The historical `TierEDaemon.kt` main() contained ~80 lines of test-harness probe modes
mixed with production daemon startup. These are only used by the WP-04/WP-05 suite
scripts and should not ship in the production daemon artifact.

**Needed:** Keep all three probe modes (`--probe`, `--probe-stdin`, `--probe-cmdfile`)
in the separate `profiler` `ProbeMain.kt` harness. Production startup remains the
daemon-only `TierEKotlinDaemon` entry point.
