---
title: "Wire Codanna into SymbolLocator as an optional accelerant"
severity: "LOW"
status: "open"
priority: medium
dependencies: []
component: "orchestrator"
target_modules:
  - ":tools:orchestrator"
target_files:
  - "tools/orchestrator/src/main/kotlin/io/mazewall/orchestrator/FilesystemSymbolLocator.kt"
  - "tools/orchestrator/src/main/kotlin/io/mazewall/orchestrator/AstImpactScanner.kt"
needs_kernel: false
core_lock: false
effort: "medium"
autonomy: "supervised"
open_questions: false
paperclip_issue_id: e6f421c7-f298-49ab-a87a-32dad25621e7
paperclip_identifier: MAZ-692
---

# 🟢 [Severity: LOW]: Wire Codanna into SymbolLocator as an optional accelerant

**Context:**
`FilesystemSymbolLocator` currently traverses directory trees and executes regex searches across `.kt` files to map symbols to filenames and test suites. While simple, it lacks cross-file symbol indexing and call-graph awareness. The repository already includes `codanna` (`scripts/code_atlas.sh`), which maintains a fast SQLite symbol graph. Wiring Codanna into `SymbolLocator` as an optional backend will allow instant (<10ms) symbol declaration lookups and direct caller resolution without filesystem recursion.

**Needed:**
1. In `tools/orchestrator/src/main/kotlin/io/mazewall/orchestrator/FilesystemSymbolLocator.kt`:
   - Create a `CodannaSymbolLocator` that checks for `codanna` on `PATH` (or `.codanna` index).
   - If available, query `codanna retrieve describe <Symbol>` / symbols API to locate declaring files and matching tests.
   - Fall back transparently to `FilesystemSymbolLocator` if Codanna is not installed or indexed.
2. In `tools/orchestrator/src/main/kotlin/io/mazewall/orchestrator/AstImpactScanner.kt`:
   - Use `codanna retrieve callers <Symbol>` to discover external call sites across modules.
3. Add unit tests for `CodannaSymbolLocator` with mock process outputs.
4. Run `./gradlew :tools:orchestrator:test -PincludeOrchestrator=true`.

---

**Verification:** `./gradlew :tools:orchestrator:checkBacklog` plus the `verify_cheap` commands above (if any).

<!-- id: issue-20260827-110130  file: issue-20260827-110130-wire-codanna-into-symbollocator-as-an-optional-accelerant.md -->
<!-- Agent: fill Context and Needed; add files/symbols if the impact walk missed them. Do not rename the file. -->
