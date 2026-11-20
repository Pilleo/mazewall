---
document_type: execution_plan
base_revision: d957719f6752f002151e8818613bfdf6146389b3
---

# OpenCode V1 / Zed Continuation Plan

Status: monitored execution approved by "good, continue". First repair committed
as e3bbfa7b; round1 committed9c41f3e7 and addressed original findings. Scoped
re-review found one introduced regression; fresh Luna6 worker `maze-scanner-3`
owns round2 on exact9c41 before integration.

> **For agentic workers:** Use executing-plans for coordinator work. Use
> subagent-driven-development only after an explicit scoped worker assignment.
> Each task uses a fresh brief and checkpoint; workers never delegate further.

**Goal:** Resume the MazeWall integration foundation with OpenCode V1 in Zed,
small auditable tasks, preserved work, and explicit recovery and verification.

**Architecture:** Zed runs the existing registry-managed OpenCode V1 agent.
One coordinator retains assignment/review authority; coding and review start
serially in owned private worktrees. Agent Orchestrator is the proposed external
worker-lifecycle supervisor around OpenCode V1, initially in explicitly monitored
mode. An unattended/background fleet remains gated by the original recovery
acceptance criteria; production assignment is not implicit in choosing V1.

**Tech stack:** OpenCode1.18.35, Zed1.23.2, `openai/gpt-6-luna` for tests/checkers,
Agent Orchestrator v0.13.5 (verified pilot artifact), existing Git
worktrees/checkpoints, Gradle9.6.1, existing serial native lane.

**Spec and evidence:**
- `integrations/2026-10-06-mazewall-integration-foundation-plan.md` and its design.
- `.superpowers/sdd/2026-10-06-mazewall-integration-foundation-plan/fleet-protocol.md`.
- `docs/superpowers/plans/2026-10-08-reliable-fleet-rollout.md` and the retained
  `fleet-canary/ao-profile/pilot-report.md` in that recovery workspace.
- Operator decisions: use V1 through Zed; Luna6 for tests; retain private worktree
  isolation and serial review/integration. The old noon cutoff was subsequently
  overridden; it is not a new execution deadline.

## Global constraints

- Daily entry point: `opencode`1.18.35. `opencode2` is an isolated experiment.
- One writer per worktree, one assignment authority, one build/native lane.
- No nested delegation, automatic replay, direct default-branch pushes, remote
  publication, or competing Paperclip/AO/Hermes dispatch into the same scope.
- Preserve every existing commit, staged file, untracked file and checkpoint.
- Stage only task-owned changes. Root settings/build/catalog/backlog index are
  coordinator-owned; the current dirty tree contains unrelated operator work.
- Never inspect, modify or filter `GITHUB_TOKEN`; operator owns authentication.
- Keep MazeWall fail-closed behavior, JVM syscall floor, Landlock-before-Seccomp,
  FFM/errno/layout ownership, and platform-thread installation invariants.
- Every Gradle/native invocation and commit acquires the existing shared lock.
- Agent deadlines do not change kernel-test budgets, assertions or required skips.
- Do not resume the old ~350k-token task12 session. Read durable state instead.
- OmO5.1.26 failed recovery checks. Do not reactivate it as the fleet supervisor.
- Other harness research is deferred from this continuation; no new framework,
  dependency, credential migration or harness installation is part of the plan.

## Operating mode and admission

This plan proposes **explicitly monitored, serial execution** first. Before a
production writer starts, the operator must record that assignment and acceptance
of monitored mode; the earlier automatic-fleet gate is not silently waived.
If monitored-mode approval is absent, only baseline checks and recovery probes run.

Unattended or parallel fleet admission additionally requires automatic bounded
failure reporting, the chosen explicit fallback policy, blocked-tool cancellation,
restart recovery and exclusive-writer tests. AO's manual ~0.5s interruption is
useful evidence but does not satisfy an automatic deadline requirement.

Task-state vocabulary: `RUNNING`, `DONE`, `BLOCKED`, `FAILED`, `CANCELLED`.
Completion requires files/commit/test evidence; a model's final text is not proof.
On cancellation, replacement stays refused until both the old session and its
owned modifying processes have ceased. Retain the old worktree and checkpoint.

## Agent Orchestrator's role

The proposed stack is:

```text
Operator / Zed coordinator
  -> approved scoped assignment
  -> Agent Orchestrator worker-lifecycle supervision
  -> OpenCode 1.18.35 ACP worker in one owned worktree
  -> fresh independent review
  -> existing repository review/integration path
```

- Zed and AO are separate clients; AO does not automatically adopt an existing
  Zed thread. Opening another writable thread in a worker's worktree requires a
  completed ownership handoff, not simply opening the same directory in Zed.
- AO provides task/session visibility, process ownership, restart recovery and
  explicit interruption. Its actual V1 pilot preserved conversation, commit and
  untracked state, rejected an occupied branch, and stopped a live tool.
- Explicit interruption is verified; automatic provider failure/deadline/fallback
  behavior remains unproven. This plan does not call the pilot unattended-ready.
- Use operator-started workers only. Do not activate AO's autonomous project
  orchestrator alongside the repository daemon. Repository start/review/merge
  authority stays with the existing approved path; inspect active slots before
  assigning a local AO worker and record any deliberate assignment override.
- The preserved scanner tree is not assumed adoptable by AO. Prove existing-tree
  support first. If AO requires a new worktree, an operator-approved handoff must
  preserve the original tree, copy only committed accepted history to the new
  owned branch, and update the plan's file map, TASK variable, and brief/checkpoint
  paths before a writer starts.
- AO's pilot daemon is currently stopped. Production activation/configuration is
  separate from the owned monitored daemon now running at the approved data/run
  directory. It has one explicitly assigned worker and no autonomous orchestrator.

## File map

| File/location | Responsibility |
|---|---|
| `~/.config/opencode/opencode.json` | Existing V1 provider timeout, MCP and permission configuration |
| `~/.config/opencode/agents/fleet-tests.md` | Luna6 checker with edit/delegation denied |
| `~/.config/zed/settings.json` | Existing registry agent; preserve unrelated editor settings |
| `~/.local/state/mazewall-v1-ao/` | Proposed AO data/run directory after explicit activation; short path avoids pilot Unix-socket length limits |
| Recovery workspace `v1-continuation/baseline.md` | Versions, effective config checks, accepted operating mode |
| Recovery workspace `v1-continuation/scanner-brief.md` | Exact first writer scope and acceptance |
| Recovery workspace `v1-continuation/scanner-checkpoint.md` | Current HEAD, changes, commands/results, owned processes, next action |
| Recovery workspace `v1-continuation/scanner-review.md` | Independent scope/correctness/test review |
| `.worktrees/fleet-20261008-lazy-scan/` | Preserved read-only scanner source tree, clean at2f9a5995 |
| `/home/leanid/.local/state/mazewall-v1-ao/data/worktrees/maze-scanner/maze-scanner-1/` | Preserved first repair at e3bbfa7b, clean; branch `fleet/v1/scanner-order` |
| `/home/leanid/.local/state/mazewall-v1-ao/data/worktrees/maze-scanner/maze-scanner-2/` | Preserved round1 commit9c41f3e7 and generated attach-marker evidence; branch `fleet/v1/scanner-order-fix-1` |
| `/home/leanid/.local/state/mazewall-v1-ao/data/worktrees/maze-scanner/maze-scanner-3/` | Actual fresh round2 writer, clean at9c41 before dispatch; branch `fleet/v1/scanner-order-fix-2` |
| `scripts/gradle_lazy_resolution.py` in that tree | Filesystem-order alias resolution repair |
| `scripts/tests/gradle_source_discovery_test.py` in that tree | Actual-filesystem regression coverage |
| `docs/internals/designs/core/testing-strategy.md` in that tree | Exact discovery limits and evidence guidance |
| `docs/internals/backlog/issue-20261008-142748-453-restrict-gradle-resolution-discovery-to-checkout-s.md` in that tree | Existing task acceptance/evidence, not a duplicate issue |
| Recovery workspace `v1-continuation/final-verification.md` | Exact combined/candidate/native gate ledger |

In commands below, initialize these variables in each new shell:

```bash
ROOT=/home/leanid/Documents/code/java/jseccomp
STATE="$ROOT/.superpowers/sdd/2026-10-06-mazewall-integration-foundation-plan"
LOCK="$STATE/verification.lock"
TASK=/home/leanid/.local/state/mazewall-v1-ao/data/worktrees/maze-scanner/maze-scanner-3
```

## Task 1 — Confirm and retain the V1/Zed baseline

**Files:** V1 global config/checker above; create `v1-continuation/baseline.md`.
**Consumes:** operator's V1 choice and preserved pre-upgrade backups.
**Produces:** verified V1/Zed baseline and a record of operating-mode approval.

- [x] Restore1.18.35 and V1 config/checker; retain V2 separately. No session DB
  rollback. Evidence: `run-9dc37c546c5e`, `run-f2e30778af18`.
- [x] Verify Zed's managed V1 accepts config, MCP connects and actual Luna6 responds.
- [ ] Restart the Zed OpenCode thread and verify the model picker and both MCP tools
  in that real editor thread. CLI config validation is not a GUI integration test.
- [ ] Record the currently selected coding/review model from available models;
  tests/checkers stay `openai/gpt-6-luna`. Do not guess an unavailable model ID.
- [ ] Record versions, global config hashes and approval in the baseline file.

Baseline commands:

```bash
opencode --version
opencode mcp list
opencode models openai
opencode run --agent fleet-tests --model openai/gpt-6-luna --format json \
  'Reply exactly V1_ZED_READY. No tools, edits or delegation.'
```

Expected:1.18.35, agentmemory/jbcontext connected and exact assistant marker.
The global permission policy remains the operator's existing policy; apply narrower
rules to owned workers/checkers rather than changing it as an unrelated task.

## Task 2 — Establish bounded monitored operation and ownership

**Files:** create baseline/brief/checkpoint/review files named in the map.
**Consumes:** Task1 evidence, existing worktree ownership and operator approval.
**Produces:** a single AO-supervised, explicitly monitored task with a fresh bounded
context, or an evidence-backed admission blocker.

- [ ] Record AO v0.13.5 artifact provenance from `fleet-canary/ao-profile/pilot-report.md`
  and verify its OpenCode adapter selects major1 and the restored1.18.35 binary.
  Use `~/.local/state/mazewall-v1-ao/data` and
  `~/.local/state/mazewall-v1-ao/running.json` after approval, retaining pilot
  artifacts and fixture worktrees. Bind only to loopback. Use existing provider
  access; no login migration.
- [ ] Inspect the repository daemon's active assignments, then record the explicit
  AO worker scope and lifecycle-only role. Keep AO autonomous planning/dispatch off.
- [ ] Inspect Git status and active sessions/processes before assignment. Record
  task ID, session ID, worktree, base/head, model, declared scope and owned logs.
- [ ] Confirm the retired task12 session has no live modifying tool. A free
  verification lock alone is not proof that a writer has stopped.
- [ ] Resolve AO's existing-worktree adoption/handoff before launch; require the
  final assigned path and base SHA to match the fresh brief. Do not silently start
  from the coordinator branch or overwrite the preserved scanner worktree.
- [ ] Record monitored-mode approval. Keep background/unattended fleet admission
  blocked if the full automatic recovery contract remains unproven.
- [ ] Start one new task from brief/checkpoint/diff. Initial brief target<=10k tokens;
  rotate at the next safe checkpoint before80k session tokens. These are measured
  operating limits, not claims that a prompt enforces a kernel deadline.
  Measure the latest request/context occupancy including cached input, rather than
  comparing the80k threshold with cumulative billed tokens from the whole session.
- [ ] Use one model request at a time initially. The writer and fresh independent
  reviewer run serially; reviewer is read-only. No nested task calls.
- [ ] Bound coordinator status/API waits to60s. At5min without activity, inspect
  AO's actual tool/PID/log/permission state and publish `BLOCKED` or a diagnosed
  running-command status. A documented quiet build is not automatically a dead worker.
- [ ] Declare a30min writer-task wall-clock budget, checked by the attending
  coordinator. On expiry, checkpoint and stop through the existing client/session
  interruption controls; verify physical cessation before replacement. Long checks have their
  own recorded budgets and remain governed by the original test scripts.
- [ ] Checkpoint after discovery, red test, implementation, verification, and review;
  also before long commands. Include current command/PID/start-time/log when active.

If existing tools cannot expose/stop the owned work reliably, return `BLOCKED`
with evidence. Do not invent a dispatcher/watchdog framework or label manual
observation as automatic supervision.

First brief content:

```yaml
task_id: v1-scanner-filesystem-order
mode: monitored-serial
worktree: /home/leanid/.local/state/mazewall-v1-ao/data/worktrees/maze-scanner/maze-scanner-1
base_revision: 2f9a59952686f546cb243ad620106449b897fa1b
scope: source inventory resolver, real filesystem fixtures, testing guidance, existing scanner issue
checker_model: openai/gpt-6-luna
acceptance: expand each symlink before processing subsequent dot-dot components; preserve all prior discovery/parser contracts
blocked_on: old writer cessation and explicit worktree assignment
```

## Task 3 — Repair the preserved scanner blocker

**Files in the assigned tree only:** helper, filesystem tests, testing guidance and
the existing scanner issue in the map. Root config/build/consumer/parser/hook files
are outside this repair scope.
**Interface:** preserve `resolve_source_path(path: Path) -> tuple[Path, bool]`.
**Produces:** reviewed inventory repair with actual red/green evidence.

- [ ] Read current root/nearest instructions and `.agents/CODE_QUALITY.md`. Confirm
  the tree is still clean at the brief's base; reconcile any difference first.
- [ ] Outline the helper/tests and inspect the resolver. `adkw slice` currently
  falls back with exit3 on these Python files; use a standard-library AST outline
  instead of treating that fallback as a valid structural outline.
- [ ] Run `./scripts/adkw blast-radius resolve_source_path` before changing the
  symbol. If the index does not support Python, record that limit and inspect the
  actual `source_inventory`, `alias_source_inventory` and `main` callers directly.
- [ ] Add this regression to `SourceDiscoveryTest` using its existing fixtures:

```python
def test_alias_expands_symlink_before_parent_component(self):
    self.write(".gitignore", "/hidden/\n/real-target/\n")
    self.write("hidden/module/build.gradle.kts", EAGER)
    self.write("real-target/module/build.gradle.kts", ACTION)
    (self.directory / "hidden/deep").mkdir()
    (self.directory / "real-target/pivot").symlink_to(
        "../hidden/deep", target_is_directory=True
    )
    (self.directory / "adapter").symlink_to(
        "real-target/pivot/../module", target_is_directory=True
    )
    self.assert_eager("hidden/module/build.gradle.kts")
```

- [ ] Run the exact new case against2f9a5995 and retain failure output:

```bash
cd "$TASK"
python3 scripts/tests/gradle_source_discovery_test.py \
  "$PWD/scripts/check_gradle_lazy_resolution.sh" \
  SourceDiscoveryTest.test_alias_expands_symlink_before_parent_component
```

Expected RED: old resolver normalizes to the clean fake target and misses the
actual eager script. The real filesystem target is `hidden/module`.

- [ ] Replace lexical `abspath` collapsing with ordered component traversal:
  consume pending path components, expand `readlink` targets at the point reached,
  and only then process later `..`. Anchor absolute targets to the checkout
  without normalizing away components. Reject crossing the root/private boundary
  before accessing it. Preserve cycle/40-link bounds, inode graph visitation,
  readability errors, unsupported-file rejection and default-index authority.
  No whole-path `resolve()` that follows external targets before validation.
  Use this component-walk implementation, checking it against the existing
  boundary/readability/cycle fixtures before retaining it:

```python
def resolve_source_path(path: Path) -> tuple[Path, bool]:
    import os
    import stat
    from collections import deque

    root = Path.cwd().resolve()
    requested = path
    links = set()

    def anchored_parts(raw: str) -> list[str]:
        parts = [part for part in raw.split("/") if part]
        if raw.startswith("/"):
            prefix = list(root.parts[1:])
            if parts[:len(prefix)] != prefix:
                raise ValueError("alias leaves checkout")
            parts = parts[len(prefix):]
        return parts

    try:
        pending = deque(anchored_parts(os.fspath(path)))
        cursor = root
        while pending:
            part = pending.popleft()
            if part == ".":
                continue
            if part == "..":
                if cursor == root:
                    raise ValueError("alias leaves checkout")
                cursor = cursor.parent
                continue
            if links and part in {".git", ".gradle", ".worktrees", "build"}:
                raise ValueError("alias crosses generated/private boundary")
            candidate = cursor / part
            metadata = os.lstat(candidate)
            if stat.S_ISLNK(metadata.st_mode):
                identity = (metadata.st_dev, metadata.st_ino)
                if identity in links or len(links) >= 40:
                    raise ValueError("alias cycle or 40-link resolution bound")
                links.add(identity)
                target = os.readlink(candidate)
                target_parts = anchored_parts(target)
                if target.startswith("/"):
                    cursor = root
                pending.extendleft(reversed(target_parts))
                continue
            cursor = candidate
            if pending and not stat.S_ISDIR(metadata.st_mode):
                raise NotADirectoryError(20, "Not a directory", str(cursor))
            if links:
                if not (stat.S_ISREG(metadata.st_mode) or stat.S_ISDIR(metadata.st_mode)):
                    raise ValueError("unsupported target type")
                required = os.R_OK | (os.X_OK if stat.S_ISDIR(metadata.st_mode) else 0)
                if not os.access(cursor, required, effective_ids=True):
                    raise PermissionError(13, "Permission denied", str(cursor))
        return cursor.relative_to(root), bool(links)
    except (OSError, ValueError) as error:
        if links:
            raise ValueError(f"Unsupported source symlink: {requested}: {error}") from error
        raise
```

This code is proposed in the plan, not an implemented or passing fix. Tests must
also cover a non-directory followed by `..` and dot/absolute target variants;
do not relax existing alias boundary policy merely to admit a new path spelling.
Independent review additionally requires checking ancestors already consumed
before the first symlink and retaining trailing-slash directory semantics. The
round1 brief/ruling controls these corrections; the prototype is not authority
to keep its reproduced omissions.
- [ ] Add actual-filesystem cases for a symlink target whose later `..` would hide
  a private or external traversal, and an unreadable intermediate directory. They
  must fail explicitly; a clean decoy must never turn them into a passing scan.
- [ ] Run meaningful focused verification once after each source correction:

```bash
python3 scripts/tests/gradle_source_discovery_test.py
bash scripts/tests/gradle_lazy_resolution_test.sh
bash scripts/check_gradle_lazy_resolution.sh
git diff --check
```

Expected: all prior36 filesystem methods plus the new cases, all98 lexical
fixtures, and actual source inventory pass with no unexpected skips. Confirm
the accepted lexical-parser prefix/fixture file remains unchanged.
- [ ] Update the checkpoint/issue/guidance with exact results and remaining
  stable-checkout/TOCTOU limits. Commit only reviewed task files under the shared
  lock and unchanged safe hook. Use verified operator/task identity; do not
  inherit the historical hook fixture's local Git identity or guess a replacement.

## Task 4 — Independent review and serial integration

**Files:** create `v1-continuation/scanner-review.md`; coordinator-owned index/ledger.
**Consumes:** Task3 commit/diff/red-green logs, not the writer's entire conversation.
**Produces:** explicit acceptance or a bounded correction request.

- [ ] Fresh read-only reviewer checks scope, filesystem-order correctness,
  non-bypassed permission errors, alias/cycle/default-index coverage, parser
  immutability and regression quality. Checker uses Luna6; choose a separately
  recorded capable model for the correctness review if needed.
- [ ] Record `ACCEPTED` or `CHANGES_REQUIRED`, exact reviewed HEAD, findings and
  evidence paths. Corrections return to the same owned worktree with a short brief.
- [ ] Inspect coordinator Git status and reviewed cumulative source range before
  serial integration. Inventory fixes15ea5166/c77e7bf7/2f9a5995 are still private;
  the earlier parser/consumer changes already have integrated equivalents.

```bash
cd "$ROOT"
git diff --stat d51b017c..fleet/v1/scanner-order-fix-2
git diff --check d51b017c..fleet/v1/scanner-order-fix-2
```

- [ ] After operator/review approval, integrate only that accepted inventory
  range via the existing scoped review path. Resolve conflicts without dropping
  user work. Partial-stage only the task-owned backlog row/count and ledger edits.
- [ ] Run the actual main-tree scanner and its filesystem/parser suites. Keep the
  scanner issue open until the repository's acceptance path closes it with evidence.

## Task 5 — Reconcile the optional starter before final candidate selection

**Files:** coordinator-owned `settings.gradle.kts` pending starter inclusion;
existing starter module, index and ledger. No adapter implementation expansion.
**Consumes:** current user/coordinator diff ownership and approved Spring scope.
**Produces:** a reviewed tracked candidate definition, not an accidental dirty build.

- [ ] Separate the existing starter inclusion hunk from unrelated user changes.
  Verify it includes `:integrations:spring:mazewall-spring-boot-starter` only under
  `includeSpringIntegration=true` and preserves the default build graph.
- [ ] Verify the actual optional module without inventing a mirror test:

```bash
cd "$ROOT"
flock "$LOCK" ./gradlew -PincludeSpringIntegration=true \
  :integrations:spring:mazewall-spring-boot-starter:assemble
```

- [ ] Obtain scoped review, commit only the approved settings hunk, and reconcile
  integration/backlog bookkeeping. Do not stage the whole dirty tree/index file.
- [ ] Record the exact candidate source HEAD and create an explicitly assigned
  clean acceptance worktree only after inspecting existing paths/branches.
  Candidate verification must include this tracked settings decision.

After explicit assignment, inspect `git worktree list --porcelain`, require that
both the destination and branch below are unused, and then create the acceptance
tree (never delete an existing path or force-reset an existing branch):

```bash
cd "$ROOT"
git worktree add -b fleet/v1/final-acceptance \
  .worktrees/fleet-v1-final-acceptance HEAD
```

## Task 6 — Run combined and exact-candidate acceptance

**Files:** `v1-continuation/final-verification.md`, existing compatibility manifest,
new candidate-namespaced consumer evidence/locks. Coordinator owns these gates.
**Consumes:** accepted tracked source plus Task4/5 evidence.
**Produces:** precise pass/failure/prerequisite status for the final candidate.

- [ ] Enter `.worktrees/fleet-v1-final-acceptance`, verify `git status --short` is
  empty and record its HEAD. Keep ROOT/STATE/LOCK bound to the coordinator paths
  declared above; gate artifacts belong to this clean candidate tree.
- [ ] Run the combined opt-in merge gate once after focused checks pass. Retain
  the existing supported TestKit isolation init script; do not delete/chown caches
  or exclude verification tasks to get a green result:

```bash
flock "$LOCK" bash scripts/koog-build.sh \
  --init-script "$STATE/testkit-isolation.init.gradle" \
  -PincludeSpringIntegration=true -- build
```

- [ ] In the clean assigned acceptance tree, stage the exact producer revision
  and independent consumer matrix following `tests/consumers/README.md`:

```bash
revision=$(git rev-parse HEAD)
version="0.1.0-candidate.${revision:0:8}"
repository="$PWD/build/fleet-consumer-repository/${revision:0:8}"
flock "$LOCK" ./gradlew --console=plain --no-build-cache \
  --no-configuration-cache --rerun-tasks \
  --init-script tests/consumers/stage.init.gradle \
  -PconsumerVersion="$version" -PconsumerRepository="$repository" \
  :platform:publishAllPublicationsToConsumerBaselineRepository \
  :enforcer:publishAllPublicationsToConsumerBaselineRepository
./scripts/check_integration_consumers.sh --repository "$repository" \
  --version "$version" --write-locks --evidence-dir "$PWD/build/consumer-lock-generation"
./scripts/check_integration_consumers.sh --repository "$repository" \
  --version "$version" --evidence-dir "$PWD/build/consumer-acceptance"
```

The consumer script acquires the shared lock internally; do not wrap it in the
same lock again. Review only the new coordinate's locks; preserve historical ones.
Record producer SHA/artifact hashes separately from any later lock-only commit.
- [ ] Run fresh serial final kernel/container evidence because the overall
  integration foundation includes native changes, even though the scanner repair
  itself does not install containment:

```bash
flock "$LOCK" ./gradlew integrationTest
flock "$LOCK" ./gradlew integrationTestFreshJvm
flock "$LOCK" env MAZEWALL_CONTAINER_ENGINE=docker \
  MAZEWALL_DOCKER_HOST=unix:///var/run/docker.sock ./scripts/run_tests.sh
```

- [ ] Reconcile actual generated Koog and finished Boot-JAR consumer evidence at
  the selected producer revision, using their existing consumer READMEs. Reject
  stale artifact substitution or task-excluded production certification.
- [ ] Record each failure before another run. Retest only after a correction or
  diagnosed prerequisite change. CET-positive verification on an inactive runtime,
  known profiler/procfs observations, remote publication and human/overhead gates
  remain explicit limitations until separately proven.
- [ ] Finish with `./scripts/adkw check-backlog` and `git diff --check`. Report
  `ready for review` only when required gates pass; do not call it release-ready
  while R0/R1 or required native/framework acceptance remains open.

## Success criteria

- Zed and terminal agree on the V1 daily configuration; Luna6 checkers and MCP work.
- Agent Orchestrator supervises only explicitly admitted V1 workers, with a known
  data/run directory and no competing autonomous dispatcher.
- Every task has one known owner, fresh bounded context and durable recovery state.
- The symlink/`..` false negative is reproduced, repaired and independently reviewed.
- Only accepted scoped changes enter the final tracked candidate.
- Combined, packaged consumer and fresh native results name exact tested revisions.
- Automatic fleet readiness is never inferred from a manual abort, happy-path
  model response, marketing claim or cached test result.
