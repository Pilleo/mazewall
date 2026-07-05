---
title: "Manual FFM Layout Maintenance and Drift Risk"
severity: "MEDIUM"
status: "open"
priority: 5
dependencies: []
component: "ffi"
effort: "large"
---

# 🟡 [Severity: LOW]: Manual FFM Layout Maintenance and Drift Risk

**Target:** `io.mazewall.ffi.Layouts` and `io.mazewall.ffi.LayoutValidator`
**Context:** Currently, FFM `MemoryLayout` definitions for system structs are maintained by hand in `Layouts.kt`. While `LayoutValidator.kt` asserts structural alignments at runtime, these should ideally be derived from system headers.
**Findings & Trade-offs:**
1.  **Linux ABI Guarantee:** The Linux kernel's "Do Not Break User Space" rule ensures that struct offsets (e.g., `seccomp_data`) remain stable across kernel versions for a given architecture. Thus, generated bindings for Linux are version-stable.
2.  **Cross-Architecture Divergence:** `jextract` produces architecture-specific layouts (e.g., x86_64 vs. AArch64). Hardcoding generated bindings from a single architecture into the JAR breaks "Write Once, Run Anywhere" if the target architectures have different padding or alignment rules for those structs.
3.  **Integration Strategies:**
    *   **Strategy A (Multi-Arch Bindings):** Generate separate packages for `x86_64` and `aarch64`, checking both into the repo and switching at runtime via `Arch.current()`. (Highest safety, but increases JAR bloat).
    *   **Strategy B (Validation Oracle):** Use `jextract` purely as a test-time oracle. CI generates bindings dynamically and reflects on them to verify that the manual `Layouts.kt` is mathematically correct against the ground-truth C headers. (Minimal JAR size, prevents human error during release, but requires manual layout updates).
**Needed:** Decide between Strategy A (full automation) and Strategy B (automated verification of manual layouts) to eliminate ABI drift risk without sacrificing multi-arch compatibility.
