---
title: "Prevent cold mmap linkage from aborting strict protection tests"
severity: "HIGH"
status: "open"
priority: high
dependencies: []
component: "enforcer"
target_modules: [":enforcer"]
target_files:
  - "enforcer/src/integrationTest/kotlin/io/mazewall/seccomp/MmapProtectionTest.kt"
target_symbols: ["MmapProtectionTest"]
needs_kernel: true
core_lock: false
effort: "medium"
autonomy: "supervised"
open_questions: false
has_side_effects: true
---

# 🟡 [Severity: HIGH]: Prevent cold mmap linkage from aborting strict protection tests

**Context:**
Default-branch CI run 39147119265 at merged commit 6a966c7fd0f23b4d140adff986f76fe7424bb5b2 fails in enforcer:integrationTest. Retained hs_err_pid1351.log identifies pool-11-thread-1, the pkey_mprotect PROT_EXEC case, anonymousMapping and SyscallInvoker.mmap method-handle linkage, then native-wrapper generation and executable CodeHeap growth. The job log records a 65536-byte executable commit denied with EPERM. This is not established RAM exhaustion, and the partial JUnit skipped entry is not protection evidence. Earlier lambda and errno-renderer bootstrap fixes do not cover this distinct mmap downcall linkage site; broader issue120935-494 remains open.

**Needed:**
1. Reproduce the retained cold mmap linkage path in an owned disposable JVM and isolate setup performed after the unchanged strict Native Image profile is installed.
2. Correct only the protection fixture setup boundary; retain actual executable-mapping denial, pkey_mprotect errno/raw-return/cause assertions, owned mapping cleanup and physical worker retirement. No policy relaxation, broad warmup, skip or automatic retry.
3. Run the whole MmapProtectionTest class and owning native gate, then qualify the exact reviewed correction through the required CI path; a passing unchanged rerun is not a fix.

## Retained evidence and source boundary

- Workflow: [39147119265](https://github.com/Pilleo/mazewall/actions/runs/39147119265), commit `6a966c7fd0f23b4d140adff986f76fe7424bb5b2`. Optional Koog/Spring acceptance and the host unit quality gate passed; the kernel gate failed and publication was skipped.
- Local artifact: `.superpowers/sdd/2026-10-18-ci-cache-key-stability/master-diagnostics-39147119265/enforcer/hs_err_pid1351.log`, SHA-256 `2f2e76b9a943aa3dcff8f9b032245540df7ad4b0ecde1897bb9b2ecbf9319d06`. Lines 39-95 name the restricted worker, method-handle/native-wrapper/CodeHeap chain and `MmapProtectionTest.pkey_mprotect...` through `anonymousMapping`. The retained main-job log lines 5062-5069 establish executable `commit_memory(..., 65536, 1)` with `errno=1`, then the failed task.
- At the failing base `6a966c7f`, the fixture constructs its operation before installation but invokes `anonymousMapping(PROT_READ|PROT_WRITE)` inside that operation after `installOnCurrentThread` (`MmapProtectionTest.kt:451-473` at that revision). The fatal path is this ordinary setup downcall, before the intended `pkey_mprotect(PROT_EXEC)` denial. Existing prelinked lambdas and direct-string errno diagnostics address different bootstrap sites and must remain intact.
- Read-only dependencies: `platform/.../SyscallInvoker.kt:96-108`, `platform/.../RealNativeEngine.kt:243-256,315-324`, the thread-local HotSpot limitation in `docs/internals/designs/enforcer/containment-design.md:209-216`, and broader open [issue120935-494](issue-20261015-120935-494-diagnose-executable-mapping-eperm-during-enforcer-.md). The new item isolates this concrete fixture path rather than claiming to resolve every historical FutureTask/VarHandle/JNI case.
- The partial XML reports one skipped pkey test because the executor aborted. Zero XML failures must not be presented as a successful or deliberately skipped native protection result.
- Actual main and optional cache requested/saved primary keys match on this failed workflow. That establishes the cache binding only; it is not native acceptance or successful publication, and the caches restored older fallback archives on the first run.

## Limits and safety

The retained fatal report establishes the observed failing path; local checks below qualify only the fixture correction on the configured environment. No fresh fatal-JVM exhaustion reproduction, universal HotSpot/JNI safety, general call-graph enforcement or exhaustive exceptional-path fault injection is claimed. Production FFM layouts/downcalls, BPF design, profiles, permission handling and enforcement remain unchanged/read-only unless the operator separately approves a broader change. Do not increase VM limits, enable executable mappings, turn EPERM into success, add a skip, delete caches or automatically rerun the failing gate as a remedy. Preserve all existing operator-owned ADK edits.

## Assigned fixture correction

The operator approved the single-task plan `docs/superpowers/plans/2026-10-18-mmap-protection-setup.md` and execution on `fix/mmap-protection-setup`, based on `6a966c7fd0f23b4d140adff986f76fe7424bb5b2`. Only the existing `MmapProtectionTest.kt` source file is assigned: prepare ordinary read/write mappings on the caller, preserve actual strict protection attempts on workers and release mappings only after executor retirement. A deterministic compiled-code regression must fail on the old placement before the correction; actual native class/suite and the full gate remain required.

ADK blast-radius evidence is PARTIAL/INDEX_MISSING; direct mapping-helper/call-site inspection is recorded instead of complete index proof. All pending ADK/cache work is preserved and excluded from this correction's commits. No production/native-binding/BPF change, remote rerun, publication, merge or automatic archive is authorized by this assignment.

## Local implementation and acceptance

Source correction `76d93d6daeff0ce824f5202dbf55c863ebd39200` and whitespace-only follow-up `2beab8eec37e7a0a93794ebe9cddf65b8d5f74d5` modify only the assigned integration-test file. Both ordinary mapping acquisitions now occur on the caller; executor creation and final close stay inside existing mapping ownership, so unmap follows physical retirement. Actual strict protection syscalls, result/cause/cleanup assertions, direct executable-mmap operation, existing helpers and production code are unchanged.

- **Meaningful RED:** the new compiled-class guard failed with one assertion identifying both missing caller allocations and both forbidden generated-operation allocations. It uses the JDK class-file API, not source grep, and asserts the literal two-method inventory is found. This is unsafe-placement proof, not a forced repeat of the fatal CodeHeap allocation.
- **Focused GREEN:** the same regression passes with zero skips. After the formatting correction, `:enforcer:ktlintIntegrationTestSourceSetCheck` and that focused regression both pass.
- **Actual native class:** `./scripts/run_containerized_tests.sh :enforcer:integrationTest --tests io.mazewall.seccomp.MmapProtectionTest --max-workers=1` exits 0: 18 tests, no failures/errors/skips, including all three real protection cases and existing modeled controls.
- **Owning native suite:** `./scripts/run_containerized_tests.sh :enforcer:integrationTest --max-workers=1` exits 0: 196 tests, no failures/errors, two CET-related skips. One skip XML has no reason text; the other names the pre-enabled SHSTK runtime prerequisite. This change adds no skip logic, but an unchanged-suite baseline was not measured and CET hardware/runtime behavior remains unqualified here.
- **Full checkpoint:** the initial full build failed seven call-chain formatting findings in the new guard after kernelCheck completed; that failed attempt is retained. After the reviewed whitespace-only correction, `LC_ALL=C ./gradlew build --console=plain --max-workers=1` exits 0 in 6m8s: 227 tasks, 31 executed and 196 up-to-date. The amended run actually executes enforcer integration/fresh-JVM tasks and root kernelCheck; it is not an unchanged retry or waived failure.
- **Review:** independent task review approves spec/quality with no Critical/Important findings; scoped re-review confirms all seven formatting findings addressed and no new breakage. This paragraph records task review only; whole-branch review and exact staged documentation checks are separate controller gates.

Evidence is retained in `.superpowers/sdd/2026-10-18-mmap-protection-setup/`: `task-1-report.md`, `task-1-review.md`, `task-1-format-review.md`, phase argv/log/exit/XML records, `full-build.*`, `full-build-after-format.*` and focused acceptance receipts. Existing toolchain/native diagnostic noise is unsuppressed. The guard is a direct-call placement check for the current compiler, not transitive or arbitrary future compiler-lowering proof.

**Integration gates:** final aggregate review, operator-controlled PR/remote CI qualification and acceptance. The previously failed default-branch run remains failed; no new remote run or publication has been performed. Keep this item and broader issue120935-494 open until their respective acceptance requirements are satisfied; do not infer universal runtime support from the local passing gate.

---
<!-- id: issue-20261018-173748-642-prevent-cold-mmap-linkage-from-aborting-strict-pro  file: issue-20261018-173748-642-prevent-cold-mmap-linkage-from-aborting-strict-pro.md -->
