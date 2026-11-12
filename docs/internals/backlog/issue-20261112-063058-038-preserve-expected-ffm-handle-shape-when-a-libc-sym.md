---
title: "Preserve expected FFM handle shape when a libc symbol is missing"
severity: "MEDIUM"
status: "open"
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

---
<!-- id: issue-20261112-063058-038-preserve-expected-ffm-handle-shape-when-a-libc-sym  file: issue-20261112-063058-038-preserve-expected-ffm-handle-shape-when-a-libc-sym.md -->
