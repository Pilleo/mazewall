---
title: "Align mutation selectors with migrated platform test namespaces"
severity: "HIGH"
status: "resolved"
priority: high
dependencies: []
component: "core"
target_modules: [":platform"]
target_files:
  - "platform/build.gradle.kts"
target_symbols:
  - "pitest"
open_questions: false
---

# 🟡 [Severity: HIGH]: Align mutation selectors with migrated platform test namespaces

**Context:**
CI run38766101235 passes host/kernel correctness then platform:pitest fails97percent coverage gate at54. The selected ABI tests were migrated to io.mazewall.platform.lifecycle, but PIT still selects syscall/core test names; NetworkSyscallMapper production target and existing equivalent-mutant exclusion also retain obsolete core namespace. Local PIT reproduces54percent (run686870cfb3af). Correct exact declared selectors without reducing thresholds or shrinking intended pure ABI/state-machine scope.

**Needed:**
1. Point the same mutation targets and their actual emitted tests/existing equivalent mutant exclusion at current fully qualified names; run real platform mutation testing and preserve all97/61/98 thresholds.

## Resolution

Corrected five fully qualified selectors in `platform/build.gradle.kts` against
the actual production and test package declarations. The intended mapper target,
three ABI tests and existing equivalent-mutant exclusion now match emitted
classes. No new exclusion, dependency or threshold change was introduced.

Actual `:platform:pitest` reproduces the 54% line-coverage failure before the fix
(`run-686870cfb3af`) and passes afterward (`run-177642cbf944`): 212/217 lines
(98%), 48/74 mutations killed (65%), and 98% test strength. The original
97%/61%/98% floors remain enforced. The complete remote pipeline remains the
PR merge gate.

---
<!-- id: issue-20261015-162735-795-align-mutation-selectors-with-migrated-platform-te  file: issue-20261015-162735-795-align-mutation-selectors-with-migrated-platform-te.md -->
