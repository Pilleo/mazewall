---
title: "Fix Jules Session ID Parsing Bug"
severity: "HIGH"
status: "open"
priority: 10
dependencies: []
component: "orchestrator"
effort: "small"
---

# 🔴 [Severity: HIGH]: Fix Jules Session ID Parsing Bug

**Context:**
The `listSessions()` method in `tools/orchestrator/src/main/kotlin/io/mazewall/orchestrator/JulesCli.kt` splits the CLI output by multiple spaces and checks if the first token is a valid Long (`id.toLongOrNull() != null`). Jules session IDs (e.g., `14927969181089226847`) exceed the maximum value of a 64-bit signed integer (`Long.MAX_VALUE`), causing `toLongOrNull()` to evaluate to `null` and drop the session from the list.

**Needed:**
Replace the Long conversion check in `JulesCli.kt` with a string-based digit check or use `ULong`/`BigInteger`:
```kotlin
// Change this:
if (id.toLongOrNull() != null) { ... }

// To this:
if (id.all { it.isDigit() }) { ... }
```
