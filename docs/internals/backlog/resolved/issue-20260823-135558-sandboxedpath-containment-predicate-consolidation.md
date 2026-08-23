---
title: "Consolidate Path Containment Logic Behind a SandboxedPath Predicate"
severity: "HIGH"
status: "resolved"
priority: high
component: "enforcer"
target_modules:
  - ":enforcer"
target_files:
  - "platform/src/main/kotlin/io/mazewall/core/SandboxedPath.kt"
  - "enforcer/src/main/kotlin/io/mazewall/enforcer/api/ContainedExecutors.kt"
  - "enforcer/src/main/kotlin/io/mazewall/enforcer/supervisor/BypassPaths.kt"
  - "enforcer/src/main/kotlin/io/mazewall/enforcer/supervisor/SupervisorInstaller.kt"
effort: "medium"
autonomy: "supervised"
open_questions: true
dependencies: []
---

# 🔴 [Severity: HIGH]: Consolidate Path Containment Logic Behind a SandboxedPath Predicate

**Context:** `SandboxedPath` (platform/src/main/kotlin/io/mazewall/core/SandboxedPath.kt) is documented as
purely syntactic and exposes no containment predicate. As a result, "is path P inside allowed set S" is
reimplemented with *divergent semantics* in at least three places:

- `ContainedExecutors.isPathSubset` (ContainedExecutors.kt:407-418): syntactic
  `Paths.get(...).startsWith(parent)` on raw values only.
- `BypassPaths` (BypassPaths.kt:204): compares `path.startsWith(bypass) || path == bypass ||
  realPath.startsWith(bypass) || realPath == bypass` — i.e. resolves realpath before comparing.
- `SupervisorInstaller` path resolution (SupervisorInstaller.kt:307): ad-hoc `/proc/self/fd/`,
  `/proc/thread-self/fd/`, and absolute-prefix handling.

Divergent containment semantics mean the Landlock subset check (`isPathSubset`) can approve a nested
policy on syntactic containment while the kernel enforces on dentry/realpath identity (symlinks,
bind mounts) — a potential restriction-expansion false-negative. Conversely, bypass matching over-matches
relative to policy evaluation.

**Resolution note (2026-08-23):** Resolved per operator decision (no back-compat constraints).
Canonical containment now lives beside `SandboxedPath` in `:platform`: `isUnder(Path, Path)`,
`SandboxedPath.isUnder(parent)`, `isUnderAny`, `Set.coveredBy/coversAll`, plus `resolveReal()`
(deepest-existing-ancestor realpath resolution; falls back to syntactic value when unresolvable).
Migrated: `ContainedExecutors.isPathSubset` now compares realpath-resolved sets on both sides —
Landlock binds rules to dentries, so a symlinked spelling of an allowed directory compares equal
while any real expansion is still rejected (fail closed). `BypassPaths.isBypassPath` routes its
raw+realpath comparison through the canonical predicate. `SupervisorInstaller.canonicalizeExecPath`
intentionally keeps string checks: they operate on `/proc` pseudo-paths and PATH search, not policy
containment. Tests: `SandboxedPathContainmentTest` (platform), `LandlockSubsetRealpathTest`
(enforcer, incl. symlink-equality acceptance + expansion rejection). During this work the
BpfNativeCache engine-poisoning bug was discovered and fixed (issue-20260823-180500).

**Needed:**
1. Add an explicit containment API to `SandboxedPath`: `infix fun isUnder(parent: SandboxedPath): Boolean`
   plus `Set<SandboxedPath>.containsPath(p)` / `coversAll(children)` helpers, implemented once.
2. Decide and document the canonical semantics (syntactic vs realpath-resolved) per call site; where the
   kernel compares dentries, the Java-side check must resolve symlinks equivalently or conservatively
   reject (fail closed).
3. Migrate `isPathSubset`, `BypassPaths`, and `SupervisorInstaller` prefix checks onto the shared API.
4. Add unit tests covering symlinked parents, trailing slashes, `..` normalization, and relative-path
   rejection for both the subset check and bypass matching.

## ❓ Open Questions
1. Should `isUnder` resolve realpaths via `Files.toRealPath()` at policy-build time (TOCTOU remains between
   build and install — acceptable?), or stay syntactic and require operators to pre-normalize?
2. Does moving this into `:platform`'s core module change any public-API surface constraints
   (issue-20260808-032525)?
