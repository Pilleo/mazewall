---
title: BPF Static Verifier fails to catch backward jumps that overflow
type: issue
status: open
priority: 3
labels:
- security
- enforcer
- bpf
- static-verifier
component: enforcer
target_modules:
- :enforcer
target_files:
- enforcer/src/main/kotlin/io/mazewall/seccomp/BpfStaticVerifier.kt
---

# Issue: BPF Static Verifier Potential Overflow

## Context
The `BpfStaticVerifier` ensures that all jump paths end in `Ret`.

## The Bug
BPF jumps in classic seccomp filters (`BPF_JMP`) only jump forwards. If `BpfStaticVerifier` does not explicitly reject negative jump offsets, a corrupted or maliciously constructed `BillOfBehavior` JSON that somehow circumvents the builder could load a filter with a negative jump offset. The Linux kernel will reject it, but the `BpfStaticVerifier` is supposed to enforce mathematically verified filters at the Kotlin level.

## Recommendation
Add an explicit check to `BpfStaticVerifier.verify` that guarantees `jt` and `jf` offsets are strictly positive or zero, and that `current_pc + offset + 1 < program_length`.
