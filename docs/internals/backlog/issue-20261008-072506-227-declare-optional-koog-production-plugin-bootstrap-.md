---
title: "Declare optional Koog production plugin bootstrap and Portal runtime publication"
severity: "MEDIUM"
status: "open"
priority: high
dependencies: []
component: "integrations"
target_modules: ["portals:runtime", "portals:codegen", "demos:koog-contained-tool"]
target_files: ["settings.gradle.kts", "portals/runtime/build.gradle.kts", "portals/codegen/build.gradle.kts", "scripts/koog-build.sh", "demos/koog-contained-tool/build.gradle.kts"]
target_symbols: []
open_questions: false
---

# 🟡 [Severity: MEDIUM]: Declare optional Koog production plugin bootstrap and Portal runtime publication

**Context:**
Task7's actual independent stable consumer exposed a missing Portal runtime Maven
artifact; source demo evaluation depended on init-script-only includes/export and
an unvalidated absolute classpath file. Task10 is the explicitly authorized scoped
production wiring follow-up, based on92da1536. No core/native/Spring implementation
or root credentials changes are authorized.

**Needed:**
1. Declare opt-in Koog includes; keep demo evaluation out of the producer phase.
2. Declare runtime's Java-component publication, preserving transitive API metadata.
3. Build/export the actual Gradle plugin closure, atomically receipt source inventory,
   worktree/version and JAR hashes, and reject stale direct buildscript use.
4. Supply a two-stage source wrapper; remove obsolete source-verification init magic.
5. Verify real generation/opaque packaging, stable published STRICT consumer,
   fresh native APIs/bounds and failure/restoration ownership under shared lock.
6. Keep open pending independent root-scope review and coordinator main integration.

Evidence and exact commands are maintained in the shared Task10 checkpoint/report;
this issue does not claim release/operator-profile/C4/human-usability closure.

---
<!-- id: issue-20261008-072506-227-declare-optional-koog-production-plugin-bootstrap-  file: issue-20261008-072506-227-declare-optional-koog-production-plugin-bootstrap-.md -->
