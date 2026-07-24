---
title: "Missing BPF Instruction Limit Validation in `newSockFProg`"
severity: "HIGH"
status: "resolved"
priority: 5
dependencies: []
component: "enforcer"
effort: "medium"
---

# 🔴 [Severity: MEDIUM]: Missing BPF Instruction Limit Validation in `newSockFProg`

*   **Dimension:** Performance & Efficiency / Macro-Architecture
*   **Target Area:** `enforcer/src/main/kotlin/io/mazewall/ffi/memory/SockFProg.kt` (or wherever `newSockFProg` is implemented)
*   **Failure Hypothesis:** The kernel strictly limits BPF programs to 4096 instructions (`BPF_MAXINSNS`). If `PolicyDefinition.compile()` generates a filter exceeding this limit, `newSockFProg` might allocate it successfully, but the `seccomp` syscall will fail mysteriously with `EINVAL`.
*   **Context & Proof:** While there is a test checking this, there might not be explicit runtime validation before attempting the syscall. A complex policy with hundreds of file paths or network addresses could exceed the limit.
*   **Cascading Risk Potential:** Medium. `EINVAL` from seccomp is notoriously hard to debug.
*   **Recommendation:** Add an explicit check in `buildFilter` or `newSockFProg` to throw a descriptive `IllegalArgumentException` if the instruction count exceeds `BPF_MAXINSNS` (4096).

---

## 🔧 Resolution Verification

This issue has been carefully verified and is confirmed to be **fully resolved** in the current codebase:
1. In `BpfFilter.buildFromActions` (`BpfFilter.kt`), the instruction count is explicitly checked against `NativeConstants.BPF_MAXINSNS` (4096):
   ```kotlin
   val instructions = builder.ret(defaultNativeAction).build().instructions
   require(instructions.size <= NativeConstants.BPF_MAXINSNS) {
       "BPF program exceeds kernel maximum instruction limit"
   }
   return instructions
   ```
2. In `RealNativeEngine.newSockFProg` (`RealNativeEngine.kt`), we validate:
   ```kotlin
   require(filters.size <= NativeConstants.BPF_MAXINSNS) {
       "BPF program exceeds kernel maximum instruction limit of ${NativeConstants.BPF_MAXINSNS} instructions"
   }
   ```
3. In `MockNativeMemory.newSockFProg` (`MockNativeEngine.kt`), we validate:
   ```kotlin
   require(filters.size <= NativeConstants.BPF_MAXINSNS) {
       "BPF program exceeds kernel maximum instruction limit"
   }
   ```
4. Regression unit tests are actively running and passing in `BpfLimitTest.kt`:
   - `BpfFilter buildFromActions throws exception when instructions exceed limit`
   - `MockNativeMemory newSockFProg throws exception when instructions exceed limit`
   - `newSockFProg throws exception when instructions exceed limit`
   - `newSockFProg accepts instructions at exactly the limit`
