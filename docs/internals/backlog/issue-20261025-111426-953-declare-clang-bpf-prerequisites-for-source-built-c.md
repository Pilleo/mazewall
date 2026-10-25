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

## Reproduction and documentation

A fresh ordinary clone at `b224a13862c91dabcc6d914c5b86cb26a30a6893`, with
`--no-build-cache`, fails specifically at `:profiler:generateInvocationStateBtf`
when a private child-PATH fixture makes Clang unavailable. This is actual cold
project-output reproduction, not a textual dependency assertion. Shared Gradle
caches, the system compiler and credentials are untouched.

The root/demo/presenter entry points now declare Clang's BPF backend. The CLI guide
includes the actual C-source compiler probe targeting `/dev/null` and the existing
development-image route, including its required UID/GID/caller-owned cache inputs.
The compiler probe passes on the host. A second fresh clone at
`9e4aab7c62ab3cf11317cb44e7d12e7cc4229ac6` passes the actual workspace command in
3m07s: all 21 project actions execute with `--no-build-cache`, default configuration-
cache validation passes, and an entry is stored. Output proves the actual allowed
read, denied sibling, unaffected host and worker/fixture retirement. Dependency
download caches are deliberately retained; no shared cache was deleted. No
dependency graph, compiler installation, image or workflow change is made. The
development-container recipe is checked against its existing image/compose inputs;
a new development image/container was not launched for this fix.

---
<!-- id: issue-20261025-111426-953-declare-clang-bpf-prerequisites-for-source-built-c  file: issue-20261025-111426-953-declare-clang-bpf-prerequisites-for-source-built-c.md -->
