---
title: "Review Task: Profiler Module & Security Audit"
severity: "HIGH"
status: "resolved"
priority: 10
component: "profiler"
target_modules: [":profiler", ":enforcer"]
target_files: []
effort: "medium"
dependencies: []
github_issue: 394
---
Please review profiler module using .agents/skills/review/SKILL.md skill. Create issues using skill .agents/skills/create_backlog_issue/SKILL.md

**Additional Focus Instructions:**
focus on the enforcer module only please

---


## 🛡️ Quality and Safety Guidelines

Adhere strictly to the following project invariants:

1. **Absolute Certainty**: If you are not 100% sure about a kernel behavior, JVM internal mechanism, or system call side-effect, you **must** say so rather than guessing or making assumptions.
2. **Zero Silent Bypasses**: Never swallow `EPERM` or `EACCES` exceptions or downgrade sandboxing failures to warnings. All security violations must be treated as fatal.
3. **JVM Coordination Invariants**: Never block system calls critical for JVM operations (parking, GC, safepoints).
4. **FFM Safety**: Ensure correct layout alignments, arena lifecycles, and off-heap memory safety. Use `JAVA_LONG` correctly and avoid its misuse on 32-bit fields.
5. **Loom Carrier Protection**: Prevent virtual thread carrier thread poisoning. Never apply seccomp filters that restrict the underlying OS carrier thread in a way that affects other virtual threads.
6. **Pull latest master**: You are working in a team of other agents, so master is updated all the time. Before submitting your work always pull in latest origin master, and push only after that.

