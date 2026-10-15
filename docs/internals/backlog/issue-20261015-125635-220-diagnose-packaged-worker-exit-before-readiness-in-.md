---
title: "Diagnose packaged worker exit before readiness in CI"
severity: "HIGH"
status: "open"
priority: high
dependencies: []
component: "core"
target_modules: [":portals:codegen"]
target_files:
  - "portals/codegen/src/test/kotlin/io/mazewall/portal/codegen/PortalCodegenFunctionalTest.kt"
  - ".github/workflows/ci.yml"
target_symbols:
  - "PortalCodegenFunctionalTest"
open_questions: false
---

# 🟡 [Severity: HIGH]: Diagnose packaged worker exit before readiness in CI

**Context:**
PR531 CI JUnit XML confirms verified worker bundle launch connects its Unix socket but stdout reaches EOF before MAZEWALL_PORTAL_WORKER_READY. Local owning test passes. Child stderr was an unread pipe and missing from the assertion, so underlying fail-closed startup cause was unavailable. Preserve actual containment/startup checks, retain child stderr and use next CI diagnostic to fix the cause rather than accepting missing readiness.

**Needed:**
1. Capture child stderr in owned evidence and report it with the unchanged READY assertion, then reproduce the exact CI startup cause.

## Diagnostic checkpoint

The child now writes stderr to an owned fixture file, and the strict readiness
assertion includes those actual diagnostics. The real local external-directory
bundle case passes (`run-0ca6062e3df2`). This does not resolve or skip the CI-only
startup refusal; its underlying exception remains to be captured on the next head.

## Established CI cause

Run38745626068's retained JUnit XML captures the real child exception:
`Dedicated process filesystem containment requires Landlock TSYNC (ABI 8+)`.
The worker correctly refuses the Ubuntu24.04 runner kernel before readiness;
this is an unavailable required containment feature, not a packaging/classpath
failure. No native fallback or missing-readiness acceptance is appropriate.

GitHub's official hosted-runner reference lists `ubuntu-26.04` as available, and
its runner-images manifest reports kernel7.0.0-1012-azure (image20260927.149.1).
Move the main correctness job to that compatible host. All real worker startup,
readiness, RPC and exit assertions remain unchanged. The next CI run must verify
the actual host capability and native behavior before this issue is resolved.

References: https://docs.github.com/en/actions/reference/runners/github-hosted-runners
and https://github.com/actions/runner-images/blob/main/images/ubuntu/Ubuntu2604-Readme.md.

---
<!-- id: issue-20261015-125635-220-diagnose-packaged-worker-exit-before-readiness-in-  file: issue-20261015-125635-220-diagnose-packaged-worker-exit-before-readiness-in-.md -->
