---
title: "Replace spec-driven skill with a one-page work-package contract"
severity: "MEDIUM"
status: "open"
priority: high
dependencies:
  - issue-20260823-181001
component: "docs"
target_modules:
  - ":tools:orchestrator"
target_files:
  - ".agents/skills/spec_driven_development/SKILL.md"
  - ".agents/specs/templates/feature_spec_template.md"
  - ".agents/specs/templates/bugfix_spec_template.md"
  - ".agents/spec-kit/memory/constitution.md"
effort: "small"
autonomy: "supervised"
open_questions: false
---

# 🟡 [Severity: MEDIUM]: Replace spec-driven skill with a one-page work-package contract

**Context:**
`.agents/skills/spec_driven_development/SKILL.md` is 21 lines of Kiro-style SDD (create `.agents/specs/<name>/`, write design, write `tasks.md`). Nothing in the orchestrator, Paperclip, or Gradle runner refuses code without an approved spec. Agents skip it or write theater specs that nothing consumes. `.agents/spec-kit/memory/constitution.md` duplicates root `AGENTS.md` never-dos. Feature/bugfix templates ask for safepoint analysis on every change. Real design for BPF/Landlock/FFM already lives in `docs/internals/designs/`. This skill is negative leverage: extra tokens, no enforcement.

**Needed:**
1. Replace `spec_driven_development/SKILL.md` with a **work-package contract** skill (new name in frontmatter `name: work_package_contract`, keep the directory or add a stub redirect from the old name so existing prompts do not 404):
   - When to use: any feature or bugfix larger than a one-file nit.
   - Artifact: a one-page YAML/markdown contract (intent, `target_files`, `target_symbols` if known, `verify.cheap` commands, `needs_kernel: true|false`, never-dos).
   - When a new supervisor/LSM/FFM protocol is involved: update the existing design doc via `update_docs`, do not invent `design.md` under `.agents/specs/`.
   - Explicitly: do not require `requirements.md` + `design.md` + `tasks.md` for ordinary backlog items.
2. Shrink `.agents/specs/templates/feature_spec_template.md` and `bugfix_spec_template.md` to match that contract (or delete them if the skill inlines the template).
3. Reduce `.agents/spec-kit/memory/constitution.md` to a pointer at root `AGENTS.md` plus maker/checker/triager one-liners, or delete it if unused after the skill change. Do not leave a second never-do list.
4. If root `AGENTS.md` still lists `spec_driven_development` after `issue-20260823-181001`, rename the index entry. Prefer not editing `AGENTS.md` in this issue if 181001 already dropped the skill table.

---

**Verification:** No remaining instruction tells agents they MUST create `.agents/specs/<name>/` before editing code. Contract template is copy-pasteable. `checkBacklog` passes.
