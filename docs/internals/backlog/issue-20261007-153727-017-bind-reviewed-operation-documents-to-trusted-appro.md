---
title: "Bind reviewed operation documents to trusted approval and exact configured identity"
severity: "HIGH"
status: "open"
priority: high
dependencies: []
component: "core"
target_modules: [enforcer]
target_files:
  - "enforcer/src/main/kotlin/io/mazewall/enforcer/api/OperationBinding.kt"
  - "enforcer/src/main/kotlin/io/mazewall/enforcer/api/OperationPolicyDocument.kt"
  - "enforcer/src/main/kotlin/io/mazewall/enforcer/internal/artifact/OperationDocumentJson.kt"
  - "enforcer/src/main/kotlin/io/mazewall/enforcer/api/execution/ManagedRuntime.kt"
  - "enforcer/src/main/kotlin/io/mazewall/enforcer/api/execution/InProcessDomain.kt"
  - "enforcer/src/test/kotlin/io/mazewall/ReviewedOperationArtifactTest.kt"
  - "enforcer/src/test/kotlin/io/mazewall/execution/OwnedRuntimeTest.kt"
  - "enforcer/api/enforcer.api"
  - "enforcer/README.md"
  - "docs/internals/designs/api/enforcer-public-api.md"
target_symbols: [OperationBinding, OperationPolicyDocument, ReviewedOperationArtifact, TrustedPolicyReview]
open_questions: false
---

# 🟡 [Severity: HIGH]: Bind reviewed operation documents to trusted approval and exact configured identity

**Context:**
Approved C6 needs strict reviewed operation envelopes over canonical policy artifacts. Validate exact host-configured operation/adapter/implementation/resource generation, retain coverage and specifically reviewed exceptions, and require external trusted approval matching canonical document bytes. JSON review labels or model-supplied identifiers cannot grant authority.

**Needed:**
1. Test forged approval, stale identity/generation and missing coverage exceptions before implementing reviewed operation binding.

## Implementation and verification evidence

Versioned operation envelopes retain a canonical policy, fixed adapter identity, implementation digest, configured resource generation, missing-coverage facets and specifically reasoned review exceptions. Collections are immutable. Adapter schemas distinguish callable entry points, Spring bean/class/method descriptors and Koog tool/contract identifiers without framework imports in core.

Trusted approval is external operator configuration, matching the canonical document SHA-256 and authorized exception facets. A JSON approval flag, self-asserted reviewer or request/model identifier cannot produce an approved value. Loading validates the exact host-configured binding, including overload/resource generation. Digest identity is not cryptographic proof of reviewer trust; operator configuration/storage remains the trust owner.

The runtime can prepare an approved worker artifact, validates its runtime profile, uses the existing captured installer/compiler and retains binding on the domain. This validates configured identities; actual implementation/resource resolution and descriptor-generation proof remain adapter/C4 responsibilities.

The initial API baseline was missing (`run-2a357b767b52`). Five approval/schema/coverage/identity cases and an owned preparation case passed (`run-574942d4d352`), including forged digest, unauthorized exception, omitted partial-coverage review, stale generation, changed member and document approval-field rejection. Full host/profiler/ABI/quality gates passed after replacing a magic digest length with a named constant; focused final checks are `run-8d69899da5a3`.

One shared `./gradlew build` checkpoint passed in 5m42s, 265 tasks (86 executed, 179 up-to-date), `run-b303371e7eee`. This metadata/validation slice does not change native installation behavior, so no additional duplicate full kernel/container invocation was run. Existing native suites ran in the merge graph. The issue stays open for repository review.

---
<!-- id: issue-20261007-153727-017-bind-reviewed-operation-documents-to-trusted-appro  file: issue-20261007-153727-017-bind-reviewed-operation-documents-to-trusted-appro.md -->
