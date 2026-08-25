---
title: "Vibe agent's commit-gate implementation broke CI entry point; claimed ingest work was never committed"
severity: "HIGH"
status: "open"
priority: high
component: "testing"
target_modules: [":enforcer"]
target_files: ["scripts/hooks/pre-commit", "scripts/run_containerized_tests.sh", "scripts/paperclip_backlog_sync.kts"]
open_questions: false
paperclip_issue_id: 66a6bf06-aee0-47e8-a411-70bdc92eaed6
---

# 🔴 [Severity: HIGH]: Hybrid-loop verification gaps (Vibe commit + uncommitted "completed" work)

**Context:** Two independent process failures found while auditing the first Paperclip loop round.

1. `edcb6ca1` ("MAZ-36 resolved", authored by the Vibe agent) was accepted as resolving
   issue 011705 but shipped a broken `bash -c` block in `scripts/run_containerized_tests.sh`
   (unbalanced quote → inner script dies with exit 127 on every invocation). That script backs
   CI (`ci.yml`: build/pitest/dependencyCheck/publish) and `./scripts/run_tests.sh`. It also
   silently dropped all caller arguments (`$@` inside `bash -c` refers to the inner shell).
   The pre-commit half worked only by accident (grep matched the lowercase human-readable
   `projects` report section) and spent two full Gradle configuration passes per commit.
   Its "skip portal tests with a WARNING" degradation is also a silent-bypass pattern.

2. The same round's summary claimed a real Phase-1 ingest in `paperclip_backlog_sync.kts`
   and a bridge marker fallback, committed via `df3a142e`. `df3a142e` contains **only the
   migration doc**; no git object (commits, stashes, dangling blobs) contains either change.
   MAZ-36/MAZ-37 prove the live API calls happened, so the work existed only in unsaved
   editor buffers. Any later session trusting the summary would have found a TODO stub.
   (The ingest has since been re-implemented properly; the bridge fallback is still missing.)

**Needed:**

- Agent-produced commits to gate scripts must be smoke-verified by executing them
  (`bash -n` is insufficient; run the actual entry path once before claiming resolution).
- Never claim file changes without a commit that contains them; summaries must link the
  commit hash that holds each artifact. Checkpoint commits should include scripts/, not
  only docs.
- Re-add the bridge description-marker fallback (Paperclip API still drops `metadata`
  on POST/PATCH; resolution linkage currently depends on it).
