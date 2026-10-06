---
title: "Make executable mapping Boolean configuration a genuine setter"
severity: "MEDIUM"
status: "open"
priority: high
dependencies: []
component: "core"
target_modules: []
target_files: 
  - "enforcer/src/main/kotlin/io/mazewall/enforcer/api/JavaPolicyBuilder.kt"
target_symbols: 
  - "allowMmapExec"
open_questions: false
---

# 🟡 [Severity: MEDIUM]: Make executable mapping Boolean configuration a genuine setter

**Context:**
Integration foundation C2 and A04 require false-after-true configuration to remove executable mapping permission before installation.

**Needed:**
1. Reproduce false-after-true and frozen snapshot behavior, then delegate Boolean assignment to canonical configuration.

## Evidence (6 October 2026)

- Base source revision: `95417771`; owning revised plan task C2, finding A04.
- `ExecutableMappingConfigurationTest` reproduced three failures before the change: explicit false did not remove a direct grant, did not remove the HotSpot runtime grant, and did not produce a disabled snapshot before later runtime selection.
- `PolicyBuilder.setExecutableMappings` assigns the Boolean in the single existing configuration owner. The Java setter delegates to it; no second policy compiler or dependency was introduced. The existing no-argument enabling method uses the same assignment.
- This is pre-install configuration only. Built snapshots remain immutable; installed kernel filters remain irreversible.
- `./gradlew :enforcer:test :enforcer:checkKotlinAbi` passed after the repair.
- Packaged Java/Kotlin consumers now also check false-after-true and preservation of the earlier enabled snapshot.
- Fresh pinned consumer build passed with Java/Kotlin recompilation and updated dependency locks; packaged overload ambiguity remains explicitly characterized until C9 migration.
- `./gradlew build` passed: 272 tasks, 93 executed, one from cache, 178 up-to-date. `./scripts/adkw check-backlog` and `git diff --check` passed.
- The canonical fixed-scope builder/caller migration is still pending; this scoped setter correction alone does not complete C2.

---
<!-- id: issue-20261006-153913-826-make-executable-mapping-boolean-configuration-a-ge  file: issue-20261006-153913-826-make-executable-mapping-boolean-configuration-a-ge.md -->
