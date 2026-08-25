---
title: "Document reversible JVM tracking vs irreversible kernel vs daemon effects"
severity: "LOW"
status: "resolved"
priority: low
dependencies: []
component: "docs"
target_modules:
  - ":enforcer"
target_files:
  - "docs/internals/designs/enforcer/containment-design.md"
effort: "small"
autonomy: "autonomous"
---

# 🟢 [Severity: LOW]: Document reversible JVM tracking vs irreversible kernel vs daemon effects

**Context:** A side-effect analysis treated kernel mutation as a smell to isolate or roll back. In mazewall, seccomp and Landlock mutation is the product. Agents and juniors need a one-page table in the internal design doc. Presentation docs stay free of BPF jargon; this change is only `containment-design.md`.

**Needed:**
1. Outline the file first: `kotlin scripts/file_structure.main.kts docs/internals/designs/enforcer/containment-design.md`.
2. Add a short section with this table (wording may be tightened, columns must stay):

| Effect | Reversible? | JVM tracking |
|---|---|---|
| Seccomp filter | No (thread/process lifetime) | `ContainerState.filterDepth` / actions — never clear after apply |
| Landlock domain | No (nest inward only) | `landlockPolicy` — never rewind after apply |
| Supervisor session / USER_NOTIF listener | Session closeable; filter stays | Closing session does **not** uninstall; later NOTIFY may get ENOSYS |
| `InstallationReceipt` | Diagnostic only | `installed` is independent of `landlockApplied` |
| `sanitizeThreadState` | Forbidden | Throws |

3. Link WONTFIX issue-102 / issue-103 and `issue-20260821-113003-report-already-active-landlock`.
4. No production code.

**Do not:**
- Add a `pure/` vs `impure/` directory split.
- Describe kernel install as atomic or transactional.

**Verify:** `./gradlew :tools:orchestrator:checkBacklog` (markdown only; no `:enforcer:test` required).
