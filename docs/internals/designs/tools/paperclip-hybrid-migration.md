# Paperclip Hybrid Loop — Operator Guide & Migration Path

> Status (2026-08-24, evening): **All loop logic is Kotlin** (`HybridSupervisor` in
> `:tools:orchestrator`). The Python bridge is retired; `run_paperclip_loop.sh` is a
> thin serialized launcher over ingest + supervisor tick.

## 1. How to trigger the loop today

```bash
# One command = ingest + dispatch tick (routed by component, default worker Jules):
./scripts/run_paperclip_loop.sh              # --dry-run previews without side effects

# Long-running form (dispatch + CI watch + resolution + Telegram doorbell):
PAPERCLIP_TICK_SECONDS=60 ./gradlew :tools:orchestrator:supervisor -PincludeOrchestrator=true --daemon

# Supervisor knobs (env): PAPERCLIP_API_KEY/API_URL/COMPANY_ID,
#   PAPERCLIP_AGENT_ADAPTER (default jules), PAPERCLIP_COMPONENT_ROUTES (comp=urlKey,…),
#   PAPERCLIP_MAX_DISPATCH (default 1), PAPERCLIP_CI_STUCK_MINUTES (default 15),
#   TELEGRAM_BOT_TOKEN/TELEGRAM_CHAT_ID (optional doorbell + approval buttons)
```

Dispatch only ever picks issues carrying the `<!-- mazewall:backlog-file=… -->`
marker, so manual/board-native entries are never auto-dispatched. The supervisor is
stateless by design: dedupe rides board comments (`<!-- mazewall-ci-fail:<sha> -->`),
stuckness on check timestamps, resolution idempotency on `resolved/` existence. The
only persisted value is the approval watermark in `.supervisor_state.properties`.

### Phone access (operator setup)

The board binds `127.0.0.1:3100`. For phone management expose it over your tailnet:
`tailscale serve --bg 3100` (or a WireGuard peer + reverse proxy) — never a public
listener. Approvals then work from the mobile PWA directly, or via the Telegram
approval cards when bot tokens are configured.

### systemd user units (suggested)

```ini
# ~/.config/systemd/user/mazewall-supervisor.service
[Unit]
Description=mazewall Paperclip hybrid supervisor
[Service]
WorkingDirectory=%h/Documents/code/java/jseccomp
Environment=PAPERCLIP_API_KEY=local
Environment=TELEGRAM_BOT_TOKEN=%h/secrets/tg-token   # if used
ExecStart=/usr/bin/env bash -c './gradlew -q :tools:orchestrator:supervisor -PincludeOrchestrator=true --daemon'
Restart=on-failure
[Install]
WantedBy=default.target
```

Known limitation: Telegram delivery paths are transport-seam-tested but not yet
exercised against live bot API (no tokens configured at migration time).

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

### Jules agent tuning for multi-day sessions (2026-08-25)

| Setting | Old | New | Why |
|---|---|---|---|
| `sessionDeadlineMinutes` | 360 (6h) | **4320** (72h) | Jules sessions can span days; 6h flags active work as stale |
| `retryBudget` | 3 | **5** | More headroom for transient failures over long-running tasks |
| `heartbeat.intervalSec` | disabled | **300** | Without it, completed sessions sat invisible forever |
| `pollCadenceSeconds` | 300 | 300 (unchanged) | 5 min catches state changes |
| `requestTimeoutSeconds` | 30 | 30 (unchanged) | Per-call HTTP timeout |

The adapter's internal watch window (`JULES_WATCH_WINDOW_MS`, 6h per heartbeat
execution) is fine: after 6h the heartbeat returns, state persists via
`sessionParams`, and the next heartbeat resumes from the stored cursor.

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
| 1. Backlog ingest | **Real, live-verified** | `paperclip_backlog_sync.kts`: POST with `blockedByIssueIds` in topo order, idempotent via `paperclip_issue_id:` frontmatter write-back (second run = 0 creates), `--force` PATCH maintenance, description marker for bridge resolution fallback, company-id auto-detect. Full batch: 80 issues ingested 2026-08-24 |
| 2. Telegram bridge | **Implemented + event-shape verified** | `sync_git_lifecycle()` resolution proven end-to-end live (probe issue → done → frontmatter flip → move to `resolved/` → git commit). Event handlers validated against the real `/activity` feed (`issue.updated` w/ `details.status`, `environment.lease_released`+failure). Company auto-detect added; marker-based file lookup with out-of-repo re-anchor. Telegram delivery itself still needs bot tokens to observe live |
| 3. Resolution/git sync | **Migrated from orchestrator RESOLVE_TASK** | Orchestrator's local resolution state is retired for hybrid-loop issues; bridge commits directly (verified commit "Resolve …" by bridge) |
| Dispatch trigger | Verified | Assignment alone does NOT dispatch; `status → in_progress` does. Runs appear under `GET /api/issues/:id/runs` with `invocationSource` |
| Jules worker config | **Fixed & live** | Agent needed `adapterConfig.repository` + `baseBranch` and the API-key secret bound at `env.JULES_API_KEY` (both `adapterConfig.env` and `runtimeConfig.env`). Dispatch now reaches the Jules API; sessions park at plan-approval (`planApprovalPolicy: required`) with `scheduled_retry` polling — approvals flow through bridge/board UI |
| Multi-model routing (Phase 4) | **Implemented** | plan.md routing table lives in `run_paperclip_loop.sh` (`agent_for_component`): enforcer/kernel/portal→antigravity, profiler/platform→grok impl-dev, docs/spec→founding engineer, default→jules. Resolved against live roster per tick |

Known API quirks (do not rediscover): `metadata` is dropped on POST/PATCH — linkage must ride
in the description marker `<!-- mazewall:backlog-file=… -->`; DELETE `/api/issues/:id` works;
`--tests` filters that match nothing silently no-op on Gradle 9; agent PATCH endpoint is
`/api/agents/:id` (not under `/api/companies/:id`).

### Orchestrator retirement state

| Orchestrator piece | Verdict after verification |
|---|---|
| SELECT_TASK / slots / exclusivity | **Retired** — board owns scheduling (`backlog` + `blockedBy`) |
| AWAIT_START_APPROVAL | Board approvals + bridge (Telegram pending tokens) |
| AWAIT_JULES_START / AWAIT_PR_CREATION | jules adapter native |
| CI_RUNNING watch, @jules review protocol, rebase/merge machinery | **Still orchestrator-unique** — no Paperclip equivalent; candidate for gh-aw later |
| RESOLVE_TASK | **Retired** — bridge `sync_git_lifecycle()` verified live |

## 3. Migration mapping: orchestrator → hybrid (final, 2026-08-24)

| Orchestrator capability | Hybrid home | State |
|---|---|---|
| Backlog parsing/DAG (`BacklogParser`,`DependencyGraph`) | `paperclip_backlog_sync.kts` (inlined copy) | ✅ Live; dedupe into one Kotlin main later (issue-181010) |
| Issue picking/scheduling (`selectAndStartTasks`, slots) | Board `backlog`+`blockedBy`; `DispatchSelector` in supervisor | ✅ Verified (parity tests + live dispatches MAZ-39…) |
| Component→agent routing (Phase-4 table) | `ComponentRouter` | ✅ Resolves against live roster |
| ACP agent execution loop | Paperclip runs (jules/antigravity/vibe/grok/codex/… adapters) | ✅ Adapter-native |
| Telegram notify/approve | `TelegramBot` + `EventNotifier` in supervisor (approval cards, callbacks, doorbell) | ✅ Code + seam tests; live delivery awaits bot tokens |
| CI watch (`CiRunningState`) | `CiWatch`: rollup poll → tokened board comment + TG; **no agent-feedback comments** (Jules watches own PRs; adapter mirrors activities) | ✅ Stateless, 9 scenario tests; live PR path fires on first Jules PR |
| Resolution/git lifecycle (`ResolveTaskState`) | `BacklogResolver` | ✅ Live: probe MAZ-117 resolved by commit fc7f87fd; idempotent vs MAZ-116 |
| Rebase/conflict tooling, worktree slot barriers | Orchestrator-only until Paperclip worktrees mature | ⏸ Keep |
| `@jules` PR review protocol / auto-merge gating | Deferred — observe real approved-review shapes first | ⏸ Keep orchestrator paths available |
| `checkBacklog` validation | Gradle task (opt-in `-PincludeOrchestrator=true`) | ✅ Green |

Net: the running daemon is gone from the critical path. `OrchestratorDaemon.kt` and its
state machine stay compiled/tested as reference and fallback — do not schedule it while
the supervisor owns a component's lifecycle; retire code only after several quiet weeks.

Paperclip **Routines** (cron → agent issue templates) are deliberately unused for
deterministic gating; they are the right home for future fuzzy sweeps (stale-backlog
triage, summary refresh).

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
