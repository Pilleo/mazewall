---
title: "Add a work-package CLI that emits impact JSON from Codanna/ast-grep"
severity: "MEDIUM"
status: "resolved"
priority: high
dependencies:
  - issue-20260823-181020
component: "orchestrator"
target_modules:
  - ":tools:orchestrator"
target_files:
  - "scripts/code_atlas.sh"
  - "tools/orchestrator/src/test/kotlin/io/mazewall/orchestrator/WorkPackageCliTest.kt"
effort: "medium"
autonomy: "supervised"
open_questions: false
---

# 🟡 [Severity: MEDIUM]: Add a work-package CLI that emits impact JSON from Codanna/ast-grep

**Context:**
Planning should compute a deterministic impact graph (files, symbols, callers, tests, core locks) as a **loop artifact**, not as an AST dump in the prompt. Codanna already supports `describe`, `find_callers`, `get_calls`, `analyze_impact`. `scripts/code_atlas.sh` wraps those but has no `work-package` mode. Scheduler and worker need a JSON file they can both read. This is cheap and repeatable; it is not PlantUML generation.

**Needed:**
1. Extend `scripts/code_atlas.sh` (or add `scripts/work_package.sh`) with:
   `work-package <SymbolOrFile>…` → stdout JSON:
   ```json
   {
     "target_files": [],
     "target_symbols": [],
     "callers": [],
     "verify_cheap": [],
     "core_lock_hit": false,
     "needs_kernel": false
   }
   ```
2. `core_lock_hit` is true if any file intersects a hard-coded CORE set: `Syscall.kt`, `Arch.kt`, `Policy.kt`, `Platform.kt`, `AGENTS.md`, `ArchitectureTest.kt`, `build.gradle.kts` / `settings.gradle.kts`. Keep the set in one shell/kotlin source of truth (prefer Kotlin in orchestrator if the script would duplicate; otherwise a small `tools/orchestrator/src/main/resources/core-lock-files.txt`).
3. `verify_cheap`: if Codanna callers include a `*Test` symbol, emit `./gradlew :<module>:test --tests <class>`. Module inferred from path (`enforcer/` → `:enforcer`). If unknown, omit.
4. Missing `codanna`: exit non-zero with a one-line error. Do not fabricate an empty success.
5. Tests: given a fixture file list, `core_lock_hit` is true for `platform/src/main/kotlin/io/mazewall/core/Syscall.kt` and false for a profiler-only path. Prefer a Kotlin unit test of the CORE-set function over bash-only tests.
6. Do not inject the JSON into agent prompts inside this issue. Do not regenerate class diagrams.

---

**Verification:** `./gradlew :tools:orchestrator:test` for CORE-set logic. Manual: `./scripts/code_atlas.sh work-package PolicyCompilationCache` prints JSON when Codanna is installed.
