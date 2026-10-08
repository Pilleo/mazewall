---
title: "Implement bounded upstream Koog Portal tool and generated packaged consumer"
severity: "MEDIUM"
status: "open"
priority: high
dependencies: []
component: "integrations"
target_modules: ["integrations:koog", "demos:koog-contained-tool"]
target_files: ["integrations/koog/src/main/kotlin/io/mazewall/koog/PortalToolAdapter.kt"]
target_symbols: ["PortalToolAdapter"]
open_questions: false
---

# 🟡 [Severity: MEDIUM]: Implement bounded upstream Koog Portal tool and generated packaged consumer

**Context:**
Approved P5 requires actual upstream Koog 1.3.0 Tool/typeToken/registry integration
with one owned prepared Portal operation, rather than framework substitutes or
in-process worker fallback. The bounded first-party text service requires an
opaque generated worker bundle with independent guest dependencies.

**Needed:**
1. Bind a thin cancellable coroutine adapter to PreparedPortalOperation.submit;
   preserve original handle cancellation and physical lifetime ownership.
2. Generate and package a deterministic text service using the actual plugin;
   enforce 64 KiB strict UTF-8 data and separate 128 KiB wire bounds.
3. Verify actual registry/JSON validation, app and wire result limits, unknown
   completion/terminal state, denial and cancellation from published artifacts
   in a disposable fresh directory with original output paths absent.
4. Coordinator integrates root opt-in/plugin-resolution wiring and the minimal
   Portal runtime Maven publication seam. Operator launch-profile/C4/human gates
   remain independent; no release closure from this fixture.

**Evidence:**
- Adapter host tests (5), demo UTF-8 test (1), adapter quality and actual
  generator/portalCheck pass in final `run-4f46ba1905a3`. The sole JVM-public
  constructor requires PreparedPortalOperation; fake submitters are test-only.
- Stable Kotlin 2.4.20/JDK25 independent published consumer with locked graph
  passes with STRICT locks in `run-91ca8b0e0c06`; no compiler metadata/prerelease skip flags.
- Disposable fresh native probe `run-9d0d135ac7c4` passes actual upstream registry,
  typed generated service, file denial, both limits, terminal session, cancellation
  and physical worker shutdown. Source builds/repository paths were hidden under
  shared lock with restoration trap. Test service is never advertised.
- Public publication seam is proven by `run-e035a128353e`: runtime has an empty
  publish aggregate and the actual consumer cannot resolve its Maven artifact.
  Only a flagged temporary init publication was used; Portal/root files unchanged.

**Remaining:**
Root include/plugin-resolution and production runtime publication require the
coordinator patch in task-7-report.md. Keep this item open pending integration.

---
<!-- id: issue-20261008-034925-367-implement-bounded-upstream-koog-portal-tool-and-ge  file: issue-20261008-034925-367-implement-bounded-upstream-koog-portal-tool-and-ge.md -->
