---
title: "Deduplicate PendingSpawnRegistry TTL logic and use monotonic clock"
severity: "LOW"
status: "open"
priority: low
dependencies: []
component: "enforcer"
target_modules:
  - ":enforcer"
target_files:
  - "enforcer/src/main/kotlin/io/mazewall/enforcer/supervisor/PendingSpawnRegistry.kt"
target_symbols:
  - "PendingSpawnRegistry"
needs_kernel: false
core_lock: false
effort: "medium"
autonomy: "supervised"
open_questions: false
paperclip_issue_id: bf0a54fe-9885-41de-8dc1-80097ff5b88f
---

# 🟢 [Severity: LOW]: Deduplicate PendingSpawnRegistry TTL logic and use monotonic clock

**Context:**
`PendingSpawnRegistry` implements one TTL rule in two places with a duplicated magic number: eviction sweep `> 10000` in `register()` (`PendingSpawnRegistry.kt:31`) and expiry check `> 10000` in `get()` (`:39`). The timestamp uses `System.currentTimeMillis()` (wall clock), which jumps under NTP adjustments or container clock skew — a backwards jump extends the authorization window beyond 10s, a forwards jump expires entries early (fail-closed direction, but still wrong). Because this registry authorizes stack traces for spawn supervision, the TTL semantics should be stated once, in code, not inferred from two scattered comparisons.

**Needed:**
1. Extract a single `private const val AUTHORIZATION_TTL_NANOS` (or a small `PendingSpawnEntry(stackTrace, expiresAtNanos)` value class with `fun isExpired(nowNanos: Long): Boolean`).
2. Switch elapsed measurement to `System.nanoTime()` for both registration-time stamping and expiry checks (monotonic); keep wall-clock out of the security decision.
3. Keep the eager sweep in `register()` as an optimization only — correctness must come from the single `isExpired` check consulted by `get()`/`remove()`.
4. Add unit tests: expired entry returns null and is removed; entry within TTL is returned; no test depends on real sleep timing (inject a clock/nanos supplier).

---

**Verification:** `./gradlew :tools:orchestrator:checkBacklog` plus the `verify_cheap` commands above (if any).

<!-- id: issue-20260826-102705  file: issue-20260826-102705-deduplicate-pendingspawnregistry-ttl-logic-and-use-monotonic.md -->
<!-- Agent: fill Context and Needed; add files/symbols if the impact walk missed them. Do not rename the file. -->
