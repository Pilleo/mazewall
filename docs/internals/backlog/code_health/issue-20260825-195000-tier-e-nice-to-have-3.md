---
title: "Add per-context event count metrics to daemon"
severity: "LOW"
status: "open"
priority: low
component: "tier-e"
target_modules:
  - "tier-e-proto"
target_files:
  - "daemon/tier_e_bpf_shim.c"
effort: "small"
autonomy: "supervised"
open_questions: false
dependencies: []
paperclip_issue_id: 0f9793b6-3264-4bc1-9ee5-677fa96ca071
paperclip_identifier: MAZ-679
---

# 🟢 [Severity: LOW]: Per-context event count metrics

**Context:** The daemon tracks total events but not per-context-id distribution.
Useful for verifying that attribution is balanced across scopes during stress runs.

**Needed:** Add a percpu LRU hash map keyed by context_id in the BPF program;
increment on each attributed event; expose via a shim function callable from Kotlin.
Report in session DEAD summary line.
