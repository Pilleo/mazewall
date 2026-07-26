---
title: BpfBuilder generates overflowed jump offsets leading to application DoS
type: issue
status: open
priority: high
labels: ["security", "enforcer", "bpf", "dos", "overflow"]
component: enforcer
target_modules: [":enforcer"]
target_files: ["io.mazewall.seccomp.BpfBuilder.kt", "io.mazewall.seccomp.BpfProgram.kt"]
---

# Issue: BpfBuilder Jump Offset Overflow

## Context
Seccomp-BPF programs have a strict structural limitation: jump offsets (`jt` and `jf`) are represented as 8-bit unsigned integers, restricting relative forward jumps to a maximum of 255 instructions.

## The Bug
The `BpfBuilder` compiler restricts relative jump offsets (`jt`/`jf`) to 8 bits. If a policy is compiled that generates larger forward jumps (e.g., deeply nested conditions or a very large number of blocked/allowed system calls evaluated sequentially without BST optimization), the jump offset will exceed 255. Currently, `BpfProgram.kt` (which implements the builder logic) checks `require(offset <= MAX_BPF_JUMP_OFFSET)` where `MAX_BPF_JUMP_OFFSET = 255`. If this offset is exceeded, it throws an `IllegalArgumentException` at runtime during the `.build()` phase. While this prevents invalid filters from reaching the kernel, it causes a complete JVM application DoS if the policy is dynamically generated or supplied by a user.

## Recommendation
Implement a jump-rewriting or trampoline-generation mechanism within `BpfBuilder` / `BpfProgram` compilation. If a jump exceeds 255 instructions, automatically insert intermediate jump trampolines or refactor the emitted bytecode layout so that no single jump exceeds the 8-bit limit, thus safely supporting arbitrarily large policies without crashing the JVM.
