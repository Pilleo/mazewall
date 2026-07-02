---
name: file_structure
description: >
  Inspect any file's structure, outline, or API surface before reading its full content.
  Use this skill when you need to understand what a file contains — classes, functions,
  headings, YAML keys, XML tags, or JSON keys — without loading the entire file.
  Trigger on: "inspect file", "outline", "what does X contain", "view api", "file structure",
  "what is in this file", "show me the structure of", "what methods/functions does X have".
---

# Skill: File Structure Inspection

## Purpose

Before reading any file in full with `view_file`, always outline its structure first.
This dramatically reduces token usage and prevents "lost in the middle" context drift.

> [!IMPORTANT]
> **This MUST be your first step when approaching any source file, config file, or design document.**
> Only call `view_file` after the outline tells you which section or function you actually need.

## Command

```bash
kotlin scripts/file_structure.main.kts <path_to_file>
```

## Supported File Types

| Extension | What is outlined |
|---|---|
| `.kt`, `.kts` | Classes, objects, interfaces, functions (with parameters), properties |
| `.md` | Heading hierarchy (`#`, `##`, `###`, ...) |
| `.yaml`, `.yml` | Top-level and second-level keys |
| `.xml` | Top-level and nested element tag tree |
| `.json` | Top-level and second-level keys with types |
| Other | Line count only (unsupported type message) |

## Examples

```bash
# Before reading a Kotlin class — see its public API
kotlin scripts/file_structure.main.kts enforcer/src/main/kotlin/io/mazewall/BpfFilter.kt

# Before reading a design doc — see which sections it has
kotlin scripts/file_structure.main.kts docs/internals/containment_design.md

# Before reading a CI workflow — see what jobs are defined
kotlin scripts/file_structure.main.kts .github/workflows/ci.yml

# Before reading a config — see what keys are present
kotlin scripts/file_structure.main.kts sbob.json
```

## Workflow Integration

1. **Receive a task** involving an unknown file or codebase component.
2. **Run the outline command** to understand the structure.
3. **Identify the relevant section** (e.g., a specific function or heading).
4. **Call `view_file`** with `StartLine`/`EndLine` targeting only that section.
5. Only read the **entire file** if the outline shows it is short (< 80 lines) or the whole content is relevant.

## When to Skip

- The file is already in your active context window from this session.
- The file is trivially small (e.g., a single-class DTO under 30 lines, a simple config value file).
