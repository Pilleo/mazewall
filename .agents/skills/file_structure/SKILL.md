---
name: file_structure
description: >
  MANDATORY first step before reading any Kotlin, Java, or config file.
  Use BEFORE any task that involves: fixing bugs, reviewing code, refactoring,
  reading a class, understanding an interface, investigating an issue, or
  navigating unfamiliar code. Do NOT call view_file on .kt/.java first.
  Trigger on: any code navigation, reading source files, code review, bug investigation,
  inspect file, outline, what does X contain, view api, file structure,
  what is in this file, show me the structure of, what methods/functions does X have.
---

# Skill: File Structure Inspection

## Purpose

Before reading any file in full with `view_file`, always outline its structure first.
This dramatically reduces token usage and prevents "lost in the middle" context drift.

> [!IMPORTANT]
> **This MUST be your first step when approaching any source file, config file, or design document.**
> Only call `view_file` after the outline tells you which section or function you actually need.

## Command & Tools

1. **For JVM Code symbols (Kotlin/Java classes, methods)**:
   Prefer using **Codanna** to inspect structures and relationships:
   ```bash
   codanna mcp find_symbol <SymbolName>
   # or to get full structure and methods:
   codanna retrieve describe <SymbolName>
   ```

2. **For file outlines (non-code or specific files)**:
   Use the local kotlin helper script:
   ```bash
   kotlin scripts/file_structure.main.kts <path_to_file>
   ```

## Supported File Types (Local Script)

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
# Get outline/methods of BpfFilter class:
codanna retrieve describe BpfFilter

# Trace what buildFromActions calls:
codanna mcp get_calls buildFromActions

# Find who calls getJvmCriticalNrs:
codanna mcp find_callers getJvmCriticalNrs

# Before reading a design doc — see which sections it has:
kotlin scripts/file_structure.main.kts docs/internals/designs/enforcer/containment-design.md

# Before reading a CI workflow — see what jobs are defined:
kotlin scripts/file_structure.main.kts .github/workflows/ci.yml
```

## Workflow Integration

1. **Receive a task** involving an unknown file or codebase component.
2. **Retrieve the structure** using `codanna retrieve describe` (for code symbols) or `kotlin scripts/file_structure.main.kts` (for documents/configs).
3. **Identify the relevant section** (e.g., a specific function or heading).
4. **Call `view_file`** with `StartLine`/`EndLine` targeting only that section.
5. Only read the **entire file** if the outline shows it is short (< 80 lines) or the whole content is relevant.

## When to Skip

- You have already outlined this specific file in the CURRENT turn (not just the session).
- The file has fewer than 30 lines as confirmed by a previous listing.
