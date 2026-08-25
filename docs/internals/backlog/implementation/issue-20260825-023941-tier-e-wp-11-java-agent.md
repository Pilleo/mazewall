---
title: "Tier E WP-11: Limited Java agent for automatic boundary scopes"
severity: "ENHANCEMENT"
status: "open"
priority: medium
component: "profiler"
target_modules:
  - ":profiler"
target_files:
  - "profiler/src/main/kotlin/io/mazewall/profiler/agent/"
effort: "large"
autonomy: "supervised"
open_questions: true
dependencies:
  - "issue-20260825-023940-tier-e-wp-10-oracle-comparison.md"
---

# 🟢 [Severity: ENHANCEMENT]: WP-11 — Limited Java Agent

**Context:** Removes the requirement to hand-write `MazewallContext.withContext` at every
boundary. Instrumentation is narrow and opt-in: obvious semantic boundaries only. The agent
emits enter/exit around the original method body — nothing else.

Design reference: [tier-e-design.md §4.1.1, §11 risk 5](../../designs/profiler/tier-e-design.md).

**Needed:**

1. Standard `ClassFileTransformer` agent (`premain`); transformation strategy via Byte Buddy
   or ASM — **dependency requires explicit operator approval in the PR** (Class-File API not
   chosen to keep JDK 22 floor).
2. Configuration first:

   ```properties
   mazewall.agent.includes=com.example.document.*,com.example.integration.*
   ```

   plus optional annotation trigger (`@MazewallProfile("PDF_PARSE")`).

3. Transformed shape (no allocation per call if avoidable):

   ```java
   ContextToken token = ContextRuntime.enter(PDF_PARSE);
   try { return original(...); }
   finally { ContextRuntime.exit(token); }
   ```

4. **Double recursion guard (mandatory):**
   * Class-level unconditional excludes: `io.mazewall.**`, `java.**`, `jdk.**`, `sun.**`,
     agent implementation classes, bridge classes, FFM/native marker glue;
   * Runtime thread-local advice guard (`BridgeGuard.active`) protecting against indirect
     re-entry through uninstrumented frames calling back into instrumented ones.
5. First automatic targets ONLY: `@Controller`/`@RestController`, `@Scheduled`, configured
   parser/integration packages, annotated methods. Never every-service/every-method.

### Tests

```text
instrumented method attributed with configured context (via collector drain)
excluded packages untouched (bytecode identity check)
re-entrant call through excluded frame does not recurse (BridgeGuard)
exception through instrumented method restores previous context
agent + explicit MazewallContext compose (innermost wins)
```
