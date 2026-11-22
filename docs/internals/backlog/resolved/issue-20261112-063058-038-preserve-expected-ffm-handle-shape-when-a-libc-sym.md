---
title: "Preserve expected FFM handle shape when a libc symbol is missing"
severity: "MEDIUM"
status: "resolved"
priority: high
dependencies: []
component: "core"
target_modules: []
target_files:
  - "platform/src/main/kotlin/io/mazewall/ffi/internal/RealNativeEngine.kt"
target_symbols:
  - "RealNativeHelper"
open_questions: false
---

# 🟡 [Severity: MEDIUM]: Preserve expected FFM handle shape when a libc symbol is missing

**Context:**
The missing-symbol branch constructs an int-returning throwing handle from descriptor argument layouts only, while errno-capturing downcalls add a capture-state argument and some callers require long returns. A missing symbol can therefore produce WrongMethodTypeException instead of the intended unsupported-symbol failure; this source finding is separate from issue 051 and has not been reproduced as its cause.

**Needed:**
1. Add focused missing-symbol tests for actual expected handle type, int and long returns, captured errno state and throwable identity, then preserve fail-closed behavior without changing native capability or timeout policy.

## Resolution Evidence (2026-11-21)
- Updated `RealNativeHelper.downcall` in `platform/src/main/kotlin/io/mazewall/ffi/internal/RealNativeEngine.kt` to build missing-symbol failing MethodHandles matching `linker.downcallHandle(fd, *options).type().dropParameterTypes(0, 1)` across return types, parameters, `captureCallState`, and variadic options.
- Added focused regressions in `platform/src/test/kotlin/io/mazewall/ffi/RealNativeHelperDowncallTest.kt` and verified via `:platform:test` (`546` tests, `0` failed, `0` skipped) and `./gradlew unitCheck`.
- Full completion gate verified in commits `52e81d2e1` and `7e62bacf7` (`./gradlew build -PmazewallKernelLane=BASELINE` and rootful Docker `CONFINED` kernel lane passed).

---
<!-- id: issue-20261112-063058-038-preserve-expected-ffm-handle-shape-when-a-libc-sym  file: issue-20261112-063058-038-preserve-expected-ffm-handle-shape-when-a-libc-sym.md -->
