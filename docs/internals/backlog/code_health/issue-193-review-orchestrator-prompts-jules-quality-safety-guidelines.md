---
title: "Review and Enhance Orchestrator Prompts for Jules to Enforce Quality and Safety Guidelines"
severity: "HIGH"
status: "open"
priority: 10
dependencies: []
component: "orchestrator"
effort: "small"
---

# 🔴 [Severity: HIGH]: Review and Enhance Orchestrator Prompts for Jules to Enforce Quality and Safety Guidelines

**Context:**
The Autonomous Backlog Orchestrator interacts with Jules (the remote coding agent) by creating GitHub Issues and prompting it for Pull Request reviews. Currently, these prompts do not systematically convey key quality, safety, and architectural boundaries of the `mazewall` project. When Jules receives an issue description or a code review request, it relies purely on general JVM and security knowledge rather than project-specific invariants (e.g., FFM safety, Loom virtual thread limits, silent bypass restrictions).

**Needed:**
We need to refactor and expand the orchestrator's prompt generation to:
1. **Embed Quality and Safety Guidelines in the Task Prompt:**
   When creating or commenting on a task/issue for Jules, the prompt must include a standardized set of guidelines. It should instruct the agent to adhere to:
   * **Absolute Certainty:** Explicitly specify that if the agent is not 100% sure about a kernel behavior, JVM internal mechanism, or system call side-effect, it must say so rather than guessing or making assumptions.
   * **Zero Silent Bypasses:** Never swallow `EPERM` or `EACCES` exceptions or downgrade sandboxing failures to warnings.
   * **JVM Coordination Invariants:** Never block system calls critical for JVM operations (parking, GC, safepoints).
   * **FFM Safety:** Ensure correct layout alignments, arena lifecycles, and off-heap memory safety.
   * **Loom Carrier Protection:** Prevent virtual thread carrier thread poisoning.
2. **Standardize the Review Prompt:**
   Similarly, the PR review prompt generated in `OrchestratorDaemon.kt` should enforce these constraints. The reviewer agent must verify that the PR adheres to all these restrictions and explicitly state if there are areas of uncertainty.

### Target Areas:
* **Task Prompting:** Modify how the issue/task body is constructed or passed to Jules. Instead of passing only the raw issue markdown file, append/inject a structured "Guidelines and Safety Boundaries" section to the issue description during creation, or post a systemic comment right after creation to configure the session context.
* **Review Prompting:** Update the inline `prompt` string in `OrchestratorDaemon.kt` (lines 327–343) to align with these detailed criteria, emphasizing honesty regarding uncertainty ("if not sure, say so").
