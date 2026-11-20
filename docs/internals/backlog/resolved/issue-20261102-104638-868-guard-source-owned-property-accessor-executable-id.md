---
title: "Guard source-owned property accessor executable identity during setup"
severity: "MEDIUM"
status: "resolved"
priority: high
dependencies: []
component: "core"
target_modules: []
target_files:
  - ".superpowers/sdd/2026-10-27-hotspot-archive-requalification/remaining-images-3/recovery-bootstrap-correctness-1/bootstrap_entry.py"
target_symbols:
  - "_validate_setup_entry"
open_questions: false
---

# 🟡 [Severity: MEDIUM]: Guard source-owned property accessor executable identity during setup

**Context:**
Independent I1c setup review found that class snapshots guarded plain functions but only the object identity of PhaseBudget.phase. Replacing its fget code with an equal but distinct CodeType left the descriptor unchanged and was accepted before budget binding and during budget access. The isolated reproduction confirmed both paths. This is a WIP integrity-guard gap; application activation remains denied. Review evidence is /tmp/opencode/profiler-resume-dfx7y2wp/i1c-root-producer-review.md.

**Needed:**
1. Retain and validate source-owned property accessor identities and executable function state before getter execution, preserve normal phase advancement, run cold regressions and obtain scoped re-review.

## Resolution — 2026-11-02

The setup producer now captures the selected source-owned property's accessor
identities and executable function state. The guard checks them before budget
binding or access can execute the getter; property-object identity alone is no
longer accepted as evidence that its executable state is unchanged.

The cold reproduction first confirmed both paths accepting and executing a
changed getter. All 35 entry tests pass after the correction, including normal
phase advancement. The original 15 entry definitions and `PINS` remain
AST-identical. Scoped independent re-review closed the P2 finding with no new
scoped findings.

Evidence under `/tmp/opencode/profiler-resume-dfx7y2wp/`:
`i1c-root-producer.md`, `i1c-root-producer-review.md` and
`i1c-root-producer-rereview.md`. Corrected entry source SHA256:
`d005d075b7c4bf435aab40cffe2cedf93228a762b11bbc32c838966228bbfa02`.
This clears the setup integrity correction, not application integration or live
authority. No Gradle or live-authority checks were run for this Python-only fix.

---
<!-- id: issue-20261102-104638-868-guard-source-owned-property-accessor-executable-id  file: issue-20261102-104638-868-guard-source-owned-property-accessor-executable-id.md -->
