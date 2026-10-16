---
title: "Quarantine failed installations before cached-policy reuse"
severity: "HIGH"
status: "open"
priority: high
dependencies: []
component: "core"
target_modules: ["enforcer"]
target_files: 
  - "enforcer/src/main/kotlin/io/mazewall/enforcer/api/ContainedExecutors.kt"
  - "enforcer/src/main/kotlin/io/mazewall/enforcer/state"
  - "enforcer/src/test"
  - "enforcer/src/integrationTest"
  - "enforcer/README.md"
  - "docs/internals/designs/api/enforcer-public-api.md"
  - "docs/internals/designs/enforcer/containment-design.md"
target_symbols: 
  - "ContainedExecutors"
open_questions: false
---

# 🟡 [Severity: HIGH]: Quarantine failed installations before cached-policy reuse

**Context:**
C3/C7: a filter can publish irreversible state and then fail verification. A later attempt currently uses the coverage fast path and enters a task without re-verifying the failed carrier. Failure must remain terminal across wrappers and process/thread state merges.

**Needed:**
1. Reproduce reuse after verification failure; record sticky failure through registry effects, reject subsequent installation before native work, and preserve already-applied state.

## Evidence (6 October 2026)

- Base revision `2622ca27`; C3/C7 failure-quarantine slice. Four runtime regressions reproduced cached-policy reuse after verification failure: three fallback modes on the same physical worker through a different wrapper, and a failed process baseline followed by a fresh worker. The ordinary business-failure case already passed and is retained.
- Existing immutable `ContainerState` now retains the first installation failure, independently of applied-policy metadata and engine-state strength. The closed registry effect set includes `InstallationFailed`; the interpreter is still the state mutation owner, and the architecture assertion is updated.
- Failed and bypassed ordinary installation attempts latch thread/process failure. Ordinary/dedicated installation entry rejects the failed scope before a new attempt. Original failure identity is preserved; repaired native probes, changed fallback, and wrapper replacement cannot clear it.
- Precomputed thread/process native-publication callbacks preserve quarantine while still recording newly applied restrictions. Pure registry tests cover first-failure retention, stronger engine-state merging, merged-state cache invalidation, fresh-thread process visibility, and late coordinator publication in both scopes.
- Six quarantine host cases and eight registry-effect cases pass. Process-baseline fault injection runs in an isolated child JVM; the process-isolation architecture gate was kept unchanged.
- `FailedInstallationQuarantineNativeTest` applies a real kernel filter, deliberately fails its mode-verification observation, confirms actual mode 2 and retained filter depth, restores the real observer, and proves a later wrapped task still never enters. One actual-kernel test passed, zero failures/errors/skips.
- Existing connection/descriptor-transfer failure fixtures now assert original failure identity across supervised and plain-wrapper retries; handshake-count, filter-depth, and zero-target-entry assertions remain intact. Their two fresh-JVM cases passed.
- Full enforcer/profiler host suites and ABI checks passed. An initial merge gate rejected a new ThrowsCount warning; factored the early refusal helper and passed the unchanged warning ratchet without inventory expansion or suppression.
- `./gradlew build integrationTest integrationTestFreshJvm` passed: 265 tasks, 80 executed, 185 up-to-date. Full caller-mapped Docker gate passed in 47s.
- Quarantine rejects future library installation/task entry; it does not undo kernel restrictions or take ownership of arbitrary executor shutdown. Application-owned native/capability/runtime capture, CET feature evidence, and managed physical generation retirement remain unfinished C3/C7 work. This issue remains open for repository review.

---
<!-- id: issue-20261006-191042-005-quarantine-failed-installations-before-cached-poli  file: issue-20261006-191042-005-quarantine-failed-installations-before-cached-poli.md -->
