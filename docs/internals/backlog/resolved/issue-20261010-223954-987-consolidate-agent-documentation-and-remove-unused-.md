---
title: "Consolidate agent documentation and remove unused workflow guidance"
severity: "MEDIUM"
status: "resolved"
priority: medium
dependencies: []
component: "core"
target_modules: []
target_files:
  - "AGENTS.md"
  - "CONTRIBUTING.md"
  - ".agents/CODE_QUALITY.md"
  - ".agents/profiles/maker.md"
  - ".agents/profiles/checker.md"
  - ".agents/profiles/triager.md"
  - ".agents/skills/spec_driven_development/SKILL.md"
  - ".agents/specs/templates/feature_spec_template.md"
  - ".agents/specs/templates/bugfix_spec_template.md"
  - ".agents/spec-kit/memory/constitution.md"
  - ".agents/spec-kit/specs/core_spec.md"
  - ".agents/skills/loop_driven_development/SKILL.md"
  - ".agents/skills/review/SKILL.md"
  - ".agents/skills/file_structure/SKILL.md"
  - ".claude/skills/file_structure/SKILL.md"
  - ".codex/skills/file_structure/SKILL.md"
  - "docs/internals/designs/core/testing-strategy.md"
  - "docs/internals/backlog/README.md"
target_symbols: []
open_questions: false
---

# 🟡 [Severity: MEDIUM]: Consolidate agent documentation and remove unused workflow guidance

**Context:**
Use the existing Gradle build as the quality gate, remove unused profiles and SDD instructions, and make type safety, purity, and minimum visibility explicit. Documentation changes are authorized on the current branch; stop before Gradle logic changes.

**Needed:**
1. Update active documentation and remove unused workflow documents; validate references and commit only task-owned documentation.

**Boundary:**
The operator additionally confirmed that `.agents/profiles` is unused and should be removed. Executable helper scripts, Gradle logic, regression fixtures, and the current kernel-test cache policy belong to the next implementation phase, which requires stopping for operator approval. Existing live verification locks and historical evidence are not unused workflow instructions.

**Resolution:**
- Removed all three unused profiles, the SDD skill, its two templates, and the duplicate spec-kit constitution/core specification.
- Made the existing build the documented formatting, lint, test, and coverage completion gate; equivalent iteration commands are alternatives.
- Strengthened sealed/enum/exhaustiveness/domain-type/purity/visibility requirements in the canonical quality guide and review skill.
- Aligned optional outlining across the canonical skill and both harness copies; reviews are read-only unless follow-up is authorized.
- Preserved the existing kernel-test cache policy in current-behavior documentation pending the separately authorized Gradle change.

**Verification:**
- Active-guidance obsolete-reference checks, local link checks, skill metadata checks, and byte-equality checks for outline-skill copies passed.
- `git diff --check` passed.
- `./scripts/adkw check-backlog` passed both before the edits and with the completed issue and updated index.
- Documentation-only scope: no executable source, helper scripts, or Gradle configuration changed.

---
<!-- id: issue-20261010-223954-987-consolidate-agent-documentation-and-remove-unused-  file: issue-20261010-223954-987-consolidate-agent-documentation-and-remove-unused-.md -->
