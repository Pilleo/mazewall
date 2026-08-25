---
title: "Paperclip core upstream asks blocking hybrid-loop robustness"
severity: "HIGH"
status: "open"
priority: high
dependencies: []
component: "orchestrator"
target_modules:
  - ":tools:orchestrator"
target_files:
  - "tools/orchestrator/src/main/kotlin/io/mazewall/orchestrator/PaperclipClient.kt"
needs_kernel: false
core_lock: false
effort: "medium"
autonomy: "supervised"
open_questions: false
---

# 🔴 [Severity: HIGH]: Paperclip core upstream asks blocking hybrid-loop robustness

**Context:** Four Paperclip-core limitations cost real debugging time and one silent multi-hour
stall during the MAZ-102 loop run (2026-08-25). Filed individually upstream as adapter issues
#4-#10 where relevant; tracked here because each forces workarounds in OUR code.
**Needed:** (upstream asks, in priority order)
1. **Wake semantics**: interaction acceptance fires exactly ONE heartbeat. Post-relay phases
   (e.g. Jules RUNNING for 30+ min) get zero polls unless agent heartbeat is enabled. Ask:
   wake_until_terminal continuation option OR config-time validation that heartbeat polling is on.
2. **Stop dropping metadata on POST/PATCH**: the description-marker hack exists solely because
   of this (paperclip_backlog_sync.kts buildBoardDescription + bridge fallback).
3. **First-class no-changes-needed outcome**: agents concluding "already fixed" produce empty
   diffs; board needs a done-without-diff disposition instead of silent blocked states.
4. **Interactions API discoverability**: request_confirmation shape + /accept endpoint were
   reverse-engineered from jules-adapter source; document in /llms.
