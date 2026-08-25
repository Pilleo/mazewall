---
title: "PolicyCompilationCache Grows Without Bound for Dynamic Policies"
severity: "HIGH"
status: "resolved"
priority: high
component: "enforcer"
target_modules:
  - ":enforcer"
target_files:
  - "enforcer/src/main/kotlin/io/mazewall/PolicyCompilationCache.kt"
  - "enforcer/src/test/kotlin/io/mazewall"
effort: "small"
autonomy: "autonomous"
open_questions: false
dependencies: []
---

# 🔴 [Severity: HIGH]: PolicyCompilationCache Grows Without Bound for Dynamic Policies

**Context:** `PolicyCompilationCache` memoizes compiled+verified BPF programs
(`CompiledSandbox(definition, List<BpfInstruction>)`) keyed by `CacheKey(definition, arch)` with
full `PolicyDefinition` data-class equality — which includes `allowedFsReadPaths` and
`allowedFsWritePaths`. However, the compiled program depends ONLY on `syscallActions`,
`defaultAction`, and the arg-inspection flags; Landlock filesystem paths never influence BPF
content. Therefore every distinct path-set produces a new cache entry holding a byte-identical
program:

- `IterativeProfiler.profile()` adds ≥1 entry per discovery iteration (path set grows each round).
- Any long-running application building per-tenant or per-request FS policies accumulates entries
  forever — `clear()` exists but is test-only. Unbounded heap growth in exactly the
  always-on deployments this library targets.
- Secondary cost: cache lookups hash/compare entire definitions including path sets and regexes.

**Resolution (2026-08-23):** Key replaced with the program-relevant projection (`defaultAction`, `syscallActions`, arg-inspection flags + arch); FS paths removed from keying so path-only variants share one entry. Cache is now a bounded (256-entry) access-order LRU — safe because the sole caller already holds `processLock`; compilation happens outside the lock. Regression tests (`PolicyCompilationCacheTest`) use instance-identity assertions (immune to concurrent foreign inserts): same-definition reuse, path-only sharing, distinct-action separation, size cap.

**Needed:**
1. Key the cache on the program-relevant projection: `defaultAction`, `syscallActions`,
   `allowMmapExec`, `allowNonThreadClone`, `allowUnsafePrctl`, `lockIntelCet` (+ arch). Two
   definitions differing only in FS paths then share one entry — correct, since the compiled
   program is byte-identical.
2. Belt-and-braces: bound entry count (e.g. simple LRU with a documented cap) so even exotic
   dynamic syscall-action churn cannot grow without limit.
3. Add a unit test asserting: compiling N policies differing only in FS paths yields exactly ONE
   cache entry and identical `compiledFilters`; and that distinct syscall actions still compile
   distinctly.
4. Document the projection invariant next to the key type ("FS paths must NOT participate in this
   key; they do not affect BPF output") to prevent regression.

