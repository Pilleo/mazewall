# Paperclip Hybrid Loop — Operator Guide & Migration Path

> Status (2026-08-24): **Loop verified end-to-end live** on MAZ-36 (ingest → assign →
> auto-dispatch → agent run). This document answers: how to trigger the loop, what is
> implemented, what stays unique in `tools/orchestrator`, and which tools help.

## 1. How to trigger the loop today

```bash
# One command: ingest open backlog issues, then dispatch the next unblocked,
# ingested issue to Jules (assign + status -> in_progress triggers the run):
./scripts/run_paperclip_loop.sh              # add --dry-run to preview without changes

# Approvals / notifications (separate daemon, when Telegram wiring is configured):
python3 scripts/paperclip_telegram_bridge.py
```

`run_paperclip_loop.sh` env knobs: `PAPERCLIP_API_KEY` (default `local`),
`PAPERCLIP_API_URL` (default `http://127.0.0.1:3100`),
`PAPERCLIP_AGENT_ADAPTER` (default `jules` — matches the orchestrator's worker),
`PAPERCLIP_MAX_DISPATCH` (default `1`). Dispatch only ever picks issues carrying
the `<!-- mazewall:backlog-file=… -->` marker, so manual/board-native entries are
never auto-dispatched.

Underlying steps (what the wrapper automates):

```bash
# 1. Push open backlog issues into Paperclip (topological batch, idempotent):
PAPERCLIP_API_KEY=local kotlin -Xuse-fir-lt=false scripts/paperclip_backlog_sync.kts [--dry-run]

# 2. Assign an agent to an issue (this is the DISPATCH trigger):
curl -X PATCH "http://127.0.0.1:3100/api/issues/<issue-id>" \
  -H "Authorization: Bearer local" -H "Content-Type: application/json" \
  -d '{"assigneeAgentId":"<agent-uuid>"}'
curl -X PATCH "http://127.0.0.1:3100/api/issues/<issue-id>" ... -d '{"status":"in_progress"}'
# -> Paperclip starts a Run automatically (invocationSource: "assignment"/"automation")

# 3. Resolution happens automatically when Paperclip marks done:
#    bridge sync_git_lifecycle() flips frontmatter to resolved/, moves file to
#    docs/internals/backlog/resolved/, git-commits.
```

### Jules agent configuration gotchas (2026-08-24)

The board's Jules agent ("Async software developer", `8ec6f7dd…`) failed three
times before its first real session; all three are config, not code:

1. `adapterConfig.repository` missing → set it (`Pilleo/mazewall`). Without it the
   adapter cannot derive the Jules source (`RepositorySchema.parse(undefined)`).
2. `baseBranch` underivable → set `fast-master` (or wire provider metadata).
3. API key binding: the shared secret record existed but was bound as
   `adapterConfig["access.JULES_API_KEY"]`, which the core never materializes.
   Working binding (PATCH `/api/agents/:id`): **both**
   `adapterConfig.env.JULES_API_KEY = {type:"secret_ref", secretId, version:"latest"}`
   and `runtimeConfig.env.JULES_API_KEY = <same ref>`. Note the secret record key
   is `jules_api_key` while the adapter README asks for `jules-api-key`; the id-based
   ref resolves regardless.

With those set, dispatch reaches the live Jules API and parks in
`scheduled_retry` / `jules_session_pending` at Jules' plan-approval gate
(`planApprovalPolicy: required`) — approvals then flow through the bridge or board UI.

Company id for `mazewall`: `8f4ef932-d769-43b2-981a-d273ed715162`
(`GET /api/companies` auto-detects it too). Agent roster via
`GET /api/companies/<id>/agents`; adapters via `GET /api/adapters`.

## 2. Implementation state matrix

| Phase (plan.md) | State | Evidence |
|---|---|---|
| 1. Backlog ingest | **Real, this week** | `paperclip_backlog_sync.kts` POSTs issues + `blockedByIssueIds` in topo order, writes `paperclip_issue_id:` back into frontmatter, idempotent (second run = 0 creates). Live proof: MAZ-36 |
| 2. Telegram bridge | **Implemented**, not running as daemon | SSE `/activity` + approval buttons + `/api/approvals/:id/:action` callbacks in `scripts/paperclip_telegram_bridge.py`; needs bot token + a supervisor unit |
| 3. Resolution/git sync | **Implemented inside bridge** | `sync_git_lifecycle()` — frontmatter flip, move to `resolved/`, rebase+commit, conflict alert. Fallback for missing `metadata` reads `mazewall:backlog-file=` marker from description (Paperclip API does **not** persist `metadata`) |
| Dispatch trigger | Discovered | Assignment alone does NOT dispatch; `status → in_progress` does. Runs appear under `GET /api/issues/:id/runs` with `invocationSource` |
| Multi-model routing (Phase 4) | Manual | Roster exists (`GET /api/companies/:id/agents`); route by setting `assigneeAgentId` per issue — automate later from `component` frontmatter |

Known API quirks (do not rediscover): `metadata` is dropped on POST/PATCH — linkage must ride
in the description marker `<!-- mazewall:backlog-file=… -->`; DELETE `/api/issues/:id` works;
`--tests` filters that match nothing silently no-op on Gradle 9.

## 3. Migration mapping: orchestrator → hybrid

| Orchestrator capability | Hybrid home | Action |
|---|---|---|
| Backlog parsing/DAG (`BacklogParser`,`DependencyGraph`) | kts script (inlined today) | Migrate into a `:tools:orchestrator` main once the module rejoins settings (issue-181010); delete inlined copy |
| Issue picking/scheduling (`selectAndStartTasks`, slots, module exclusivity) | Paperclip board + `blockedByIssueIds` | **Migrate now** — board owns scheduling |
| ACP agent execution loop | Paperclip runs (adapters incl. `vibe`, `antigravity`, `codex_local`, `grok_local`, `jules`, `opencode_local`) | **Migrate now** |
| Telegram notify/approve | bridge py | Keep (already migrated) |
| Resolution/git lifecycle | bridge py | Keep |
| PR creation/CI watch/review loop (`GitHubCli`, `ReviewIssueLauncher`) | Orchestrator-only | **Keep unique** — no Paperclip equivalent yet |
| Rebase/conflict tooling, slot exclusivity barriers | Orchestrator-only | Keep until Paperclip worktrees mature |
| `checkBacklog` validation | Gradle task (currently offline w/ tools excluded) | Restore module to settings ASAP |

Net: the orchestrator shrinks to its genuinely unique core — **PR/CI lifecycle + review loop +
conflict machinery**. Everything else (parsing, scheduling, execution, approvals, resolution) has
a working hybrid home.

## 4. Tools that actually help from here

- **systemd user units** (or cron) for `paperclip_backlog_sync.kts` (every 10 min, flock already
  built-in) and the bridge daemon — replaces manual triggering.
- **`gh` CLI** (already a dependency of GitHubCli) for the kept PR/review loop.
- **`jq`** for ad-hoc board queries when curl+python feels heavy.
- **BpfSimulator-style golden tests**: same idea applies here — fixture backlog + fake HTTP
  transport (issue-181011 §4) before pointing at live 3100.

## 5. Suggested next steps (ordered)

1. Restore `:tools` in `settings.gradle.kts` (unblocks checkBacklog + orchestrator tests).
2. Land issue-181011 properly: port this ingest into a Gradle-run main using real
   `BacklogParser` (delete the inlined ~250 lines).
3. Start bridge under a user systemd unit; wire Telegram tokens via env file.
4. Route by `component` (Phase 4 table) with a tiny mapping in the sync script
   (`assigneeAgentId` chosen at POST time).
5. Then deprecate orchestrator scheduling paths (`OrchestratorDaemon.selectAndStartTasks`)
   behind a flag once two real issues complete end-to-end without intervention.
