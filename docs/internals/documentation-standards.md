# Repository Documentation Standards (Human & Agent Co-Optimized)

To keep this codebase manageable for both human software engineers and AI coding agents (such as LLMs), the documentation follows a strict **modular, JIT-retrieved, and structured** layout model. 

---

## 1. Modularization over Monoliths

A massive documentation file is hard for humans to scan and extremely expensive for AI agents to load. A 200KB markdown file consumes upwards of 50,000 context tokens, causing **"lost in the middle"** context degradation where agents ignore critical details.

### Standard:
*   Keep files under **10KB (approx. 200 lines)**.
*   **The Backlog Standard:** All issues, bugs, and design changes are logged as individual, numbered Markdown files inside [docs/internals/backlog/](backlog/).
*   **The Index Rule:** Every directory of modular documents must contain a `README.md` that acts as a summary index table linking to individual files.

---

## 2. YAML Frontmatter (Metadata Schema) for Design Docs

To allow AI agents to map target files to design rules in microseconds without parsing full pages of prose, all design and roadmap documents should contain a YAML frontmatter header.

### Frontmatter Schema:
```yaml
---
title: "Title of the Document"
scope: "enforcer | profiler | ffi | cicd"
critical_syscalls: ["sys_a", "sys_b"]
target_files: ["path/to/affected/File.kt"]
keywords: ["key1", "key2"]
---
```

### Agent Integration:
Before modifying a system component, an agent can perform a high-speed grep search (e.g. `grep_search` looking for `target_files` or `scope: "enforcer"`) to discover which files contain the relevant design constraints, loading them only when needed.

---

## 3. Just-In-Time (JIT) Context Loading (`.agents/`)

The workspace `.agents/` directory is the entry point for AI agent rules. Placing raw design details or backlog logs inside the global `.agents/AGENTS.md` leads to prompt bloat and dilutes compiler guidelines.

### Standard:
*   The `.agents/AGENTS.md` and child agents files (e.g., `enforcer/AGENTS.md`) must only contain **behavioral invariants, compile targets, and critical boundaries**.
*   All design layouts, FFM mappings, and research notes must be kept out of the rule files and referenced via **absolute file links** (which agents resolve to physical paths) or relative paths:
    > "When editing memory mapping code or JIT safety checks, you MUST read the layout design guidelines in [containment_design.md](containment_design.md)."
*   This forces the agent to follow a JIT retrieval strategy: it scans the rule index first, and then selectively calls `view_file` on the target design document only if the active task touches that area.

---

## 4. Architecture-as-Code (Interactive Knowledge Graph)

To maintain a clear relationship graph of how JVM code coordinates with native systems, the repository maintains an interactive component map at [architectural_map.md](architectural_map.md).

### Standard:
*   Use standard Mermaid flowcharts to model cross-module dependency structures and trace notification sequence flows.
*   Make all chart nodes interactive by appending clickable Markdown or HTML file links:
    ```mermaid
    graph TD
        Enforcer[":enforcer Module"] -->|Security Policy| PolicyClass[Policy.kt]
        Enforcer -->|Design Document| ContainmentDesign[containment_design.md]
        
        click ContainmentDesign "containment_design.md"
    ```
*   This enables developers and agents to traverse component boundaries visually, instantly clicking through to the underlying code files or design constraints.

---

## 5. File Structure Inspection Tool

To avoid reading hundreds of lines of code just to understand the API surface or method layout of a source file, the repository provides a lightweight outline/structure tool.

### Usage:
Run the Kotlin script passing the path to any file:
```bash
# Kotlin files: outlines classes, objects, functions, properties
kotlin scripts/file_structure.main.kts enforcer/src/main/kotlin/io/mazewall/enforcer/ContainedExecutors.kt

# Markdown: outlines heading hierarchy
kotlin scripts/file_structure.main.kts docs/internals/containment_design.md

# YAML/YML: outlines top-level and second-level keys
kotlin scripts/file_structure.main.kts .github/workflows/ci.yml

# XML: outlines top-level element tags
kotlin scripts/file_structure.main.kts config/seccomp-profile.xml

# JSON: outlines top-level keys
kotlin scripts/file_structure.main.kts sbob.json
```

This returns a clean, hierarchical tree allowing both human developers and agents to immediately grasp the file's structure without reading its full contents.

---

## 6. Code-to-Docs Back-Reference Convention (`@ref`)

To create bidirectional navigation between source code and design documents, critical code sites should include structured `@ref` comments. This allows agents and developers to jump directly from a function to the authoritative design document — without having to search.

### Standard Format:
```kotlin
// @ref: docs/internals/containment_design.md — Description of what the link covers
// @issue: docs/internals/backlog/issue-042-tsync-race.md — Known issue affecting this code
```

### Rules:
*   Use `// @ref:` for links to design documents, security considerations, or research notes.
*   Use `// @issue:` for links to open backlog issues that affect this code site.
*   All paths must be **relative to the project root**.
*   Place `@ref` comments on the line **immediately before** the declaration (class, function, companion object) they describe.
*   Do not duplicate all design content in code — the comment is a **pointer**, not a summary.

### Example:
```kotlin
// @ref: docs/internals/containment_design.md — BPF linear scan and 8-bit jump limit
// @ref: docs/internals/jvm_syscall_floor_research.md — JVM floor syscall requirements
internal fun installFilter(arch: Arch, prog: MemorySegment, useTsync: Boolean) {
    ...
}
```

