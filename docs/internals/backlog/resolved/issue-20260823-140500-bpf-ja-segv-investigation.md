---
title: "BPF_JA Misencoded: Classic BPF JA Jumps by K, Not jt (SIGSEGV Root Cause)"
severity: "HIGH"
status: "resolved"
priority: high
component: "enforcer"
target_modules:
  - ":enforcer"
target_files:
  - "enforcer/src/main/kotlin/io/mazewall/seccomp/BpfProgram.kt"
  - "enforcer/src/test/kotlin/io/mazewall/seccomp/BpfFilterTest.kt"
effort: "small"
autonomy: "autonomous"
open_questions: false
dependencies: []
---

# 🔴 [Severity: HIGH]: BPF_JA Misencoded — Classic BPF JA Jumps by K, Not jt (SIGSEGV Root Cause)

**Context:** While implementing `BpfBuilder.jumpUnconditional` (issue-20260823-135559), emitting a
genuine classic-BPF unconditional jump as `{code=0x05, jt=offset, jf=0, k=0}` caused the entire
`:enforcer:integrationTest` executor to die with exit code 139 (SIGSEGV), reproducibly and also in a
plain C binary outside the JVM.

**Root cause (empirically proven):** Classic BPF `BPF_JMP|BPF_JA` takes its skip count from the
**32-bit `k` immediate**, not the `jt` byte. The original emission used eBPF-style semantics
(offset in `jt`, `k=0`), i.e. "jump 0" → fall through into the *next* RET block. In real filters the
instruction after a JA is typically a `RET ERRNO`, so every syscall evaluated past that point was
denied, poisoning the whole process (JVM died messily; C harness segfaulted). Evidence matrix from a
C harness replaying our exact 73-instruction filter via `prctl(PR_SET_SECCOMP)` on Linux x86_64:

| F70 encoding | Result |
|---|---|
| `{0x05, jt=1, k=0}` | SIGSEGV (fall-through into wrong RET) |
| `{0x05, jt=0, k=0}` | SIGSEGV |
| `{0x05, jt=0, k=1}` | ✅ correct: non-listed syscall reaches `RET ALLOW` |
| `{0x15, jt=1, jf=1, k=0}` (old idiom) | ✅ correct |

The kernel accepted all variants at install time (`seccomp_check_filter` passes because `k=0` is an
in-bounds target under k-semantics) — the corruption only manifests at runtime, which is why no
EINVAL ever surfaced.

**Needed (all done):**
1. Emit JA with the resolved label offset in **k**, `jt=jf=0` (`compileJaJump` in `BpfProgram.kt`).
2. Fix the test-oracle simulator `evalBPF` to advance `pc += k + 1` for opcode `0x05`.
3. Add structural regression test `JA instructions encode skip count in k with zero jt and jf`.
4. Verified: full `:enforcer:integrationTest --rerun-tasks` passes with true JA emission; unit suite green.

## ❓ Open Questions
1. None.
