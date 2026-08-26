---
title: "Cap SandboxDispatcher poolCache growth and evict idle pools"
severity: "HIGH"
status: "open"
priority: high
dependencies: []
component: "enforcer"
target_modules:
  - ":enforcer"
target_files:
  - "enforcer/src/main/kotlin/io/mazewall/enforcer/api/SandboxDispatcher.kt"
  - "enforcer/src/main/kotlin/io/mazewall/enforcer/SandboxDispatcher.kt"
target_symbols:
  - "SandboxDispatcher"
needs_kernel: false
core_lock: false
effort: "medium"
autonomy: "supervised"
open_questions: false
---

# 🔴 [Severity: HIGH]: Cap SandboxDispatcher poolCache growth and evict idle pools

**Context:**
`SandboxDispatcher.poolCache` (`enforcer/api/SandboxDispatcher.kt:29`) is an unbounded `ConcurrentHashMap<PolicyDefinition<*>, ExecutorService>` keyed by the *full* definition. Callers that build policies dynamically (e.g. per-request FS paths via `allowFsRead(...)`) create one cached-thread-pool entry per distinct definition; entries are never evicted until the process-wide `shutdownAll()`. Each entry holds daemon worker threads whose seccomp filters are permanent (thread-local containment cannot be undone), so leaked pools also leak contained OS threads. This contradicts the KDoc promise ("prevents thread-explosion") and repeats the exact bug class already fixed for BPF compilation by issue-20260823-171953 (cache key must be the program-relevant projection of a policy, not the whole definition). Note `PolicyDefinition.equals` includes Landlock paths which do not influence which *threads* must exist — only the syscall projection matters for pooling.

**Needed:**
1. Key the cache on the program-relevant projection (default action, syscall actions, arg-inspection flags, arch) — mirror the `PolicyCompilationCache.CacheKey` approach and reference that invariant in KDoc.
2. Bound the cache (small fixed cap, e.g. 32 entries) with LRU-style eviction that calls `shutdown()` on evicted executors before dropping them.
3. Document explicitly in KDoc that executor threads are permanently contained and therefore pooled forever by design; eviction only happens under cap pressure or `shutdownAll()`.
4. Add a unit test: installing N > cap distinct dynamic policies results in at most `cap` live executors (assert via internal accessor), and evicted pools report `isShutdown`.
5. Run `./gradlew :enforcer:test`.

---

**Verification:** `./gradlew :tools:orchestrator:checkBacklog` plus the `verify_cheap` commands above (if any).

<!-- id: issue-20260826-102609  file: issue-20260826-102609-cap-sandboxdispatcher-poolcache-growth-and-evict-idle-pools.md -->
<!-- Agent: fill Context and Needed; add files/symbols if the impact walk missed them. Do not rename the file. -->
