---
title: "Missing `BpfProgram<Status>` and `BpfLabel` Type-Safety"
severity: "HIGH"
status: "open"
---

# 🔴 [Severity: MEDIUM]: Missing `BpfProgram<Status>` and `BpfLabel` Type-Safety

*   **Dimension:** Compile-time Safety & Type Integrity
*   **Target Area:** `enforcer/src/main/kotlin/io/mazewall/seccomp/BpfProgram.kt`
*   **Observation:** While the `BpfBuilder` uses a sealed state machine, the resulting `BpfProgram` lacks a `Status` phantom type (Verified/Unverified). Furthermore, `BpfBuilder` still uses `String` for labels, which are prone to typos and lack compile-time existence guarantees.
*   **Needed:** Transition `BpfProgram` to `BpfProgram<Status>` and introduce `BpfLabel` value class tokens for the DSL.
