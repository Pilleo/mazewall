---
title: "Rename report_security_issue Skill to create_backlog_issue and Expand Schema Protocol"
severity: "MEDIUM"
status: "resolved"
priority: 9
dependencies: []
component: "orchestrator"
target_modules: [":tools:orchestrator"]
target_files:
  - ".agents/skills/report_security_issue/SKILL.md"
  - ".agents/skills/create_backlog_issue/SKILL.md"
  - "AGENTS.md"
effort: "small"
autonomy: "autonomous"
---

# 🔴 [Severity: MEDIUM]: Rename `report_security_issue` Skill to `create_backlog_issue` and Expand Schema Protocol

**Context:**
Currently, `.agents/skills/report_security_issue/SKILL.md` is used for creating backlog issue files across all domains (features, bugs, testing, performance, orchestrator health), making the name `report_security_issue` misleadingly narrow. Furthermore, the skill template lacks explicit target file/module declaration fields needed for Orchestrator multi-task parallel scheduling.

**Needed:**
1. Rename skill folder `.agents/skills/report_security_issue` to `.agents/skills/create_backlog_issue`.
2. Update `SKILL.md` frontmatter template and protocol instructions to include `target_modules`, `target_files`, and timestamp-based issue ID guidelines (`issue-YYYYMMDD-HHMM-slug`).
3. Update `AGENTS.md` and repository references pointing to the old skill name.
