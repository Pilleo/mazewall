---
title: "Slim root AGENTS.md to non-inferable commands, never-dos, and nested index"
severity: "MEDIUM"
status: "open"
priority: high
dependencies: []
component: "docs"
target_modules:
  - ":tools:orchestrator"
target_files:
  - "AGENTS.md"
  - ".agents/skills/file_structure/SKILL.md"
effort: "medium"
autonomy: "supervised"
open_questions: false
paperclip_issue_id: 8b969de4-34bb-41ad-a02d-1a6ad49a1f02
---

# 🟡 [Severity: MEDIUM]: Slim root AGENTS.md to non-inferable commands, never-dos, and nested index

**Context:**
Root `AGENTS.md` is 225 lines and is injected on every agent turn (Grok, Jules, Codex, Paperclip adapters). 2026 AGENTS.md practice (agents.md spec, Codex nested files, Anthropic CLAUDE.md ceiling) is: always-on layer ~50–150 lines of facts the model cannot infer; nested `AGENTS.md` per module; skills and design docs on demand. The current root file fails that test: welcome/philosophy, skill catalog table, “outline before every `view_file`” as a hard never-do, conflict-resolution novel, and stacked verification (`test` after every step plus OCI plus `./gradlew build`). Nested `enforcer/AGENTS.md` and `profiler/AGENTS.md` already hold the kernel invariants. The outline ritual costs a tool round-trip per file and is routinely skipped, which trains agents to treat all MUSTs as optional — including fail-closed rules. Issue `issue-20260726-205804` also edits `AGENTS.md` (single-control-plane section); this issue must preserve those invariants if already present, or leave a clearly marked stub if not.

**Needed:**
1. Rewrite root `AGENTS.md` to roughly 80–100 lines containing only: one-sentence project identity; JDK 22/25 + Gradle; exact copy-paste commands (`./gradlew test`, `integrationTest`, `integrationTestFreshJvm`, `:module:test --tests`, `./gradlew build` as merge gate only); fail-closed / never-do security list (EPERM, JVM floor syscalls, TSYNC+NEW_LISTENER, JAVA_LONG, GITHUB_TOKEN); pointer to nested files (`enforcer/`, `profiler/`, `platform/`, `portal/`, `tools/orchestrator/`); one line that skills live in `.agents/skills/` and are loaded by trigger, not inlined; cheap-inner-loop vs merge-barrier verification.
2. Remove the hard never-do “never call view_file without Codanna/`file_structure.main.kts`”. Keep Codanna/ast-grep as recommended search tools in a short “Code intelligence” bullet, not a pre-read law.
3. Update `.agents/skills/file_structure/SKILL.md`: outline is recommended for files ≳400 lines or unknown modules; skip when the file was already outlined this turn or is short.
4. Do not paste `.agents/CODE_QUALITY.md` or design-doc bodies. Link them.
5. Do not weaken fail-closed, JVM floor, or Loom-carrier rules. Those stay in root (short) and in nested module files (full).
6. Optional in the same PR: add `CLAUDE.md` with a single `@AGENTS.md` import so Claude Code (Paperclip Summarizer) does not drift.
7. Verify with `./gradlew :tools:orchestrator:checkBacklog`. Manually confirm a representative agent prompt still sees the never-do list.

---

**Verification:** Root file line count and the never-do list are present; `file_structure` skill no longer claims MANDATORY for every read; `checkBacklog` passes.
