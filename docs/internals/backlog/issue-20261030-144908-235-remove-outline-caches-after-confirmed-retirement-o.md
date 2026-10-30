---
title: "Remove outline caches after confirmed retirement on parser failure"
severity: "MEDIUM"
status: "open"
priority: high
dependencies: []
component: "integrations"
target_modules: []
target_files:
  - "demos/koog-document-agent/src/main/kotlin/demo/koog/OutlineFileApplication.kt"
target_symbols: []
open_questions: false
---

# 🟡 [Severity: MEDIUM]: Remove outline caches after confirmed retirement on parser failure

**Context:**
PR562 review finding3:129headings reject after worker launch and canonical close, but leave17.6MB private cache despite a dead child. Never delete possibly-live resources or hide the original failure.

**Needed:**
1. Add packaged invalid-input native test proving child retirement and cache cleanup RED; establish retirement on success and failure, retain uncertain resources and original/suppressed errors; full native suite.

## Execution

- [x] Real129-heading failure/cache regression RED→GREEN; original error and dead child checked.
- [x] Resource cleanup uses standard use() suppression and confirmed canonical retirement on both paths.
- [ ] Final copied/full gates.

The test owns a private java.io.tmpdir and requires its extracted cache removed
after the actual child ends. The application checks captured child and CLOSED /
retirementComplete / zero-live-workers diagnostics after runtime close even on
failure, before allowing cache removal. Unknown retirement keeps the cache; original
failures retain retirement/delete failures as suppressed exceptions. No success
artifact is displayed after a parser error. All4outline-file cases pass within
Koog26host/11native suite. Logs `/tmp/opencode/pitch-fix-koog-{red,green}.log`.

---
<!-- id: issue-20261030-144908-235-remove-outline-caches-after-confirmed-retirement-o  file: issue-20261030-144908-235-remove-outline-caches-after-confirmed-retirement-o.md -->
