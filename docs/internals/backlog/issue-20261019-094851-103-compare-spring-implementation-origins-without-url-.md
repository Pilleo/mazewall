---
title: "Compare Spring implementation origins without URL-handler or DNS effects"
severity: "MEDIUM"
status: "open"
priority: high
dependencies: []
component: "spring"
target_modules: [":integrations:spring:mazewall-spring-boot"]
target_files:
   - "integrations/spring/mazewall-spring-boot/src/main/java/io/mazewall/spring/SpringOperationBinder.java"
   - "integrations/spring/mazewall-spring-boot/src/test/java/io/mazewall/spring/SpringOperationOriginTest.java"
   - "integrations/spring/mazewall-spring-boot/S1-S2-contract.md"
target_symbols:
  - "sameOrdinaryJarOrigin"
open_questions: false
needs_kernel: false
core_lock: false
effort: "small"
autonomy: "supervised"
has_side_effects: false
---

# 🟡 [Severity: MEDIUM]: Compare Spring implementation origins without URL-handler or DNS effects

**Context:**
The production same-origin check delegates to URL.equals, whose semantics can consult handlers/DNS and equate host aliases. Ordinary-JAR approval should compare deterministic locations, retain exact classloader checks, and fail closed on missing/malformed origins without network-dependent identity.

**Needed:**
1. Add a controlled CodeSource regression proving comparison does not invoke URL equality handlers; retain positive equivalent local file URI forms, different origins/loaders and malformed-origin rejection.
2. Use URI value comparison in the existing private predicate without a public test seam, preserving the existing ordinary-JAR binding contract and all native-policy behavior.

## Verified local correction

The controlled CodeSource regression first failed on URL-handler equality and
host-alias admission. The private predicate now compares URI values, retaining
loader identity and rejecting missing/malformed locations. Three new contracts
cover equivalent local URI spellings, different origins/loaders and the custom
URL equality trap. The complete Spring host suite passes113 tests and its analyzer
is complete; the full optional build exits0. No public test seam or runtime
dependency was added. Evidence:
`.superpowers/sdd/2026-10-19-analyzer-gates-resumed/spring-origin-red.log`,
`spring-origin-green-suite.log`, and
`.superpowers/sdd/2026-10-19-strict-analyzer-repair/completion-receipt.json`.
Keep open for scoped integration with recursive analyzer qualification.

---
<!-- id: issue-20261019-094851-103-compare-spring-implementation-origins-without-url-  file: issue-20261019-094851-103-compare-spring-implementation-origins-without-url-.md -->
