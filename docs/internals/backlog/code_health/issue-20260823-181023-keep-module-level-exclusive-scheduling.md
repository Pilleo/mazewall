---
title: "Keep module-level exclusive scheduling; CORE file locks only, no intra-module parallel"
severity: "HIGH"
status: "open"
priority: high
dependencies: []
component: "orchestrator"
target_modules:
  - ":tools:orchestrator"
target_files:
  - "tools/orchestrator/src/main/kotlin/io/mazewall/orchestrator/DependencyGraph.kt"
  - "tools/orchestrator/src/main/kotlin/io/mazewall/orchestrator/OrchestratorDaemon.kt"
  - "tools/orchestrator/src/test/kotlin/io/mazewall/orchestrator/ParallelTaskSchedulerTest.kt"
effort: "medium"
autonomy: "supervised"
open_questions: false
---

# 🔴 [Severity: HIGH]: Keep module-level exclusive scheduling; CORE file locks only, no intra-module parallel

**Context:**
`selectAndStartTasks` already refuses two slots that share a `target_modules` entry. That is the correct default for mazewall: agents leak files, Gradle compiles the whole module, `ArchitectureTest` is shared, and Paperclip agents may share a checkout until `issue-20260823-181013`. Open issue `issue-20260726-191002` asks for (1) CORE file exclusive locks and (2) **subsystem package intersection** so two `:enforcer` tasks in different packages can run together. Package-level concurrency is too optimistic for this repo. Implementing 191002 as written would loosen safety. CORE file locks are still needed *in addition to* module locks (two tasks in different modules that both touch `AGENTS.md` or `Syscall.kt`).

**Needed:**
1. Implement `SHARED_CORE_FILES` exclusive scheduling: if any active slot’s declared or actual files intersect CORE (`platform/.../Syscall.kt`, `Arch.kt`, `Policy.kt`, `Platform.kt`, any `AGENTS.md`, `ArchitectureTest.kt`, root/module `build.gradle.kts`, `settings.gradle.kts`), do not start another slot that also intersects CORE. This is 191002 points 1–2, not point 3.
2. **Do not** add package-directory intersection that allows two `:enforcer` (or two `:profiler` / `:platform`) slots at once. Add a regression test: issue A `target_modules: [":enforcer"]` file `PolicyCompilationCache.kt`, issue B `target_modules: [":enforcer"]` file `supervisor/Foo.kt` → B must **not** start while A is active.
3. Keep current behavior: empty `target_files` or empty `target_modules` is a global lock unless `isNonInterfering()` (docs/ci).
4. Tests in `ParallelTaskSchedulerTest` (extend, do not replace). If `issue-20260726-191002` is implemented later, it must not land point 3 without explicitly reversing this test.
5. Comment in `DependencyGraph.kt` pointing at this issue so the next agent does not “optimize” module locks.

---

**Verification:** `./gradlew :tools:orchestrator:test --tests io.mazewall.orchestrator.ParallelTaskSchedulerTest`. Two `:enforcer` fixtures never co-start.
