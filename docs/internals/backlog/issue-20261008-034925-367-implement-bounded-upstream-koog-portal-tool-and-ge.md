---
title: "Implement bounded upstream Koog Portal tool and generated packaged consumer"
severity: "MEDIUM"
status: "open"
priority: high
dependencies: []
component: "integrations"
target_modules: ["integrations:koog", "demos:koog-contained-tool", "portals:runtime"]
target_files: ["integrations/koog/src/main/kotlin/io/mazewall/koog/PortalToolAdapter.kt", "portals/runtime/src/main/kotlin/io/mazewall/portal/PreparedPortalOperation.kt", "portals/runtime/src/main/java/io/mazewall/portal/PreparedPortalOperation.java"]
target_symbols: ["PortalToolAdapter", "PreparedPortalOperation"]
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

**Round 1 review remediation:**
- S1 authorized minimal runtime-root correction: PreparedPortalOperation is now
  a final public Java facade with a genuinely package-private constructor and
  unchanged public submit. Runtime.prepare still constructs it in-package;
  test-only fake construction is not present in production artifacts. External
  javac negative rejects only constructor access; external public-submit positive
  and real runtime/generated-service dispatch remain passing.
- Q1 native runner now registers complete object-identity move ownership before
  effect, attempts every restore independently, refuses exact destination conflicts
  and unowned/missing backups, and never nests/overwrites unknown output. Native
  status remains primary while cleanup failures aggregate/report separately.
  Ten executable filesystem/process fault-injection cases pass, including
  raced destination, replaced backup, simultaneous native/restore failure,
  post-effect hide error, missing parent and catchable signal/descendant cleanup,
  including a signal before the owned launcher establishes its session.
- Q2 original child/owner snapshot is captured before crash. New focused native
  broker regression compares a monotonic lifetime spawn count captured before
  crash after joined close, catching short-lived/pre-delivery reconstruction.
- No other Portal/P3/core/root production source changed. New facade classfile
  targets Java22 (major66); producer remains Beta1, consumer stable2.4.20/JDK25.
  Root/plugin/runtime-publication integration and operator/C4/human release gates
  are still coordinator-owned and pending. See round1 evidence in shared report.

---
<!-- id: issue-20261008-034925-367-implement-bounded-upstream-koog-portal-tool-and-ge  file: issue-20261008-034925-367-implement-bounded-upstream-koog-portal-tool-and-ge.md -->
