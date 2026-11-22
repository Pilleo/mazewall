---
title: "Harden worker artifacts"
severity: "HIGH"
status: "open"
priority: high
dependencies: []
component: "portal-runtime"
target_modules: []
target_files: []
target_symbols: []
open_questions: false
---

# 🟡 [Severity: HIGH]: Harden worker artifacts

**Context:**
Verify complete worker bundle integrity, classpath isolation, schema compatibility and launch configuration before guest execution; prevent untrusted guest implementations from loading in the host. (Java4Shell-roadmap P1 #12)

**Needed:**
1. Define integrity rules: bundle hashes/signatures, classpath isolation, schema compatibility, launch-config validation.
2. Implement bundle integrity verification and launch-config validation at worker admission.
3. Add negative tests: tampered bundle, mismatched classpath, invalid launch config.
4. Wire artifact verification into Portal readiness gates.
5. Run owning focused checks and the project completion gate.

---
<!-- id: issue-20261120-170351-749-harden-worker-artifacts  file: issue-20261120-170351-749-harden-worker-artifacts.md -->
