---
name: create_backlog_issue
description: >
  Create a mazewall backlog issue with a valid timestamp id and planned impact.
  Use when documenting a bug, feature, architectural gap, security finding, or
  kernel/FFM nuance. Trigger on: new issue, backlog, file a bug, log a finding,
  create_backlog_issue, new_backlog_issue, write an issue, discovered a bug.
---

# Skill: Create Backlog Issue

**Do not hand-name `issue-YYYYMMDD-HHMMSS-*.md`.** Run the scaffold script so the ID is unique, the YAML passes `checkBacklog`, and influenced files/symbols are properly mapped:

```bash
./scripts/new_backlog_issue.sh \
  --non-interactive \
  --title "Cap PolicyCompilationCache growth" \
  --category code_health \
  --severity MEDIUM \
  --priority high \
  --symbol PolicyCompilationCache \
  --file enforcer/src/main/kotlin/io/mazewall/PolicyCompilationCache.kt \
  --decompose \
  --json
```

---

## Protocol & Guidelines

### 1. Classification & Priority Assignment

**Severity Criteria:**
- **CRITICAL:** Remote execution bypass, trivial sandbox escape without ACE, severe memory corruption.
- **HIGH:** Local privilege escalation within sandbox, core whitelist bypass, architectural boundary break.
- **MEDIUM:** Information leak, usability flaw, performance regression, multi-threading race condition.
- **LOW:** Documentation drift, minor nitpicks, non-critical DX friction.

**Priority Assignment (`high` | `medium` | `low`):**
Assign **`high`** to changes that multiply developer velocity, safety, and autonomy:
- Refactorings that introduce type safety (e.g. Type-State pattern, value classes, nominal brands).
- Pre-implementation refactorings that simplify complex legacy code before functional feature changes.
- Improvements to testing harness, ArchUnit rules, and automated test coverage.
- Enhancements to CI/CD pipelines, build barriers, and validation rules.

---

### 2. Task Granularity & Autonomous Splitting Gate
**Every issue MUST be tightly scoped and atomic:**
- **Single Responsibility:** An issue must cover one specific refactoring, bug fix, or feature capability. Do NOT create monolithic catch-all issues.
- **Decomposition Mandate via Codanna:** If an issue touches multiple sub-components, requires changing more than ~3-5 distinct files, or spans multiple architectural layers (e.g. FFM layout changes + API redesign + profiler integration), **run the automated work package decomposer**:
  ```bash
  ./scripts/decompose_work_package.py <Symbol1> [Symbol2] ...
  ```
  This analyzes the exact call graph and blast radius across `:platform`, `:enforcer`, `:portal`, and `:profiler`, producing an ordered, multi-stage DAG.
- **Dependency Chaining:** Use the `dependencies: ["MAZ-XXX"]` frontmatter field to define precise execution order across decomposed issues so the DAG scheduler executes them safely in sequence.

---

### 3. Pre-Implementation Refactoring Gate ("Make the Change Easy First")
Before implementing a feature on existing code:
- **Inspect Legacy Complexity:** If the existing code around target files is tangled, complex, or lacking test seams:
  - *"Make the change easy (warning: this may be hard), then make the easy change."*
  - **Spin off a Preparatory Refactoring Sub-Task** (e.g. `issue-*-refactor-*.md`) to extract clean interfaces, decouple state, or simplify logic FIRST.
  - Declare the functional task as dependent on the refactoring PR.
