---
title: "Memory Alignment verification for `Layouts.kt` FFM Structures"
severity: "HIGH"
status: "open"
priority: 9
dependencies: []
component: "enforcer"
effort: "small"
---

# 🔴 [Severity: LOW]: Memory Alignment verification for `Layouts.kt` FFM Structures

*   **Target Area:** `enforcer/src/main/kotlin/io/mazewall/ffi/Layouts.kt`
*   **Hypothesis:** `Layouts.kt` manually specifies C struct memory layouts using `java.lang.foreign.MemoryLayout`. Does it perfectly match the Linux C ABI on x86_64?
*   **Context & Proof:** We wrote a C program and a Java program to verify `sizeof` and `offsetof` for `msghdr`, `cmsghdr`, `seccomp_data`, `seccomp_notif`, `seccomp_notif_resp`, and `seccomp_notif_addfd`. The sizes and offsets in Java exactly matched the sizes and offsets in C. No issues found in standard FFM layout alignment for x86_64.
*   **Recommendation:** Continue to verify cross-compilation/aarch64 alignments if applicable, but x86_64 layouts are verified correct.
