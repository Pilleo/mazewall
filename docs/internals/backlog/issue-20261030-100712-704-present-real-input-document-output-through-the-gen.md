---
title: "Present real input document output through the generated Portal worker"
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

# 🟡 [Severity: MEDIUM]: Present real input document output through the generated Portal worker

**Context:**
Approved useful-application Task4: actual selected Markdown input -> actual process outline output; preserve opaque generated worker, old runPortal/demoPortal/AIAgent tests and separate launch-compatible process baseline. Host file acquisition is not guest parsing containment.

**Needed:**
1. Write actual file-mode native regression RED, implement outline CLI using existing runtime/tool/configuration, require changed output and actual child death, preserve limits/control fixture and update packaging.

## Execution

- [x] Task4 actual input/output/opacity/child-retirement native regression RED->GREEN.
- [x] Existing limits, old commands and separate process launch preserved.
- [x] Final review/source/full gates and ZIP qualification.

Two actual documents first observed RED at the missing file entry, then GREEN with
different real outline output and captured child retirement. Oversized input rejects
before worker startup. `outlineFile`/installed `outline` are additive; legacy real
AIAgent/process checks remain selected. Host acquisition is explicitly not parser
containment. Cache removal follows established physical retirement only.

Ruling: reject terminal control characters in the displayed artifact, without
changing the worker parser/wire limits. A real ESC-bearing heading observed RED
before that host display check. Cost if wrong: such unusual input fails display
rather than altering the terminal or presenting a false clean trace.

Final producer `8b4e2c414052271de6461fdd1a95f7ccaa85eecb` passed full build and
exact copied acceptance. All3file-mode native cases ran without skips; installed
launcher also returned the actual bundled deployment outline and child-retirement
observation. Separate review noted the deferred failed-execution private-cache
retention minor; details/evidence/kits in issue-20261030-100713-290. Open pending
separately approved landing; no operator installation or old kit replaced.

---
<!-- id: issue-20261030-100712-704-present-real-input-document-output-through-the-gen  file: issue-20261030-100712-704-present-real-input-document-output-through-the-gen.md -->
