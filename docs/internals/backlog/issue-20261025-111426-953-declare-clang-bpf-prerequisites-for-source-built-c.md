---
title: "Declare Clang BPF prerequisites for source built core quickstart"
severity: "MEDIUM"
status: "open"
priority: high
dependencies: []
component: "docs"
target_modules: []
target_files:
   - "demos/cli-demo/README.md"
   - "README.md"
   - "demos/README.md"
   - "docs/presentation/PRESENTER_GUIDE.md"
target_symbols: []
open_questions: false
---

# 🟡 [Severity: MEDIUM]: Declare Clang BPF prerequisites for source built core quickstart

**Context:**
PR559 review: the new workspace quickstart inherits the profiler JAR resource generator, which invokes clang -target bpf. A clean project/cache with only the documented JDK25/Linux prerequisites fails before the example begins. Document the build-time compiler/backend, a source probe and existing development-container route; preserve the dependency graph and all shared caches.

**Needed:**
1. Verify the source task chain and reproduce unavailable Clang in an owned fresh clone with build-cache reuse disabled, then document and smoke-test the required compiler/backend.
2. Declare JDK25/Linux/native permissions plus build-time Clang with a BPF backend; provide a compiler probe and the existing configured development-container route.
3. Verify an owned fresh project build with Clang present and cache reuse disabled. Never delete shared caches, replace system tools, modify credentials, or add a new build dependency or launcher.

---
<!-- id: issue-20261025-111426-953-declare-clang-bpf-prerequisites-for-source-built-c  file: issue-20261025-111426-953-declare-clang-bpf-prerequisites-for-source-built-c.md -->
