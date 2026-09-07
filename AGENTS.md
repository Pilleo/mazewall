# mazewall agent guide

mazewall is a Linux JVM sandboxing library built on Seccomp-BPF, Landlock, and the JDK Foreign Function & Memory API. It targets JDK 25 idioms and supports JDK 22 or later.

## Security invariants

- Fail closed. Never swallow, downgrade, or silently bypass `EPERM` or `EACCES`; fallback behavior is opt-in only.
- Never block JVM coordination syscalls. The complete list and argument constraints are in [enforcer/AGENTS.md](enforcer/AGENTS.md).
- Never install seccomp from a virtual thread; it permanently contaminates its carrier. See [enforcer/AGENTS.md](enforcer/AGENTS.md).
- Never combine `SECCOMP_FILTER_FLAG_TSYNC` with `SECCOMP_FILTER_FLAG_NEW_LISTENER`.
- Never use `JAVA_LONG` for a 32-bit C `int` or `sock_filter` field.
- Never modify, filter, or handle `GITHUB_TOKEN`; credential management is the operator's responsibility.
- Do not make breaking API changes, add dependencies, or change the core BPF linear-scan design without explicit approval.
- Thread-scoped containment is not a complete ACE boundary. Preserve the process-wide Tier 1 baseline and document exact limitations.

## Module boundaries

Read the nearest module instructions before changing code:

- [platform/AGENTS.md](platform/AGENTS.md): FFM layouts, syscall metadata, native-engine isolation.
- [enforcer/AGENTS.md](enforcer/AGENTS.md): containment, JVM floor, Loom, Landlock, and native safety.
- [profiler/AGENTS.md](profiler/AGENTS.md): USER_NOTIF, ptrace/Yama, and profiler protocol.
- [portal/AGENTS.md](portal/AGENTS.md), [portal-codegen/AGENTS.md](portal-codegen/AGENTS.md), and [portal-worker/AGENTS.md](portal-worker/AGENTS.md): broker/worker process isolation.
- [tools/orchestrator/AGENTS.md](tools/orchestrator/AGENTS.md): orchestration and backlog tooling.

Skills live in `.agents/skills/` and are loaded by their trigger; do not inline their procedures here. Follow `.agents/CODE_QUALITY.md` for design and code-quality standards.

## Native and lifecycle discipline

- Keep FFM layouts and downcalls behind `NativeEngine`/`io.mazewall.ffi`; use confined arenas and capture `errno` immediately after a native call.
- Landlock setup precedes Seccomp installation. Preserve that order.
- A file descriptor must be owned before it is closed. Do not mint a closeable token around an invented integer.
- Do not turn kernel, permission, or containment failures into warnings unless the operator explicitly chose that fallback.

## Documentation and worktree safety

- Read the relevant design document before changing behavior, threat model, native layout, or protocol semantics.
- Keep design documentation and public guidance accurate when behavior changes; archive a backlog item only with evidence.
- Treat existing uncommitted work as user-owned. Stage only files belonging to the current change.
- Before conflict resolution or branch updates, inspect `git status` and preserve unrelated work with a recoverable stash when needed.

## Single control plane

- Do not push directly to the default branch. Work enters through a scoped backlog item and the orchestrator-managed review path.
- Preserve serial PR merge handling and reject a task diff that escapes its declared scope.
- Do not let Paperclip or another agent mutate the same worktree as an active orchestrator worker; assignment is an explicit operator action.
- Operators use the orchestrator CLI/Telegram approval flow for starts and merges. See `tools/orchestrator/README.md` for commands.

## Verification

Use focused host tests while iterating:

```bash
./gradlew :<module>:compileKotlin
./gradlew :<module>:test --tests <TestClass>
./gradlew :<module>:test
```

Run kernel verification only when the work installs or changes Seccomp, Landlock, or USER_NOTIF behavior:

```bash
./gradlew integrationTest
./gradlew integrationTestFreshJvm
./scripts/run_tests.sh
```

Run the merge gate once after focused checks pass:

```bash
./gradlew build
```

## Code intelligence and backlog

- Prefer Codanna for symbols and callers, `./scripts/code_atlas.sh blast-radius <Symbol>` before changing public or cross-module symbols, and `./scripts/sg.sh` for structural search.
- Use a file outline first for unknown or large files; it is recommended, not a pre-read gate for short or already-understood files.
- Record discovered bugs, security gaps, and kernel nuances as a backlog issue using `./scripts/adkw new-issue`; keep `docs/internals/backlog/README.md` in sync.
- Before a merge or rebase, inspect `git status` and preserve unrelated work. Never use destructive resets or checkouts to discard user changes.

## Useful commands

```bash
./scripts/adkw check-backlog
./scripts/lint.sh
./scripts/check_coverage.sh
```

Post-edit hooks run `./scripts/adkw hook` (Kotlin syntax). Delivery uses `./scripts/adkw guard <file> --stage compile|test|delivery`.


## 🛠️ Agent DevKit (ADK) Universal Tooling & Hard Boundaries

- Outline with `./scripts/adkw slice <file-or-class> [--json]` (or the file_structure skill) before a full source-file view.
- Before modifying a core symbol, run `./scripts/adkw doctor` then `./scripts/adkw blast-radius <SymbolName>`.
- After edits, hooks run `adk hook` (syntax). Delivery uses `./scripts/adkw guard <file> --stage compile|test|delivery`.
- Scaffold issues with `./scripts/adkw new-issue --title "<title>"` and run `./scripts/adkw check-backlog` before completion.
