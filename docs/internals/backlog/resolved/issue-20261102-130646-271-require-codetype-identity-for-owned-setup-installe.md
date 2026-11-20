---
title: "Require CodeType identity for owned setup installer frames"
severity: "MEDIUM"
status: "resolved"
priority: high
dependencies: []
component: "core"
target_modules: []
target_files:
  - ".superpowers/sdd/2026-10-27-hotspot-archive-requalification/remaining-images-3/recovery-bootstrap-correctness-1/bootstrap_entry.py"
target_symbols:
  - "compile_root"
open_questions: false
---

# 🟡 [Severity: MEDIUM]: Require CodeType identity for owned setup installer frames

**Context:**
Independent helper-loader review found that the installer-frame allowlist uses tuple membership, which compares CodeType objects by equality. An equal-but-distinct genuine_code.replace() installed in a FunctionType with the same globals and producer can therefore pass the claimed actual-frame guard and reach selected helper acquisition and execution. This is a WIP provenance guard gap, not a claim of untrusted Python ACE containment. Review evidence: /tmp/opencode/profiler-resume-dfx7y2wp/i1c-helper-loader-review.md. Activation remains denied.

**Needed:**
1. Reproduce a copied installer CodeType in a cold owned fixture, replace equality membership with actual object-identity checks, verify denial before grants and source effects and genuine installation success, then obtain scoped re-review.

## Resolution — 2026-11-02

The allowed installer-code guard now uses explicit object identity rather than
tuple equality. The cold regression first demonstrated an equal-but-distinct
installer actually acquiring and registering gate. After correction it is denied
before source I/O or source/import/exec/codegen debits, while the genuine installer
still succeeds. All 35 entry tests pass; original entry definitions and `PINS`
remain AST-identical. Scoped independent re-review closed P2 with no new findings.

Evidence under `/tmp/opencode/profiler-resume-dfx7y2wp/`:
`i1c-helper-loader.md`, `i1c-helper-loader-review.md` and
`i1c-helper-loader-rereview.md`. Corrected entry SHA256:
`d5a4ef76b52574f6cb75770790c7a91a046a5f443fcb923108c97474c45f9502`.
The helper handles remain definitions only. This correction does not qualify a
provider, activate limits or approve live operation or image acceptance.

---
<!-- id: issue-20261102-130646-271-require-codetype-identity-for-owned-setup-installe  file: issue-20261102-130646-271-require-codetype-identity-for-owned-setup-installe.md -->
