---
name: create_backlog_issue
description: Standardized protocol for documenting newly discovered backlog issues, bugs, features, architectural gaps, or security/performance/testing findings in mazewall.
---

# Skill: Create Backlog Issue

This skill provides a standardized protocol for documenting a newly discovered backlog issue, bug, feature request, architectural gap, or kernel-level nuance in `mazewall`.

## Protocol

### 1. Classification & Priority Assignment

**Severity Criteria:**
- **CRITICAL:** Remote execution bypass, trivial sandbox escape without ACE, severe memory corruption.
- **HIGH:** Local privilege escalation within sandbox, core whitelist bypass, architectural boundary break.
- **MEDIUM:** Information leak, usability flaw, performance regression, multi-threading race condition.
- **LOW:** Documentation drift, minor nitpicks, non-critical DX friction.

**Priority Assignment (`high` | `medium` | `low`):**
Assign **`high`** to changes that multiply developer velocity, safety, and autonomy:
- Refactorings that introduce type safety (e.g. Type-State pattern, value classes, compile-time token proofs).
- Improvements to testing harness, ArchUnit rules, and automated test coverage.
- Enhancements to CI/CD pipelines, build barriers, and validation rules.
- Orchestrator tooling improvements (parallel task scheduling, conflict avoidance, Jules review loop handling).
- Simplifications that make future development faster, safer, and less error-prone.

### 2. Issue Granularity & Decomposition Rule
**Every issue MUST be tightly scoped and atomic:**
- **Single Responsibility:** An issue must cover one specific refactoring, bug fix, or feature capability. Do NOT create monolithic catch-all issues.
- **Decomposition Mandate:** If an issue touches multiple sub-components, requires changing more than ~3-5 distinct files, or spans multiple architectural layers (e.g., FFM layout changes + API redesign + profiler integration), **split it into multiple smaller, ordered issues**.
- **Dependency Chaining:** Use the `dependencies: ["issue-YYYYMMDD-HHMM-parent-slug"]` frontmatter field to define precise execution order across decomposed issues so the orchestrator can schedule them safely in sequence.

### 0. Generate the file (do not hand-name IDs)

Do **not** invent `issue-YYYYMMDD-HHMMSS-*.md` by hand. Run the scaffold so the id is unique, the YAML passes `checkBacklog`, and influenced files are pre-filled from `--file` / `--symbol`:

```bash
./scripts/new_backlog_issue.sh \
  --title "Cap PolicyCompilationCache growth" \
  --category code_health \
  --severity MEDIUM \
  --priority high \
  --symbol PolicyCompilationCache \
  --file enforcer/src/main/kotlin/io/mazewall/PolicyCompilationCache.kt \
  --dep issue-20260823-181020
```

Then replace the `FILL:` sentences under **Context:** and **Needed:**. Do not rename the file. `--symbol` walks Kotlin sources for the type/function and its `*Test`. `--dry-run` prints markdown without writing.

Humans on a TTY are prompted (open questions, `needs_kernel`, Context/Needed). Agents must pass `--non-interactive` (or no TTY) plus flags. Optional `--clarify` runs: verify draft → weak ACP fills Context/Needed → verify again → independent strong ACP review (`ISSUE_CLARIFY_ACP='agy --acp'`, `ISSUE_CLARIFY_STRONG_ACP` for a separate reviewer). No API keys. If an ACP agent is missing or fails, the file is still written.

### 3. Naming & File Placement
Create a new markdown issue file under the appropriate category subdirectory in `docs/internals/backlog/{category}/`:
- `docs/internals/backlog/code_health/` for refactoring, architectural health, and orchestrator tooling issues
- `docs/internals/backlog/security/` for security and sandbox boundary findings
- `docs/internals/backlog/performance/` for performance optimizations and reactor loop scoping
- `docs/internals/backlog/testing/` for unit, integration, and architecture test coverage
- `docs/internals/backlog/implementation/` for feature additions and sub-project implementations

**Filename Convention:** Every issue file MUST use a timestamp-based unique identifier containing full date and time down to seconds:
`issue-YYYYMMDD-HHMMSS-short-descriptive-slug.md` (e.g. `issue-20260726-035221-decouple-nativeengine-from-raw-ffm-types.md`).
Including seconds (`HHMMSS`) prevents timestamp collision when multiple issues are created rapidly.

### 3. Structured Frontmatter & Template
Every backlog issue file MUST contain complete YAML frontmatter at the top of the file:

```markdown
---
title: "Title of Issue"
severity: "HIGH" # CRITICAL | HIGH | MEDIUM | LOW | ENHANCEMENT
status: "open" # open | in_progress | resolved | deferred
priority: high # high | medium | low (do not use 0–10)
dependencies: [] # List of dependency issue IDs
component: "enforcer" # enforcer | profiler | orchestrator | docs | ci | testing | platform
target_modules:
  - ":enforcer" # Gradle module paths where code changes will occur
target_files:
  - "enforcer/src/main/kotlin/io/mazewall/NativeEngine.kt" # Target files to edit
effort: "medium" # small | medium | large | huge
autonomy: "supervised" # autonomous | supervised
open_questions: false # Set to true if pending design/operator feedback
---

# 🔴 [Severity: HIGH]: Title of Issue

**Context:** [Detailed description of the bug, security finding, or feature requirement, explaining why it exists and what current behavior is.]
**Needed:** [Concrete, step-by-step technical requirements for the solution.]

## ❓ Open Questions
1. [Clarifying design questions, architectural options, or operator trade-offs (required when open_questions: true).]
```

### 4. Target Module & File Declarations for Multi-Task Parallel Scheduling
- Always declare **`target_modules`** (e.g. `[":enforcer"]`, `[":profiler"]`, `[":tools:orchestrator"]`).
- Declare **`target_files`** whenever specific files to be modified are known.
- The Orchestrator uses these fields to schedule non-conflicting tasks concurrently without git merge collisions or Gradle lock contention.

### 5. Safety Invariants
- **Fail Closed:** Ensure any recommended security fix follows the "Fail Closed" doctrine.
- **No Silent Bypasses:** Do not suggest "fail-safe" or "warning-only" fallbacks for security boundaries.
- **Constant Verification:** Always specify automated test requirements to verify the fix.
