---
name: create_backlog_issue
description: Standardized protocol for documenting newly discovered backlog issues, bugs, features, architectural gaps, or security/performance/testing findings in mazewall.
---

# Skill: Create Backlog Issue

This skill provides a standardized protocol for documenting a newly discovered backlog issue, bug, feature request, architectural gap, or kernel-level nuance in `mazewall`.

## Protocol

### 1. Classification
Determine the severity based on the following impact criteria:
- **CRITICAL:** Remote execution bypass, trivial sandbox escape without ACE, severe memory corruption.
- **HIGH:** Local privilege escalation within sandbox, core whitelist bypass, architectural boundary break.
- **MEDIUM:** Information leak, usability flaw, performance regression, multi-threading race condition.
- **LOW:** Documentation drift, minor nitpicks, non-critical DX friction.

### 2. Naming & File Placement
Create a new markdown issue file under the appropriate category subdirectory in `docs/internals/backlog/{category}/`:
- `docs/internals/backlog/code_health/` for refactoring, architectural health, and orchestrator tooling issues
- `docs/internals/backlog/security/` for security and sandbox boundary findings
- `docs/internals/backlog/performance/` for performance optimizations and reactor loop scoping
- `docs/internals/backlog/testing/` for unit, integration, and architecture test coverage
- `docs/internals/backlog/implementation/` for feature additions and sub-project implementations

**Filename Convention:** Use timestamp-based unique identifiers:
`issue-YYYYMMDD-HHMM-short-descriptive-slug.md` (e.g. `issue-20260726-1845-decouple-nativeengine-from-raw-ffm-types.md`).

### 3. Structured Frontmatter & Template
Every backlog issue file MUST contain complete YAML frontmatter at the top of the file:

```markdown
---
title: "Title of Issue"
severity: "HIGH" # CRITICAL | HIGH | MEDIUM | LOW | ENHANCEMENT
status: "open" # open | in_progress | resolved | deferred
priority: 9 # Integer (1-10)
dependencies: [] # List of dependency issue IDs
component: "enforcer" # enforcer | profiler | orchestrator | docs | ci
target_modules:
  - ":enforcer" # Gradle module paths where code changes will occur
target_files:
  - "enforcer/src/main/kotlin/io/mazewall/NativeEngine.kt" # Target files to edit
effort: "medium" # small | medium | large | huge
autonomy: "supervised" # autonomous | supervised
---

# 🔴 [Severity: HIGH]: Title of Issue

**Context:** [Detailed description of the bug, security finding, or feature requirement, explaining why it exists and what current behavior is.]
**Needed:** [Concrete, step-by-step technical requirements for the solution.]
```

### 4. Target Module & File Declarations for Multi-Task Parallel Scheduling
- Always declare **`target_modules`** (e.g. `[":enforcer"]`, `[":profiler"]`, `[":tools:orchestrator"]`).
- Declare **`target_files`** whenever specific files to be modified are known.
- The Orchestrator uses these fields to schedule non-conflicting tasks concurrently without git merge collisions or Gradle lock contention.

### 5. Safety Invariants
- **Fail Closed:** Ensure any recommended security fix follows the "Fail Closed" doctrine.
- **No Silent Bypasses:** Do not suggest "fail-safe" or "warning-only" fallbacks for security boundaries.
- **Constant Verification:** Always specify automated test requirements to verify the fix.
