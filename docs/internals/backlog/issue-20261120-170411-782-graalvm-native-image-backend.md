---
title: "GraalVM Native Image backend"
severity: "LOW"
status: "open"
priority: medium
dependencies: []
component: "portal-runtime"
target_modules: []
target_files: []
target_symbols: []
open_questions: false
---

# 🟡 [Severity: LOW]: GraalVM Native Image backend

**Context:**
Explore strict executable-memory restrictions where runtime JIT compilation is unnecessary; this would offer a stronger memory-execution policy than normal HotSpot. (Java4Shell-roadmap P2 #17)

**Needed:**
1. Prototype strict executable-memory restrictions in native-image builds without runtime JIT.
2. Qualify memory-execution policy parity with the HotSpot baseline through native artifacts.
3. Wire the backend into the build and publication matrix for qualified consumers.
4. Document backend trade-offs and restrictions in release documentation.
5. Run owning focused checks and the project completion gate.

---
<!-- id: issue-20261120-170411-782-graalvm-native-image-backend  file: issue-20261120-170411-782-graalvm-native-image-backend.md -->
