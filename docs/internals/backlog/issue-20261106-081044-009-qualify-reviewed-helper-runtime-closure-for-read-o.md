---
title: "Qualify reviewed helper runtime closure for read-only shared gconv mappings"
severity: "HIGH"
status: "open"
priority: high
dependencies: []
component: "core"
target_modules: []
target_files:
  - "enforcer/src/integrationTest/kotlin/io/mazewall/enforcer/supervisor/ConfinedHelperProcessIntegrationTest.kt"
  - "enforcer/src/integrationTest/kotlin/io/mazewall/enforcer/supervisor/ConfinedHelperReadinessIntegrationTest.kt"
target_symbols:
  - "ConfinedHelperProcessIntegrationTest"
open_questions: false
---

# 🟡 [Severity: HIGH]: Qualify reviewed helper runtime closure for read-only shared gconv mappings

**Context:**
Full gate012 non-root checkpoint-only profile reaches mapping identity validation but seventeen helper cases reject unaccounted r--s gconv-modules.cache. Printed reviewed support omits that object; fixture inventories select private mappings. Rootful controls pass. Exact mapping creation timing and all compound identity predicates remain unmeasured; production fail-closed check must remain intact.

**Needed:**
1. Correlate parent/helper mapped object keys and reviewed inventory under the unchanged profile, then reproduce and correct only justified fixture/bootstrap closure accounting with deterministic tests; never accept by pathname alone.

## Verified correction candidate

The operator approved this scoped correction. A disposable JVM mapped a genuine
file read-only shared; the compiling RED proved the fixture omitted its actual
`r--s` inode. In actual Gradle-context diagnostic 013, the parent already contained
the same gconv device/inode later rejected by the helper, but both selected-path
and selected-key membership were false. No late-object authorization was needed.

Both fixtures now include parent non-writable shared mappings alongside private
mappings. Production identity validation, canonical path/digest review, regular-file
and deleted-path checks, support limits and no-writer premise are unchanged. The
permanent regression also confirms a separate real writable-shared file is excluded.
Observed focused GREEN and integration formatting checks passed; independent review
found no Critical or Important findings. Evidence: `mapping-closure-fix-001/` and
`container-mapping-gradle-diagnostic-013/` within the D2 packet.

The 27-case non-root helper/readiness selection passed under the original
capability/syscall envelope:19 helper cases and8 readiness cases, no skips, errors
or failures. Integration formatting passed. Run014 exited0 after4m1s; the container
was physically exited with PID0 and no OOM. Full gate acceptance and issue closure
remain open because the separate socket-policy baseline is blocked.

---
<!-- id: issue-20261106-081044-009-qualify-reviewed-helper-runtime-closure-for-read-o  file: issue-20261106-081044-009-qualify-reviewed-helper-runtime-closure-for-read-o.md -->
