---
title: "Migrate remaining application callers to owned runtimes and retain incomplete lifecycle owners"
severity: "MEDIUM"
status: "resolved"
priority: high
dependencies: []
component: "demos"
target_modules: ["demos:cli-demo", "demos:vulnerable-web-app"]
target_files:
  - "demos/vulnerable-web-app/src/main/kotlin/demo/vulnapp/config/ServiceExecutorOwner.kt"
target_symbols:
  - "ServiceExecutorOwner"
open_questions: false
---

# 🟡 [Severity: MEDIUM]: Migrate remaining application callers to owned runtimes and retain incomplete lifecycle owners

**Context:**
**Resolution — 2026-10-12:** Needed step1 fulfilled: application bootstrap/invocation/profiler shutdown/drain owners migrated, late wrapped collection retained, actual web resources acquired and cleanup/cause/budget fixes accepted. E=`/home/leanid/Documents/code/java/jseccomp/.superpowers/sdd/2026-10-11-integration-contract-completion/`: `task-4b/task-4b-fix-review.md` and 48 unfiltered owning tests, quality/ABI/ratchet receipts; accepted P617 gate. [Acceptance](../../reviews/2026-10-12-integration-contract-completion.md). Earlier WIP/open prose is historical.

Task4b migrates CLI and vulnerable web required execution without borrowed wrappers; public cutover remains Task4c.

**Needed:**
1. Migrate actual bootstrap, invocation and profiling-drain owners; verify focused host and disposable native controls.

**Task4b implementation and review evidence:**
- CLI callers use owned open/prepare/submit/Callable with bounded result wait, cooperative cancellation and application-retained incomplete owners.
- Web required service operations use fixed prepared domains with real invocation handles, original cause/fatal identity and retryable drain. Diagnostic profiling executors remain separate; incomplete profiling retains collectors until actual physical retirement.
- No application borrowed-wrapper caller remains. Public API/test-primitive removal belongs to Task4c; internal SandboxDispatcher dependencies are preserved.
- Evidence package: `.superpowers/sdd/2026-10-11-integration-contract-completion/task-4b/task-4b-report.md` in the operator repository root, with task-only diff and inventory. Issue remains open for scoped review and the coherent completion gate.
- Fix round 1 addresses all five scoped review findings: raw-pool retirement precedes actual profiler-wrapper/session close; all exceptional cleanup owners are rooted before effects and attempted before fatal/secondary delivery; entry deadlines include contention and pending collector/publication work remains owned; late prepare publication is retained; interrupted/combined reports preserve the original runtime counts and cleanup cause.
- `ServiceResourceBindings` keeps deployment `/app/uploads` and `/app/data` defaults while giving host fixtures acquired temporary equivalents shared by policy and service use. The missing full-web fixture was local work, not a waived operator prerequisite. The protected full-context control now exercises benign HTTP, bound upload and actual command-launch denial with honest original native cause; the missing-resource/zero-entry control remains separate.
- Actual `Profiler.wrap` late-operation native proof and concurrent/fatal/native-cleanup retention controls are in the appended fix-round report. No synthetic snapshot is used to claim native collector completeness. Genuine incomplete coverage remains refused.

---
<!-- id: issue-20261011-230257-867-migrate-remaining-application-callers-to-owned-run  file: issue-20261011-230257-867-migrate-remaining-application-callers-to-owned-run.md -->
