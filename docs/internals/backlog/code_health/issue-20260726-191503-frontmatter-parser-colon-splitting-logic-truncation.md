---
title: "Fix Custom Frontmatter Parser Colon-Splitting Logic Bug"
severity: "HIGH"
status: "open"
priority: 9
dependencies: []
component: "orchestrator"
target_modules:
  - ":tools:orchestrator"
target_files:
  - "tools/orchestrator/src/main/kotlin/io/mazewall/orchestrator/BacklogParser.kt"
effort: "small"
autonomy: "autonomous"
---

# 🔴 [Severity: HIGH]: Fix Custom Frontmatter Parser Colon-Splitting Logic Bug

**Context:**
The orchestrator's custom YAML frontmatter parser `BacklogParser.extractFrontmatter` processes frontmatter lines to build a key-value map. However, the logic for finding key-value pairs is overly simplistic:
```kotlin
} else if (line.contains(":")) {
    // Key-value pair
    if (currentKey != null) {
        frontmatter[currentKey] = currentValBuilder.toString().trim()
    }
    val parts = line.split(":", limit = 2)
    currentKey = parts[0].trim()
    currentValBuilder = StringBuilder(parts[1].trim())
} else if (currentKey != null) {
    currentValBuilder.append("\n").append(line)
}
```
If any value in a frontmatter field contains a colon character (e.g., inside a title like `title: "Review Task: Profiler Module"`, a URL like `url: https://...`, or inline text in a multiline block like `context: "The issue exists: we found it."`), the line will match `line.contains(":")`.
This triggers a split, truncating the existing `currentKey` context prematurely and defining a brand-new, spurious key in the frontmatter map (e.g. `"The issue exists"` with value `'"we found it."'`). This bugs out the parser, corrupts parsed descriptions, or drops valuable text fields.

**Needed:**
Refactor `BacklogParser.extractFrontmatter` key detection to be robust:
1. Ensure a line is only treated as a new frontmatter key if it matches a valid key pattern, e.g., starting with a word/identifier followed immediately by a colon and optional whitespace (e.g., `^[a-zA-Z0-9_-]+:\s*(.*)$`).
2. Alternatively, check that the colon is not inside quotes or is actually at the root level of a YAML key-value definition.
3. If a colon exists in a line but does not match the key-value declaration structure, treat the line as a continuation of the `currentKey` value builder rather than a new key.
