---
title: "Verify candidate617 through authorized remote artifact retrieval"
severity: "MEDIUM"
status: "open"
priority: medium
dependencies: []
component: "integrations"
target_modules: []
target_files:
  - "docs/internals/reviews/integration-compatibility-manifest.md"
target_symbols: []
open_questions: false
---

# 🟡 [Severity: MEDIUM]: Verify candidate617 through authorized remote artifact retrieval

**Context:**
The immutable candidate0.1.0-candidate.617fc10d was verified from a local Maven repository. There is no operator-authorized exact remote repository plus independent retrieval and hash receipt; metadata HEAD must not relabel library bytes.

**Needed:**
1. After operator supplies the exact approved repository, retrieve the unchanged P617 artifact closure in an independent consumer and compare JAR/POM/module and strict-resolution hashes; leave credentials operator-owned and do not overwrite historical coordinates.

**Acceptance:** Exact repository/version, independent retrieval command/exit, resolved closure and payload hashes are recorded. Any changed byte or missing coordinate rejects. Metadata/documentation HEAD is not a library producer label. Local presentation uses an explicit staged repository until remote retrieval is actually authorized and verified.

---
<!-- id: issue-20261013-005316-364-verify-candidate617-through-authorized-remote-arti  file: issue-20261013-005316-364-verify-candidate617-through-authorized-remote-arti.md -->
