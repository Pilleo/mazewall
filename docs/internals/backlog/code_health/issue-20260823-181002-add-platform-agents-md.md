---
title: "Add platform/AGENTS.md for FFM layouts, Syscall.kt, and NativeEngine isolation"
severity: "MEDIUM"
status: "open"
priority: high
dependencies: []
component: "platform"
target_modules:
  - ":platform"
target_files:
  - "platform/AGENTS.md"
effort: "small"
autonomy: "autonomous"
open_questions: false
---

# 🟡 [Severity: MEDIUM]: Add platform/AGENTS.md for FFM layouts, Syscall.kt, and NativeEngine isolation

**Context:**
2026 monorepo practice is nested `AGENTS.md` (nearest file wins). mazewall already has nested files for `:enforcer`, `:profiler`, and `:tools:orchestrator`. `:platform` holds `Syscall.kt`, `Arch.kt`, FFM `Layouts.kt`, `NativeEngine`, and value-class wrappers — the hottest shared lock set — and has **no** nested instruction file. Agents editing platform therefore only see the bloated root file and miss module-specific layout/ABI rules unless they happen to open `enforcer/AGENTS.md`. Paperclip/Grok/Codex will load `platform/AGENTS.md` when the active path is under `platform/`.

**Needed:**
1. Create `platform/AGENTS.md` (~40–80 lines, imperative, non-inferable only):
   - Never use `JAVA_LONG` for 32-bit `sock_filter` / C `int` fields; exact `ValueLayout` mapping lives in `Layouts.kt` and must stay aligned with x86_64/aarch64 ABI.
   - `Syscall.kt` and `Arch.kt` are global locks: adding a syscall requires both arches plus tests; do not edit as a drive-by.
   - Raw FFM/`MemorySegment` stays behind `NativeEngine` / `io.mazewall.ffi`; ArchUnit isolation must remain.
   - Arena: `Arena.ofConfined().use { }`; capture `errno` immediately; no sharing confined segments across threads.
   - Pointer to `docs/internals/designs/enforcer/containment-design.md` and `.agents/skills/ffm_safety/SKILL.md`.
   - Exact test commands: `./gradlew :platform:test`, `:platform:check`.
2. Do not duplicate the full JVM floor syscall list (that belongs in `enforcer/AGENTS.md`). One sentence: “Do not block JVM coordination syscalls; see enforcer/AGENTS.md.”
3. If root `AGENTS.md` already has a nested-file index (see `issue-20260823-181001`), do not edit root here unless the index omits `platform/` — then add a single bullet only.

---

**Verification:** File exists; `:platform:test` still passes (docs-only). Spot-check that the file does not restate README or SOLID.
