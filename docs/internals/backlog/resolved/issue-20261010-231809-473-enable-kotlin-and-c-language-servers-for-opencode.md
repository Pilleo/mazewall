---
title: "Enable Kotlin and C++ language servers for OpenCode"
severity: "LOW"
status: "resolved"
priority: low
dependencies: []
component: "core"
target_modules: []
target_files:
  - "opencode.jsonc"
  - "docs/internals/backlog/README.md"
target_symbols: []
open_questions: false
---

# 🟡 [Severity: LOW]: Enable Kotlin and C++ language servers for OpenCode

**Context:**
Enable OpenCode LSP for Kotlin and C/C++ using the installed Kotlin server and managed clangd. Verify configuration and diagnostics in isolated fixtures without running the blocked project build.

**Needed:**
1. Add project LSP configuration and verify Kotlin and C/C++ diagnostic startup.

**Resolution:**
- Added `opencode.jsonc` to enable built-in LSP servers, including clangd for C/C++, and use the PATH-installed Kotlin server with `--stdio`.
- The existing Kotlin server (`262.8190.0`) exited with an expired-build message. Installed the current official standalone release `263.6379.0`, verified SHA-256 `ab8ca4455dc2fc5fe1a24db2bccc46c104254d2c465155c4251ee65df8f3f7cc`, and updated the existing user-level `kotlin-lsp` symlink. The previous installation remains available.
- Used OpenCode-managed clangd `23.1.0`. OpenCode `1.18.35` still constructs the old Kotlin ZIP download URL; the explicit command avoids that outdated downloader.

**Verification:**
- Installed OpenCode accepted the project configuration through `opencode debug config`.
- An isolated C++ fixture produced the expected `init_conversion_failed` diagnostic through OpenCode.
- Kotlin completed a direct LSP initialization handshake and advertised symbol, definition, reference, and diagnostic capabilities.
- Loose Kotlin files without a supported imported project returned empty diagnostics/symbols. Full project semantic analysis was not certified; project import and Gradle verification remain outside this configuration task.
- No project build or Gradle logic changes were made.

---
<!-- id: issue-20261010-231809-473-enable-kotlin-and-c-language-servers-for-opencode  file: issue-20261010-231809-473-enable-kotlin-and-c-language-servers-for-opencode.md -->
