# How to Turn "Slow" AI Models into Effective Electronic Juniors

Lately, using asynchronous or "warm-start" AI models is proving much cheaper than constantly running fast, synchronous ones. Take Jules from Google as an example: it works slowly — sometimes taking 15–30 minutes per task — but in terms of token economy it is significantly more affordable than lightning-fast alternatives.

The problem is that the current experience of interacting with such systems is poor due to high latency. The goal of this orchestrator is to change the interaction model entirely: instead of waiting for a response, you hand the system a task and walk away. The model works in the background, and the orchestrator manages the full lifecycle from task selection to merge.

---

## The Pipeline

Each task passes through a well-defined state machine:

1. **Task Selection** — The orchestrator selects a task from a structured backlog directory. Each task is a Markdown file with YAML frontmatter describing severity, status, and dependencies. Only tasks whose declared dependencies are already resolved are eligible.
2. **Approval Gate** — A Telegram notification is sent to the owner with a task summary. The task only proceeds after explicit approval.
3. **GitHub Issue Creation** — A GitHub Issue is created from the task file, which acts as the canonical task anchor.
4. **Jules Session Trigger** — A Jules coding session is started via the REST API. The prompt includes the full original task description plus a block of project-specific quality and safety invariants (FFM layout correctness, no silent security bypasses, Loom carrier thread safety, etc.).
5. **PR Monitoring** — The orchestrator polls for a PR linked to the GitHub Issue. Once found, it tracks CI build status.
6. **AI Review** — Once CI passes, Jules is asked to post a structured code review comment on the PR as a read-only reviewer. The review enforces security-specific checks: sandboxing bypass gaps, off-heap memory lifecycle, concurrency correctness.
7. **Human Review Gate** — A second Telegram notification is sent with the review verdict. The owner makes the final merge decision.
8. **Merge** — The orchestrator approves and merges the PR.

---

## Parallel Execution

The orchestrator manages multiple task slots concurrently. Each slot runs an independent state machine, so several Jules sessions can be in-flight simultaneously — one waiting for CI, another waiting for review, a third just starting. This is the core reason the economics work: slow models become viable when you pipeline them in parallel without blocking human attention.

---

## Conflict Resolution

When a PR cannot be merged cleanly due to conflicts with master, the orchestrator does not simply retry. It spawns a new Jules session with explicit context:

- The URL of the conflicting PR.
- The head branch name of that PR.
- The full original task description.

Jules is instructed to re-implement the changes cleanly on top of the current master, using the previous generation as reference. The old PR is labeled `superseded`. This makes conflict resolution automatic and traceable without losing the history of what was attempted.

---

## Dependency-Aware Scheduling

The backlog uses a dependency graph to prevent conflicting task assignments. Each task file can declare which other tasks it depends on. The scheduler only picks tasks whose dependencies are already in the `resolved` state. This eliminates most scheduling-level conflicts before they reach the code level — Jules sessions never race on the same files by design.

---

## Automated Review Campaigns

When the active task queue runs low, the orchestrator can automatically launch review sessions — instructing Jules to audit individual modules and create new backlog issues for any findings. These new issues are then processed through the same pipeline. A Telegram notification is sent at the end of each review campaign for the owner to triage.
