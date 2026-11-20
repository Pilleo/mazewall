---
title: "Reject hidden nested fields in sealed live preparation contracts"
severity: "MEDIUM"
status: "resolved"
priority: high
dependencies: []
component: "core"
target_modules: []
target_files:
  - ".superpowers/sdd/2026-10-27-hotspot-archive-requalification/remaining-images-3/recovery-bootstrap-correctness-1/live_inputs.py"
target_symbols:
  - "_contract_projection"
open_questions: false
---

# 🟡 [Severity: MEDIUM]: Reject hidden nested fields in sealed live preparation contracts

**Context:**
Independent I1c review found that pure preparation reconstructed known row fields without checking closed nested schemas. Updating a contract leaf and manifest pins plus only the sealed object _encoded allowed an unknown nested input field to pass unchanged projection comparison, although the real decoder rejects it. This is a WIP data-validation gap, not live-route authority. Review and exact reproduction are retained under /tmp/opencode/profiler-resume-dfx7y2wp/i1c-live-inputs-review.md.

**Needed:**
1. Reproduce the encoded-only mutation, enforce exact row/container and diagnostic shapes without compiler-bearing decode/encode calls, run focused purity regressions and obtain scoped re-review.

## Resolution — 2026-11-02

Pure projection reconstruction now checks the exact list containers and closed
row/diagnostic fields required by the frozen decoder. It preserves the existing
type-aware projection comparison and does not call compiler-bearing decode or
encode APIs. Expected-only runtime references remain data, not acquired bodies.

The exact encoded-only mutation failed before the correction. All 19 focused
tests now pass, including malformed rows/containers and effect-boundary checks.
Scoped independent re-review closed the finding with no remaining findings.

Evidence under `/tmp/opencode/profiler-resume-dfx7y2wp/`:
`i1c-live-inputs.md`, `i1c-live-inputs-review.md` and
`i1c-live-inputs-rereview.md`. Corrected source SHA256:
`f0e53936dafcaa808a8dcf5f32fe3e52b97438cf8b08349ac0445a9febe03a02`.
This resolves a pre-integration data-validation gap; it does not approve live
startup, runtime authority or four-image acceptance. No Gradle gate was repeated
for these standalone Python/data changes; the prior JVM sources remain unchanged.

---
<!-- id: issue-20261102-103137-113-reject-hidden-nested-fields-in-sealed-live-prepara  file: issue-20261102-103137-113-reject-hidden-nested-fields-in-sealed-live-prepara.md -->
