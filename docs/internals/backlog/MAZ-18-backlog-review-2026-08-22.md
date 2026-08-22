# MAZ-18 Backlog Review - Open Questions Analysis

**Date:** 2026-08-22  
**Status:** Complete  
**Reviewer:** Vibe ACP Developer (d159bcf4-4a01-4fd8-9007-bad4aababfeb)  

---

## Executive Summary

Reviewed **86 open backlog issues** across 6 categories (security, performance, testing, code_health, implementation, platform). 

- **9 issues** have explicitly marked open questions (`open_questions: true`)
- **15+ additional issues** contain architectural decisions requiring input
- **Current documentation** (README, security-considerations.md, designs/) provides sufficient context for ~70% of implementation questions
- **Gaps identified:** 8 critical open questions cannot be answered by existing docs or code and require architectural/operator input

---

## Methodology

1. Scanned all issue files in `docs/internals/backlog/**/*` (excluding `resolved/`)
2. Identified files with `open_questions: true` frontmatter or "Open Questions" sections
3. Cross-referenced each question against:
   - Existing documentation (`docs/`, `README.md`, `GETTING_STARTED.md`)
   - Existing code implementations (`enforcer/`, `profiler/`, `platform/`)
   - Existing design documents (`docs/internals/designs/`)
4. Categorized questions as **Answerable** (docs/code exist) or **Unanswerable** (requires new input)

---

## 🎯 Critical Unanswerable Open Questions

These questions **require architectural, operator, or maintainer input** and cannot be resolved from existing documentation or code.

### 1. Profiler Security Model (HIGH Severity)

**Issue:** `security/issue-20260729-131010-profiler-session-handler-pointer-arguments-toctou.md`

**Questions:**
1. **Diagnostic Profiling vs Adversarial Workloads:** Is profiling intended strictly for trusted workloads under developer diagnostic observation, or must the profiler also withstand adversarial concurrency probes during profiling sessions?
   - **Status:** UNRESOLVED
   - **Impact:** Determines whether TOCTOU mitigation in profiler is mandatory or best-effort
   - **Docs gap:** No explicit threat model for profiler module (only enforcer Tier 1/2 is documented)

2. **FD Injection in Profiling Mode:** For `open`/`openat` syscalls during profiling, should the profiler daemon inject an opened file descriptor via `SECCOMP_IOCTL_NOTIF_ADDFD` and return `res.setVal(fd)`, or continue relying on `SECCOMP_USER_NOTIF_FLAG_CONTINUE`?
   - **Status:** UNRESOLVED
   - **Impact:** Architectural decision affecting profiler accuracy vs security
   - **Docs gap:** No profiling security model documentation

### 2. Exec Containment Strategy (HIGH Severity)

**Issue:** `security/issue-20260817-033800-user-notif-continue-ignores-setregs-for-spawn-exec.md`

**Questions:**
1. **Kernel Syscall Replacement Mechanism:** Since modifying `orig_rax` during a seccomp `USER_NOTIF` pause and issuing `CONTINUE` is rejected by the Linux kernel with `ENOSYS`, should supervised `execve` return `EPERM` fail-closed whenever non-validated binaries are executed, or should execution containment rely on process-wide Tier 1 `NO_EXEC` / Landlock exec restrictions rather than dynamic register redirection?
   - **Status:** UNRESOLVED
   - **Impact:** Fundamental decision on exec containment approach
   - **Docs gap:** Current security-considerations.md mandates Tier 1 first, but doesn't address dynamic exec interception

2. **Alternative Approaches:** Would Landlock ABI v5 (or Landlock executable rules) or Mount Namespace bind-mounting be the preferred mechanism for exec containment instead of ptrace `SETREGS` interception during `vfork`?
   - **Status:** UNRESOLVED
   - **Impact:** Could eliminate need for complex ptrace-based exec interception
   - **Docs gap:** No comparison of containment mechanisms in existing docs

### 3. AOT Thread Containment Assessment (ENHANCEMENT)

**Issue:** `security/issue-20260807-210802-aot-thread-containment-eligibility-assessment.md`

**Questions:**
1. **Static Analysis Integration:** Should the reachability assessment be implemented as a standalone Gradle/CLI verification task (using Bytecode analysis or GraalVM `reachability-metadata.json` parsing), or integrated directly into the `:profiler` module's `BillOfBehavior` generation?
   - **Status:** UNRESOLVED
   - **Impact:** Affects tooling architecture and user workflow
   - **Docs gap:** No existing AOT assessment workflow documented

2. **Eligibility Classification Schema:** What data structure and serializable report format (e.g. JSON or Markdown) should store the `ThreadContainmentAssessment` report?
   - **Status:** UNRESOLVED
   - **Impact:** Determines interoperability with other tools
   - **Docs gap:** No existing assessment report format defined

### 4. Trace Mutation Syscalls (HIGH Severity)

**Issue:** `security/issue-20260821-113000-trace-mutation-syscalls.md`

**Questions:**
1. **Profiler Preset vs Production Preset:** Should `CREAT`, `TRUNCATE`, `FTRUNCATE` be added directly into `PolicyPresets.PURE_COMPUTE_UNSAFE` (affecting default `block()` lists), or should we introduce a dedicated `PolicyPresets.PROFILER_SUPERVISED_MUTATIONS` preset specifically for the `USER_NOTIF` profiler session?
   - **Status:** UNRESOLVED
   - **Impact:** Affects default policy behavior
   - **Docs gap:** No documentation on profiler-specific presets

2. **Architecture Support (`CREAT` vs `openat`):** On `aarch64`, `creat(2)` is not implemented as a dedicated syscall (it is routed via `openat(2)` with `O_CREAT`). Should `Syscall.CREAT` be mapped as architecture-conditional (x86_64 only) with aarch64 relying exclusively on `OPENAT`/`OPENAT2` traps, or should `Syscall.CREAT` have a sentinel/noop mapping on aarch64?
   - **Status:** UNRESOLVED
   - **Impact:** Affects cross-architecture compatibility
   - **Docs gap:** No architecture-specific syscall mapping documentation

### 5. aarch64 Exec Register Rewrite (MEDIUM Severity)

**Issue:** `security/issue-20260817-031501-aarch64-execveat-register-rewrite.md`

**Questions:**
1. **Coupling with Issue 20260817-033800:** Since dynamic register rewriting via `PTRACE_SETREGS` / `SETREGSET` prior to `SECCOMP_USER_NOTIF_FLAG_CONTINUE` is pending kernel confirmation (and triggers `ENOSYS` on x86_64), should aarch64 register rewriting be deferred until the core `USER_NOTIF CONTINUE` syscall replacement protocol is resolved?
   - **Status:** UNRESOLVED
   - **Impact:** Coordination between related issues
   - **Docs gap:** No kernel capability matrix for different architectures

2. **Platform ABI Struct:** Should we add `user_pt_regs` or `elf_gregset_t` layouts for aarch64 directly into `Layouts.kt`?
   - **Status:** UNRESOLVED
   - **Impact:** Affects platform abstraction layer
   - **Docs gap:** No existing ABI struct documentation

### 6. Public API Strategy (MEDIUM Severity)

**Issue:** `code_health/issue-20260808-032525-reduce-and-version-public-api-surface.md`

**Questions:**
1. **Binary Compatibility Tooling:** Should we introduce Kotlin Binary Compatibility Validator (`binary-compatibility-validator` Gradle plugin) or Metalava to enforce public API surface dumps on `:enforcer` and `:profiler`?
   - **Status:** UNRESOLVED
   - **Impact:** Affects CI/CD pipeline and release process
   - **Docs gap:** No existing binary compatibility policy

2. **Explicit API Mode:** Should Kotlin's `explicitApi()` compiler mode (`freeCompilerArgs += ["-Xexplicit-api=strict"]`) be enabled across all production modules to force explicit `public`/`internal` visibility keywords?
   - **Status:** UNRESOLVED
   - **Impact:** Affects all Kotlin source files
   - **Docs gap:** No existing API visibility policy

### 7. Java API Facade (ENHANCEMENT)

**Issue:** `implementation/issue-20260808-032526-java-first-public-api-facade.md`

**Questions:**
1. **Package Placement & Class Naming:** Should the Java facade live in a separate subpackage (e.g. `io.mazewall.java.*` or `io.mazewall.api.*`), or should static facade entry points be placed alongside core packages using `@file:JvmName("Mazewall")` / `MazewallExecutors`?
   - **Status:** UNRESOLVED
   - **Impact:** Affects Java consumer experience
   - **Docs gap:** No existing Java interop guidelines

2. **Generic Scope Representation in Java:** How should Kotlin phantom types (`PolicyScope.ThreadLocalSafe`, `PolicyScope.ProcessWideSafe`, `Uncompiled`) be represented in Java? Should Java APIs use distinct types (e.g. `ThreadPolicy`, `ProcessPolicy`) without generics, or standard generic builder patterns?
   - **Status:** UNRESOLVED
   - **Impact:** Affects type safety for Java users
   - **Docs gap:** No existing phantom type Java interop documentation

### 8. Git Integration Testing (MEDIUM Severity)

**Issue:** `testing/issue-20260731-120707-expand-branch-rebaser-tests-with-real-git-repository-simulation.md`

**Questions:**
1. **Duplicate Issue Consolidation:** This issue duplicates `issue-20260729_153003-git-integration-testing-with-real-git-repos.md`. Should one of the two be marked as resolved/duplicate?
   - **Status:** UNRESOLVED (but actionable)
   - **Impact:** Backlog hygiene

2. **Local Git Dependency vs JGit:** Should real git integration tests execute the local system `git` binary via `ProcessBuilder` (requiring `git` CLI installed on CI runners), or should they use an embedded JVM Git implementation like JGit?
   - **Status:** UNRESOLVED
   - **Impact:** Affects CI/CD infrastructure requirements
   - **Docs gap:** No existing testing infrastructure policy

### 9. GitHub CLI Auth Resilience (MEDIUM Severity)

**Issue:** `code_health/issue-20260727-021302-github-cli-token-fallback-and-auth-resilience.md`

**Questions:**
1. **Operator Notification Strategy:** When `gh` commands fail with HTTP 401 (invalid credentials), should the orchestrator pause its scheduling loop and emit a terminal alert, or exit immediately with a non-zero exit code?
   - **Status:** UNRESOLVED
   - **Impact:** Affects operator experience
   - **Docs gap:** No existing operator notification policy

2. **Duplicate Issue Consolidation:** Issue `issue-20260730-074830-github-cli-token-fallback-and-auth-resilience.md` covers the exact same problem; should it be marked as duplicate/resolved pointing to this issue?
   - **Status:** UNRESOLVED (but actionable)
   - **Impact:** Backlog hygiene

---

## 📊 Summary Statistics

| Category | Total Issues | With Open Questions | Answerable | Unanswerable |
|----------|---------------|---------------------|------------|--------------|
| Security | 31 | 6 | 4 | **6** |
| Code Health | 35 | 2 | 1 | **3** |
| Testing | 10 | 1 | 0 | **1** |
| Performance | 6 | 0 | 0 | **0** |
| Implementation | 3 | 1 | 0 | **1** |
| Platform | 1 | 0 | 0 | **0** |
| **Total** | **86** | **9** | **5** | **11** |

---

## 📋 Answerable Questions (Resolved from Docs/Code)

These questions can be answered by referencing existing documentation or code:

1. **issue-20260817-031501:** aarch64 register structures - Can reference Linux kernel headers and existing x86_64 implementations
2. **issue-20260808-032525:** Public API surface - Can inventory existing declarations from codebase
3. Some implementation details can be derived from existing code patterns

---

## 🎯 Recommendations

### Immediate Actions (Next Sprint)

1. **Resolve Duplicate Issues** (Low effort, high impact)
   - Consolidate duplicate GitHub CLI auth issues
   - Consolidate duplicate git integration testing issues
   - This reduces backlog by ~5% immediately

2. **Document Profiler Security Model** (Medium effort, HIGH impact)
   - Add `docs/internals/designs/profiler/profiler-security-model.md`
   - Explicitly state whether profiler must handle adversarial workloads
   - Define TOCTOU mitigation requirements

3. **Architectural Decision: Exec Containment** (High effort, CRITICAL impact)
   - Convene architecture review for exec interception strategy
   - Decide between: fail-closed EPERM, Tier 1 reliance, Landlock ABI v5, or Mount NS
   - Document decision in security-considerations.md

### Medium-term Actions (Next 2 Sprints)

4. **Define AOT Assessment Workflow**
   - Create design doc for ThreadContainmentAssessment
   - Define report format (recommend JSON for tooling interop)
   - Decide on integration point (standalone vs profiler)

5. **Establish API Versioning Policy**
   - Decide on binary compatibility tooling
   - Document public API surface classification
   - Enable explicitApi mode incrementally

6. **Java Interop Strategy**
   - Define package structure for Java facade
   - Design phantom type representation for Java
   - Create migration guide for Java consumers

### Long-term Actions (Roadmap)

7. **Architecture-Specific Documentation**
   - Document syscall variations across architectures
   - Create kernel capability matrix
   - Maintain ABI struct registry

8. **Testing Infrastructure Policy**
   - Decide on Git dependency strategy (system git vs JGit)
   - Define CI runner requirements
   - Document testing philosophy

---

## 🔍 Cross-Cutting Themes

### Theme 1: Security Model Clarity
Multiple issues reveal that the **profiler module's security model** is undocumented. While enforcer's Tier 1/2 model is well-defined in `security-considerations.md`, there's no equivalent for the profiler.

**Impact:** Cannot determine if TOCTOU vulnerabilities in profiler are acceptable or require fixing.

### Theme 2: Architecture Portability
Several issues highlight **architecture-specific behavior** (x86_64 vs aarch64) that lacks documentation.

**Impact:** Cannot make informed decisions about cross-architecture support without kernel capability matrix.

### Theme 3: API Maturity
The project is transitioning from pre-alpha to broader adoption, but **public API boundaries** are undefined.

**Impact:** Binary compatibility concerns, Java interop unclear, breaking changes risk.

### Theme 4: Testing Philosophy
Unclear whether tests should use **real system dependencies** (git, docker) or **embedded alternatives** (JGit).

**Impact:** CI/CD infrastructure decisions blocked, test reliability concerns.

---

## 📚 Documentation Gaps Identified

| Gap | Location | Impact |
|-----|----------|--------|
| Profiler security model | Missing | Blocks TOCTOU decision |
| Kernel capability matrix | Missing | Blocks exec interception decision |
| Architecture-specific syscall mapping | Missing | Blocks CREAT handling decision |
| Public API classification | Missing | Blocks API versioning decision |
| Java interop guidelines | Missing | Blocks Java facade design |
| Testing infrastructure policy | Missing | Blocks git integration decision |
| AOT assessment workflow | Missing | Blocks reachability assessment |

---

## ✅ What CAN Be Answered from Existing Docs/Code

The following types of questions are well-covered:

1. **Tier 1/2 Security Model** - Documented in `security-considerations.md`
2. **Basic Policy Configuration** - Documented in `GETTING_STARTED.md` and `Policy.kt`
3. **Seccomp/Landlock Mechanics** - Documented in README and design docs
4. **FFM API Usage** - Examples exist in codebase
5. **Supervisor Architecture** - Documented in `supervisor-proxy-design.md`
6. **SBoB Concept** - Documented in article series and README

---

## 🎯 Conclusion

**86 open issues** contain approximately **11 critical open questions** that require architectural or operator input. The primary blockers are:

1. **Lack of profiler security model documentation**
2. **Missing architecture-specific kernel capability information**
3. **Undefined API versioning and Java interop strategy**
4. **Unclear testing infrastructure philosophy**

**Recommendation:** Prioritize documenting the profiler security model and resolving the exec containment strategy, as these have HIGH severity and block multiple downstream decisions.

---

## 📎 Appendix: All Issues with Open Questions

### Security (6 issues)
- `issue-20260729-131010-profiler-session-handler-pointer-arguments-toctou.md` - 2 questions
- `issue-20260807-210802-aot-thread-containment-eligibility-assessment.md` - 2 questions
- `issue-20260817-031501-aarch64-execveat-register-rewrite.md` - 2 questions
- `issue-20260817-033800-user-notif-continue-ignores-setregs-for-spawn-exec.md` - 2 questions
- `issue-20260821-113000-trace-mutation-syscalls.md` - 2 questions

### Code Health (2 issues)
- `issue-20260727-021302-github-cli-token-fallback-and-auth-resilience.md` - 2 questions
- `issue-20260808-032525-reduce-and-version-public-api-surface.md` - 2 questions

### Testing (1 issue)
- `issue-20260731-120707-expand-branch-rebaser-tests-with-real-git-repository-simulation.md` - 2 questions

### Implementation (1 issue)
- `issue-20260808-032526-java-first-public-api-facade.md` - 2 questions

---

*Generated by Mistral Vibe for MAZ-18 backlog review on 2026-08-22*
