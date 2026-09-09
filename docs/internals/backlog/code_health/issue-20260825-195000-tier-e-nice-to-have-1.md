---
title: "Extract probe harness modes from TierEDaemon.kt main()"
severity: "LOW"
status: "open"
priority: low
component: "tier-e"
target_modules:
  - "tier-e-proto"
target_files:
  - "tier-e-proto/src/main/kotlin/io/mazewall/tierE/daemon/TierEDaemon.kt"
effort: "small"
autonomy: "supervised"
open_questions: false
dependencies: []
paperclip_issue_id: "f963ff0e-8e57-4671-8c1d-ff372b990a94"
paperclip_identifier: "MAZ-1253"
---

# 🟢 [Severity: LOW]: Extract --probe/--probe-stdin/--probe-cmdfile modes to ProbeMain.kt

**Context:** `TierEDaemon.kt` main() contains ~80 lines of test-harness probe modes
mixed with production daemon startup. These are only used by the WP-04/WP-05 suite
scripts and should not ship in the production daemon artifact.

**Needed:** Move all three probe modes (`--probe`, `--probe-stdin`, `--probe-cmdfile`)
to a separate `ProbeMain.kt` file. Update inner scripts to invoke it.
