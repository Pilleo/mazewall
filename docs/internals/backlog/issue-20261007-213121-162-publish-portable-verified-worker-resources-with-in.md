---
title: "Publish portable verified worker resources with independent dependency closure"
severity: "HIGH"
status: "open"
priority: high
dependencies: []
component: "core"
target_modules: ["portals:runtime", "portals:codegen"]
target_files: 
  - "portals/runtime/src/main/kotlin/io/mazewall/portal/PortalWorkerArtifactCatalog.kt"
target_symbols: 
  - "PortalWorkerArtifactCatalog"
open_questions: false
---

# 🟡 [Severity: HIGH]: Publish portable verified worker resources with independent dependency closure

**Context:**
Approved P4 requires packaged host artifacts to resolve opaque verified worker bundles without original absolute build paths. Worker source-set configurations currently inherit every host implementation/runtime dependency.

**Needed:**
1. Publish format-2 indexed opaque bundle resources, bounded digest-verified materialization, and explicit worker-only dependencies; verify relocation and rejection cases.

**Implemented packaging evidence (pending review and packaged native release gate):**
- Format2 uses digest-addressed opaque classpath ZIP resources; format1 metadata is retired under the no-legacy approval. Explicit local artifact configuration remains available.
- Resource copying is bounded to 64 MiB, digest-verified, staged/atomic and fatal-first on rollback. Resource copying/extraction share cache ancestor/destination symlink rejection. Missing/duplicate/unknown/unsafe resources reject before launch.
- Metadata publication uses declared relocatable inputs and transactional output replacement. Worker implementation/runtime configurations no longer inherit host dependencies; Kotlin and Java-only fixtures declare their worker closure explicitly.
- Missing materializer baseline (`run-b7de834d8565`). Real TestKit host-only JAR leakage reproduced (`run-e8fb1892c9b6`); portable host JAR resolves/materializes after deleting its original build directory, with no directly loadable dispatchers or host-only JAR in the bundle.
- Focused runtime/portable/quality checks passed (`run-b853aff432e6`). Full owning suites exposed the Java-only fixture's missing explicit worker dependencies; migrated fixture passed (`run-7b0d8f828873`). Full `./gradlew build` passed in 2m7s (`run-8af34f0ff7ce`). Backlog validation passed (`run-71e4b846034c`).
- This establishes portable package resolution and closure, not the remaining P3 required-generation handshake or P5/R0 external generated-service native consumer. Those remain separate release acceptance evidence.

---
<!-- id: issue-20261007-213121-162-publish-portable-verified-worker-resources-with-in  file: issue-20261007-213121-162-publish-portable-verified-worker-resources-with-in.md -->
