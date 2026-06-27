# Skill: Spec-Driven Development (Kiro-Style)

This skill directs agents to execute Kiro-style **Spec-Driven Development** (SDD) within the `mazewall` codebase.

---

## 🛠️ SDD Execution Instructions

### 1. Spec Initiation
*   Before modifying any code, the agent MUST initialize a new spec folder under `.agents/specs/<spec-name>/`.
*   Use the templates located in `.agents/specs/templates/`:
    - Use `feature_spec_template.md` for new capabilities.
    - Use `bugfix_spec_template.md` for bugs.

### 2. Design Anchoring
*   Always perform a full architectural and safety review in `design.md` or the spec document before editing source files.
*   Explicitly analyze JVM Safepoints, thread coordination system calls, and Loom carrier impacts in your design plans.

### 3. Task Management
*   Break down implementation steps into a `tasks.md` checklist inside the spec folder.
*   Perform work incrementally, keeping changes focused on one task at a time.
