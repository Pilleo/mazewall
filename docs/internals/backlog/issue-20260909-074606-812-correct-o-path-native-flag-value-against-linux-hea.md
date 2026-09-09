---
title: "Correct O_PATH native flag value against Linux headers"
severity: "HIGH"
status: "open"
priority: high
dependencies: []
component: "platform"
target_modules: ["platform"]
target_files:
  - "platform/src/main/kotlin/io/mazewall/ffi/NativeConstants.kt"
target_symbols:
  - "NativeConstants"
open_questions: false
---

# 🟡 [Severity: HIGH]: Correct O_PATH native flag value against Linux headers

**Context:**
Task 8 literal native-flag test found O_PATH is 0x01000000 in NativeConstants.kt:86, but /usr/include/asm-generic/fcntl.h defines octal 010000000, hexadecimal 0x00200000. The decomposition preserves legacy native flags; correcting this cross-module constant needs its own ABI and kernel regression review.

**Needed:**

1. Add failing assertions against the Linux header value and fresh-JVM tests of O_PATH behavior, then fix the constant and audit all consumers.

---
<!-- id: issue-20260909-074606-812-correct-o-path-native-flag-value-against-linux-hea  file: issue-20260909-074606-812-correct-o-path-native-flag-value-against-linux-hea.md -->
