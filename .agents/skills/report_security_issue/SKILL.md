# Skill: Report Security Issue

This skill provides a standardized protocol for documenting a newly discovered security bypass, architectural gap, or kernel-level nuance in `mazewall`.

## Protocol

### 1. Classification
Determine the severity based on the following impact criteria:
- **CRITICAL:** Remote execution bypass, trivial sandbox escape without ACE.
- **HIGH:** Local privilege escalation within the sandbox, bypass of core whitelists (e.g. `NO_EXEC`).
- **MEDIUM:** Information leak, bypass requiring complex prerequisites, significant usability flaw.
- **LOW:** Documentation drift, minor performance degradation, nitpicks.

### 2. Documentation Phase (Debuggability & Precision)
Create a new issue file under the appropriate category subdirectory in **`docs/internals/backlog/{category}/issue-XXX-name.md`** (e.g., `security/`) and register it in the **Open Issues** table of [backlog/README.md](file:///home/leanid/Documents/code/java/jseccomp/docs/internals/backlog/README.md) using the following structured template:

```markdown
---
title: "Title of Issue"
severity: "HIGH/MEDIUM/LOW/CRITICAL/ENHANCEMENT"
status: "open"
---

# 🔴 [Severity: Severity]: Title of Issue
**Context:** [Detailed description of the vulnerability, including why it exists and why current checks fail.]
**Failure Hypothesis:** [The precise memory state, kernel state, or JVM state that causes the failure.]
**Needed:** [Concrete technical recommendation for the fix, ensuring it improves debuggability and trace-friendliness.]
```

### 3. Safety Invariants
- **Fail Closed:** Ensure the recommended fix follows the "Fail Closed" doctrine.
- **No Silent Bypasses:** Do not suggest "fail-safe" or "warning-only" fixes for security boundaries.
- **Verification:** Always recommend a specific test case that can verify the fix.

### 4. Cross-Reference
If the issue affects both `:enforcer` and `:profiler`, ensure the entry covers both modules and explains the synchronization requirement.
