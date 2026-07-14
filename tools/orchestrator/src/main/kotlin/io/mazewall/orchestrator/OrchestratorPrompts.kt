package io.mazewall.orchestrator

object OrchestratorPrompts {
    const val QUALITY_AND_SAFETY_GUIDELINES = """
## 🛡️ Quality and Safety Guidelines

Adhere strictly to the following project invariants:

1. **Absolute Certainty**: If you are not 100% sure about a kernel behavior, JVM internal mechanism, or system call side-effect, you **must** say so rather than guessing or making assumptions.
2. **Zero Silent Bypasses**: Never swallow `EPERM` or `EACCES` exceptions or downgrade sandboxing failures to warnings. All security violations must be treated as fatal.
3. **JVM Coordination Invariants**: Never block system calls critical for JVM operations (parking, GC, safepoints).
4. **FFM Safety**: Ensure correct layout alignments, arena lifecycles, and off-heap memory safety. Use `JAVA_LONG` correctly and avoid its misuse on 32-bit fields.
5. **Loom Carrier Protection**: Prevent virtual thread carrier thread poisoning. Never apply seccomp filters that restrict the underlying OS carrier thread in a way that affects other virtual threads.
"""

    fun taskPrompt(originalIssueBody: String): String {
        return """
$originalIssueBody

---

$QUALITY_AND_SAFETY_GUIDELINES
""".trimIndent()
    }

    fun reviewPrompt(prNumber: String, shaPrefix: String, pushWarning: String): String {
        return buildString {
            append("⛔ READ-ONLY TASK — DO NOT COMMIT, PUSH, OR EDIT ANY FILES ⛔")
            append(pushWarning)
            append("""


@jules You are acting as a **code reviewer**, not an implementer, on PR #$prNumber (SHA: $shaPrefix).

Your ONLY deliverable is a **single comment on this PR** containing your review.
Do NOT open the workspace editor. Do NOT stage, commit, or push any changes.
Do NOT create new files. Just write a comment.

---

Review the diff as a senior JVM security expert and staff engineer for `mazewall`
— a kernel-enforced JVM sandboxing library using Linux Seccomp-BPF and Landlock LSM
via the JDK Foreign Function & Memory (FFM) API.

$QUALITY_AND_SAFETY_GUIDELINES

Structure your comment as follows:

**1. Overview** — What does this PR do and what problem does it solve?

**2. Rationale** — Why was this specific implementation approach chosen?

**3. Security & Correctness Analysis**
   - JVM sandboxing bypasses (Seccomp/Landlock filter gaps)
   - FFM layout correctness: alignment, padding, `JAVA_LONG` misuse on 32-bit fields
   - Memory lifecycle: arena scope, off-heap leak risk
   - Concurrency: JVM thread coordination, Loom carrier thread safety (carrier poisoning)
   - Silent error swallowing: any `catch` that ignores `EPERM`/`EACCES`/`EINTR`

**4. Missed Edge Cases** — What scenarios are not covered by this PR?

**5. Alternatives** — What other approaches exist and why is this one better or worse?

**6. Verdict** — End your comment with EXACTLY one of these lines (on its own line):
`VERDICT: APPROVED`
`VERDICT: NEEDS_CHANGES`
`VERDICT: UNCERTAIN`

---
⛔ Reminder: post your review as a **comment only**. Do NOT edit files or push commits. ⛔""")
        }
    }
}
