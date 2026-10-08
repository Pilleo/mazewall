---
title: "Restrict Gradle resolution discovery to checkout source inventory"
severity: "HIGH"
status: "open"
priority: high
dependencies: []
component: "core"
target_modules: []
target_files:
  - "scripts/gradle_lazy_resolution.py"
target_symbols:
  - "source_inventory"
open_questions: false
---

# 🟡 [Severity: HIGH]: Restrict Gradle resolution discovery to checkout source inventory

**Context:**
Combined build run-22955f967680 fails checkGradleLazyResolution with discovery exit2 on26 unreadable .gradle/configuration-cache directories. Generated/private state must be pruned before traversal; tracked and normal untracked Gradle source must remain checked, with source permission failures fatal. This is a verification inventory contract, not a kernel permission fallback.

**Needed:**
1. Add real-file production-entrypoint red/green discovery fixtures, repair inventory without changing accepted parser, and retain failing source permissions and the98 lexical/action regressions.

## Root cause and scoped implementation

Discovery used filename inclusion globs before selecting checkout source. Those
globs interact with traversal/ignore behavior and can reach private configuration
cache directories. The scanner also omitted hidden source, ignored tracked source,
source packages named `build`, and the whole orchestrator source folder.

`source_inventory()` now explicitly prunes VCS/cache/generated-output/worktree
directories before filesystem traversal, then selects Gradle suffixes. Only
checkout-local VCS ignores apply to untracked scratch; ambient ripgrep config,
`.ignore`, global and parent ignore files cannot change source selection. A
verified read-only checkout index supplies tracked source even if ignored.
Enumeration/Git-context/source-read errors remain failures. Accepted lexical
parser and execution-action behavior are unchanged. No cache ownership/content
changes, kernel permission fallbacks, source-module exclusions or new dependencies.

## Evidence and acceptance

- Coordinator full combined build `run-22955f967680`:26 configuration-cache
  permission errors; checkGradleLazyResolution exit2. No agent cache repair.
- Actual-entrypoint real-file RED `run-666d6ee40de1`:9 tests,7 failures/subtest
  failures, including generated-directory discovery exit2 and skipped source.
- Initial GREEN `run-8c7ade0af1cf`:all9 discovery cases and all98 accepted lexical
  fixtures pass; actual assigned checkout scanner passes.
- Final baseline RED `run-867832a35566`:12 tests with10 failures/subtest failures
  against the preserved d51b017c scanner; real cache traversal reproduces exit2.
- Final GREEN `run-fc12a2afffa4`:all12 real-file discovery tests and all98 accepted
  lexical/action cases pass, with no skips. Source permission cases require actual
  nonzero scans and concrete permission/path diagnostics.
- Read-only inventory audit `run-6ba070347e4e`:33 assigned-checkout source scripts
  and36 combined-main source scripts; no generated/private sibling paths, actual
  source scans pass using the proposed helper, both Git index hashes unchanged.
  No main source/cache modification or full combined build claim.
- Accepted parser prefix byte-for-byte unchanged fromd51b017c; SHA256
  `5ebb459096e44e7e8fb723514ab2ad31dad3be10054abd4eba223aeda3b1cf85`.
- `scripts/tests/gradle_source_discovery_test.py` creates real temporary source/Git
  files and actual unreadable directories/files under an unprivileged UID. It
  checks generated-state pruning versus fatal untracked/ignored-tracked source
  permissions, normal hidden/integration/test/orchestrator sources, concrete path
  diagnostics, and strict read-only checkout identity. No mock permission result.

Keep open until independent review and coordinator rerun of the combined build.
Task12 shared checkpoint/report owns exact final commands/commit/evidence.
Backlog index is coordinator-owned; register this task-owned issue separately.

## Rereview 2 — symlink and default-index coverage

Two inventory false negatives were reproduced using the actual entrypoint:
untracked source links never entered ripgrep's file inventory, and inherited
GIT_INDEX_FILE could hide ignored tracked eager/unreadable source while Git
top-level still matched CWD. Round5 RED run-422f8bcba77d:21 filesystem methods,
10 failures/subtest failures.

Discovery now clears only Git-reported repository-local variables inside readonly
Git inventory children, leaving parent/non-Git variables unchanged. Actual default
checkout index is authoritative, including regular linked-worktree .git pointers.
No optional Git writes or credential-variable handling.

Explicit no-follow policy rejects indexed/visible source-tree links and indexed
source link ancestors without reading targets. Opaque non-script links can be
subproject directories and are conservatively rejected too. Untracked conventional
build-name pruning and local VCS scratch ignore declarations are documented bounds,
not proof that all omitted paths are generated/owned or that every layout is covered.

Expanded23 filesystem methods pass (run-1ece6800e214), including alternate-index
source visibility/permissions, index hash preservation, linked-worktree default
index, tracked/untracked/ignored/dangling/unreadable/private/external/ancestor links.
The98 parser/action cases pass unchanged (run-2c74ec5aba53).

Actual checkout now truthfully rejects an existing indexed experiment link:
`experiments/hotspot-agentless/cmd/mw-gadget-build` (run-9635f8a36b47, exit2).
No actual source links or user caches were changed/removed/ignored. Coordinator
must resolve actual-checkout coverage/acceptance explicitly; do not label this
tree as passing or add module-wide exclusions. Full round5 report retains exact
commands, final verification and fix HEAD.

---
<!-- id: issue-20261008-142748-453-restrict-gradle-resolution-discovery-to-checkout-s  file: issue-20261008-142748-453-restrict-gradle-resolution-discovery-to-checkout-s.md -->
