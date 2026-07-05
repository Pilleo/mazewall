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

## Architecture & Flow

1. **Parse Backlog:** Scans the `BACKLOG_PATH` for `.md` files starting with `issue-`.
2. **Prioritize:** Identifies unblocked tasks (dependencies are resolved/skipped) and picks the one with the highest priority.
3. **Approval:** Asks the developer for approval via Telegram or Terminal.
4. **Issue Creation:** Creates a GitHub Issue with the `jules` label.
5. **Agent Handoff:** Polls the Jules CLI to verify the agent session is active.
6. **PR Monitoring:** Polls GitHub for a linked Pull Request and checks the CI build status (`SUCCESS`, `FAILURE`, `IN_PROGRESS`).
7. **Automated Review:** Once CI passes, invokes Antigravity for a first-pass review.
8. **Merge & Resolve:** Waits for human approval and merge. Upon merge, moves the local backlog file to the `resolved/` directory and regenerates architectural knowledge maps.

## Limitations & Known Issues
- Currently, Jules session IDs are strictly parsed as Longs, which can cause failures due to integer overflow for large session UUIDs.
- Polling is frequent; be cautious of GitHub API rate limits.
