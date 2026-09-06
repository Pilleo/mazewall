---
title: "Align Codanna document indexing and agentmemory semantic recall on BGE-M3 vector space"
severity: "MEDIUM"
status: "open"
priority: medium
dependencies: []
component: "testing"
target_modules:
  - ":platform"
target_files:
  - ".codanna/settings.toml"
needs_kernel: false
core_lock: false
effort: "medium"
autonomy: "supervised"
open_questions: false
review_verdict: skipped
has_side_effects: true

paperclip_issue_id: "b00759a1-e23a-4f36-9cf3-091427e8564c"
paperclip_identifier: "MAZ-1052"
---

# 🟡 [Severity: MEDIUM]: Align Codanna document indexing and agentmemory semantic recall on BGE-M3 vector space

**Context:**
`agentmemory` currently indexes agent session notes, past gotchas, and architectural decisions using `BAAI/bge-m3` embeddings (1024 dimensions). Meanwhile, Codanna has a built-in document embedding engine (`[documents]` in `.codanna/settings.toml`) that indexes markdown designs and backlog specs. Aligning both engines to utilize the same semantic embedding model and building a cross-retrieval bridge allows an agent to query a single unified interface that merges experiential memory (past failures) with normative design documentation (kernel specs, BPF jump conventions) without semantic drift.

**Needed:**
1. Configure Codanna's document embedding pipeline in `.codanna/settings.toml` to align with `BAAI/bge-m3` embeddings.
2. Add document collections in Codanna for `docs/internals/designs/` in addition to `docs/internals/backlog/`.
3. Create a unified recall helper in `scripts/unified_recall.py` that queries both `agentmemory.memory_smart_search` and `codanna mcp semantic_search_docs`.
4. Rerank and deduplicate results into a cohesive context payload for prompt injection.
5. Verify that querying a topic like "USER_NOTIF socket ACK protocol" simultaneously retrieves the design specification from `profiler-design.md` and negative lessons from `agentmemory`.

---

**Verification:** `./gradlew :tools:orchestrator:checkBacklog` plus the `verify_cheap` commands above (if any).

<!-- id: issue-20260905-130530  file: issue-20260905-130530-align-codanna-document-indexing-and-agentmemory-semantic-rec.md -->
<!-- Agent: fill Context and Needed; add files/symbols if the impact walk missed them. Do not rename the file. -->
