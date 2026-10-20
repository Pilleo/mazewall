# Contributing to mazewall

Thank you for your interest in contributing to **mazewall**.

mazewall sits at the intersection of security, Linux kernel internals, and JVM runtime mechanics. It is a security-critical library that interfaces directly with the Linux kernel and JVM threads. Errors here lead to silent security bypasses or permanent JVM deadlocks — not just bugs.

As a contributor, keep the north star in mind: give developers easy-to-use tools to restrict code execution as much as possible, with automated SBoB generation and surgical sandboxing for the most dangerous parts of their code.

---

## Before You Start

**Read these first — they contain non-negotiable safety constraints:**

| Document | Why it matters |
|---|---|
| [AGENTS.md](AGENTS.md) | Hard boundaries for AI and human contributors: what never to block, what never to bypass |
| [enforcer/AGENTS.md](enforcer/AGENTS.md) | JVM coordination syscalls, Loom carrier thread rules, FFM errno safety |
| [profiler/AGENTS.md](profiler/AGENTS.md) | USER_NOTIF ACK loop deadlock prevention, ptrace scope, strace parsing |
| [docs/internals/designs/core/architectural-map.md](docs/internals/designs/core/architectural-map.md) | Component diagram, the ACK loop sequence, cross-module dependencies |
| [docs/internals/designs/core/security-considerations.md](docs/internals/designs/core/security-considerations.md) | Full threat model — what mazewall stops and what it doesn't |

---

## Architecture in One Paragraph

mazewall has two production modules: **`:enforcer`** (zero-dependency production runtime — BPF compilation, filter installation, Landlock, `ContainedExecutors`) and **`:profiler`** (dev/test tool — `USER_NOTIF` daemon, iterative Landlock profiler, `BillOfBehavior` compiler). The demo subprojects in `demos/` consume both. The most critical architectural constraint is the profiler's **ACK loop**: the daemon must always ACK every intercepted syscall or the worker thread deadlocks permanently. See `designs/core/architectural-map.md` for the full sequence diagram.

---

## Guidelines

1. **Security first:** Never implement silent fallback or bypass behavior. The default is fail-closed. See [AGENTS.md §2](AGENTS.md).
2. **TDD:** Reproduce bugs with a failing test before fixing. New features get tests before implementation.
3. **Log issues:** Any kernel behavior discovery, JVM quirk, or security nuance you find goes in the [`docs/internals/backlog/`](docs/internals/backlog/README.md) directory.
4. **Discuss major changes:** Open a GitHub issue before starting large architectural or API changes.
5. **Code quality:** Follow [the code-quality standards](.agents/CODE_QUALITY.md): sealed states/outcomes, enums for finite choices, exhaustive branches, strict domain types, pure decisions, and minimum visibility. Formatting, lint/static analysis, tests, and configured coverage thresholds are part of `./gradlew build`.

---

## Development Setup

You need a **Linux host or container with kernel ≥ 6.2** for integration tests. Host-safe unit tests do not require containment privileges, but the build still requires its native toolchain: Linux headers, a C compiler, clang with a BPF backend, and LLVM tools. See [the testing strategy](docs/internals/designs/core/testing-strategy.md) for prerequisites.

### Option A: Run directly on a Linux host

```bash
# Focused iteration: host-safe unit tests and unit-only coverage
./gradlew unitCheck

# Focused kernel iteration on a suitable host
./gradlew kernelCheck

# Alternative containerized test runner
./scripts/run_tests.sh

# Completion gate: formatting, lint, tests, and configured coverage thresholds
./gradlew build
```

The full build already selects kernel and fresh-JVM suites for included modules; equivalent iteration commands need not be repeated after that gate. For orchestrator work, use `./gradlew build -PincludeOrchestrator=true`. Documentation-only changes use reference/link checks, `git diff --check`, and backlog validation.

### Commit verification

The version-controlled `scripts/hooks/pre-commit` runs `./gradlew build` on every
commit, retaining the explicit portal checks when that module is included. It does
not exclude `check`: formatting, static analysis, tests and configured coverage
failures abort the commit. Full output is retained in
`/tmp/mazewall-precommit-test.log`. The hook preserves staged security audits and
isolates Git-local repository variables only for the Gradle subprocess.

`build` already includes `check`. Explicitly requesting `build check` does not add
verification tasks, so the hook selects the full build once.

Use the configured host/kernel test environment for commits, just as for the full
build above. `core.hooksPath=scripts/hooks` selects the tracked hook in this
checkout. The hook regression can be run independently:

```bash
bash scripts/tests/git_hook_environment_test.sh
```

It exercises real disposable Git commits with a controlled Gradle boundary,
including full-build arguments, failed-verification rejection, staged/alternate
index audits and foreign-repository isolation.

### Optional architecture report

RefactorFirst is intentionally not part of `build`, `check`, `unitCheck`, or `kernelCheck`.
Generate its informational report explicitly when planning architectural work:

```bash
./gradlew refactorFirstReport
```

The task analyzes committed production sources from `platform`, `enforcer`, `profiler`,
`portal`, `portal-codegen`, and `portal-worker`. It rejects uncommitted changes in those
source roots, builds an isolated sparse snapshot so linked worktrees and unrelated modules
cannot leak into the analysis, and writes
`build/reports/refactorFirst/refactor-first-report.html`. Use `--rerun-tasks` to regenerate
the report for an unchanged commit.

### Option B: Isolated Podman environment (recommended)

```bash
podman compose -f infra/dev/compose.yml up -d
podman compose -f infra/dev/compose.yml exec mazewall ./gradlew check
```

The Podman environment provides the correct kernel capabilities and nested seccomp profile needed for integration tests.

### Module-Level Checks

```bash
./gradlew :enforcer:unitCheck  # host-safe coverage thresholds
# Host-only mutation thresholds: serial modules, two PIT workers, one ordinary test fork
./gradlew :enforcer:pitest :platform:pitest :profiler:pitest \
  --max-workers=1 --no-parallel \
  -Pmazewall.pitest.threads=2 -Pmazewall.test.maxParallelForks=1
./gradlew :enforcer:kernelCheck # combined native-contract thresholds
./gradlew :profiler:unitCheck
```

PIT defaults to two workers in every module. `-Pmazewall.pitest.threads` takes
precedence over the compatible `-Dpitest.threads`; the selected value must be a positive integer.

---

## Responsible Disclosure

If you discover a security vulnerability, **do not open a public issue**. Follow standard responsible disclosure — contact the maintainer directly before publishing.
