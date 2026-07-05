# Autonomous Backlog Orchestrator

The Autonomous Backlog Orchestrator is a Kotlin-based daemon designed to automate the lifecycle of software development tasks. It acts as the bridge between a local markdown-based issue backlog, GitHub Issues, Jules (an asynchronous coding agent), and automated PR reviewers (Antigravity/agy).

## Features

- **Backlog Parsing & Dependency Graph:** Reads markdown files from `docs/internals/backlog`, parses their frontmatter (priority, dependencies, status), and selects the highest-priority, unblocked task.
- **GitHub Integration:** Automatically creates GitHub issues for unblocked tasks, links them to Pull Requests, and tracks CI build statuses.
- **Jules Session Management:** Triggers remote Jules sessions using the `jules` CLI and polls for completion.
- **Automated PR Review (Antigravity):** Invokes `agy` to perform automated code reviews on successfully built PRs before manual merging.
- **Telegram Notifications:** Integrates with Telegram to send status updates, alert on build failures, and ask for manual approvals to start tasks.

## Prerequisites

- **Java 21+** (for Gradle execution)
- **GitHub CLI (`gh`)**: Must be installed and authenticated (`gh auth login`).
- **Jules CLI (`jules`)**: Must be installed and authenticated (`jules login`).
- **Antigravity CLI (`agy`)**: Used for automated PR reviews.

## Configuration

The Orchestrator reads configuration from a `.ENV` file in the working directory and environment variables.

| Variable | Description | Default |
|----------|-------------|---------|
| `TELEGRAM_BOT_TOKEN` | (Optional) Token for Telegram bot notifications. | `null` |
| `TELEGRAM_CHAT_ID` | (Optional) Chat ID for Telegram notifications. | `null` |
| `JULES_REPO` | Target GitHub repository (e.g., `owner/repo`). | `Pilleo/mazewall` |
| `BACKLOG_PATH` | Path to the local issue backlog directory. | `docs/internals/backlog` |
| `GITHUB_TOKEN` | GitHub Personal Access Token (for CLI usage). | Uses system auth if unset |

*Note: If Telegram variables are not provided, the daemon falls back to local terminal prompts (requiring manual `y/n` input to start tasks).*

## Running the Orchestrator

Use the provided shell script to run the daemon:

```bash
# Run in foreground
./scripts/run_orchestrator.sh

# Run in background (logs output to logs/orchestrator.log)
./scripts/run_orchestrator.sh --background
```

## Architecture & State Machine

The orchestrator is implemented as a robust State Machine with the following states:
1. **`SELECT_TASK`:** Scans the `BACKLOG_PATH` for `.md` files starting with `issue-`, calculates dependencies, and prioritizes the next unblocked task.
2. **`AWAIT_START_APPROVAL`:** Asks the developer for approval via Telegram or Terminal. Once approved, retrieves or creates a GitHub Issue.
3. **`AWAIT_JULES_START`:** Polls for the automatically triggered Jules session ID.
4. **`AWAIT_PR_CREATION`:** Monitors the session and waits for Jules to open a Pull Request.
5. **`MONITOR_PR`:** Polls the PR's CI build status. If the build passes, requests a Jules code review. If the build fails, comments on the PR (deduplicated by commit SHA to prevent spamming) and alerts the developer.
6. **`RESOLVE_TASK`:** Moves the backlog file to `resolved/`, deletes the local state file, and regenerates the knowledge maps.

### State Persistence
To prevent context loss across daemon restarts, the orchestrator automatically writes its execution state and active task context (TIDs, SHAs, session IDs, PR/Issue numbers) to `.orchestrator_state.properties` in the workspace root. On boot, it automatically resumes from the last active state.

## Limitations & Known Issues
- Polling is frequent; be cautious of GitHub API rate limits.
- The daemon must run in an environment authenticated with the `gh`, `jules`, and `agy` CLIs.
