---
title: "Orchestrator: Build GitHub Issue Body Dynamically with Project Preamble"
severity: "HIGH"
status: "open"
priority: 10
dependencies: []
component: "orchestrator"
effort: "small"
reversible: true
autonomy: "supervised"
solution_approved: false
blast_radius: "medium"
reversible: true
---

# 🔴 [Severity: HIGH]: Orchestrator: Build GitHub Issue Body Dynamically with Project Preamble

**Context:**
`PENDING_APPROVAL` currently calls `env.createIssue(issueTitleForGit, File(context.currentIssueFile!!), "jules")`, passing the raw backlog markdown file directly as the GitHub issue body via `--body-file`. Jules therefore receives the YAML frontmatter (which is orchestrator metadata, not instructions), and body sections that may use non-canonical headers (`**Target Area:**`, `**Failure Hypothesis:**`) that `BacklogParser.extractSection` silently drops. Jules has no knowledge that this is a JVM security library, no hard rules about what not to do (`EPERM`, `Thread.sleep()`, `JAVA_LONG`), no reference to `AGENTS.md` or skills, and no definition of done.

**Needed:**
1. Add a `buildIssueBody(issue: BacklogIssue): String` function to `OrchestratorEnvironment` (or a new `IssueBodyBuilder` object).
2. The function must produce a two-part body:
   - **Part A — Static project preamble** (always injected): project description, hard rules list (EPERM/EACCES, JVM syscalls, JAVA_LONG, Thread.sleep, MemoryLayout, MockNativeEngine, FallbackBehavior.FAIL), reference documents (AGENTS.md, enforcer/AGENTS.md, .agents/CODE_QUALITY.md, docs/internals/containment_design.md), and definition of done (gradlew build, run_tests.sh, Jacoco thresholds, failing-test-first for bug fixes).
   - **Part B — Dynamic task content**: extracted from the parsed `BacklogIssue` — `issue.id`, `issue.title`, `issue.severity`, `issue.component`, `issue.effort`, `issue.context`, `issue.needed`, and optionally `implementationHints`, `acceptanceCriteria`, and `chosenSolution` if present.
3. Write the constructed body to a temp file in `build/tmp/` and pass that temp file to `gh issue create --body-file`.
4. The raw backlog file must never be passed directly as the issue body again.

## Solution Options

### Option A — Refactor implementation
Implement the recommendation described in the Needed section to resolve the issue directly. Target area: `Unknown`
**Pros:** Resolves the root cause of the issue.
**Cons:** Requires careful implementation and testing.
**Risk:** MEDIUM
**Effort:** small

---
**Chosen:** *(not yet approved — requires human decision)*

**Acceptance Criteria:**
- [ ] `./gradlew :tools:orchestrator:test` passes with a new unit test `IssueBodyBuilderTest` that asserts: (a) preamble always present, (b) YAML frontmatter stripped from output, (c) `context` and `needed` sections present, (d) `implementationHints` only included when non-null.
- [ ] Manual smoke: create a test issue and verify GitHub issue body contains the preamble header and the task context.

**Implementation Hints:**
- The preamble text is static — put it in a `companion object` constant so it can be tested without instantiating the full environment.
- Use `File.createTempFile("issue_body_", ".md", File("build/tmp"))` consistent with how `commentOnPr` and `commentOnIssue` already work.
- Reuse `BacklogParser.extractSection` for the body sections — do not re-parse from the raw file.