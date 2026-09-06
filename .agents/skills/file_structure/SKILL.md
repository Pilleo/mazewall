---
name: file_structure
description: >
  Inspect the structure of unfamiliar or large Kotlin, Java, configuration, and
  documentation files before reading them in full.
  Trigger on: any code navigation, reading source files, code review, bug investigation,
  inspect file, outline, what does X contain, view api, file structure,
  what is in this file, show me the structure of, what methods/functions does X have.
---

# Skill: File Structure Inspection

## Purpose

Outline unfamiliar modules and files of roughly 400 lines or more before reading them in full.
For short files or files already outlined in the current turn, proceed directly to the relevant content.

Use an outline when it will reduce navigation cost or clarify an unfamiliar API surface. It is a recommendation, not a mandatory pre-read gate.

## Command & Tools

0. **For whole-module architecture (fastest first orientation):**
   Before diving into individual symbols, check the generated artifacts:
   - **Class diagrams** (PlantUML/SVG): `docs/diagrams/enforcer_class_diagram.puml`, `docs/diagrams/profiler_class_diagram.puml`
     - Regenerate: `./gradlew :enforcer:generateClassDiagrams :profiler:generateClassDiagrams`
   - **Knowledge maps** (Mermaid — links source files, design docs, and open issues): `docs/internals/designs/core/maps/enforcer_map.md`, `docs/internals/designs/core/maps/profiler_map.md`
     - Regenerate: `./gradlew generateKnowledgeMap`

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
- The file is short enough that an outline would not improve navigation.
- You already know the relevant section and can read it narrowly.
