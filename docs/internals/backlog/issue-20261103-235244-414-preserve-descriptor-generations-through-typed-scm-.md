---
title: "Preserve descriptor generations through typed SCM_RIGHTS transmission"
severity: "HIGH"
status: "open"
priority: high
dependencies: []
component: "core"
target_modules: [":platform"]
target_files:
  - "platform/src/main/kotlin/io/mazewall/platform/socket/SocketManager.kt"
target_symbols:
  - "sendDescriptor"
open_questions: false
---

# 🟡 [Severity: HIGH]: Preserve descriptor generations through typed SCM_RIGHTS transmission

**Context:**
Repository-wide review, 2026-11-04: [PLAT-03](../reviews/2026-11-04-repository-review-findings.md#plat-03). The source review and reproductions below are not release qualification or implementation approval. Historical resolved issues remain archived; this is their newly scoped residual where applicable.

**P1 · capability/descriptor safety · confirmed source defect · high confidence; native transfer not reproduced · shared transport, supervisor and profiler; Portal callers need ownership-aware qualification.**

- **Evidence:** `platform/src/main/kotlin/io/mazewall/platform/socket/SocketManager.kt:98–103` converts both typed tokens into integers. `platform/src/main/kotlin/io/mazewall/ffi/networking/SupervisorSocketUtils.kt:139–170` places `fdToSend` in ancillary data without checking its original token and remints the socket with `FileDescriptor.unixSocket(socketFd)` on each attempt. `platform/src/main/kotlin/io/mazewall/platform/fd/FileDescriptor.kt:340–343,404–420` borrows the **current** generation; `169` exposes the integer even after close. The old token's generation is never consulted by this send path.
- **Trigger:** retain an Open-typed token A, close its generation, and acquire B reusing the same integer; then call the typed `sendDescriptor` with A. Kotlin leaves the old Open-typed variable usable, which is precisely why `isLiveForIo` exists. For the payload, the message carries B's integer. For the socket, reminting borrows B's generation and the real networking liveness check can accept it. No simultaneous race is required for this stale-token case; close/reuse between attempts adds another exposure.
- **Effect:** the receiver can receive a different resource from the caller's authorized token, or a transfer can use a different socket. This is a capability-identity failure; no claim is made that a guest can currently force that schedule. Production typed callers include `enforcer/src/main/kotlin/io/mazewall/enforcer/internal/supervisor/SocketHelperProcess.kt:65` and `enforcer/src/main/kotlin/io/mazewall/enforcer/internal/supervisor/SocketHelperMain.kt:39,81–86`. Portal's `Capability.borrowUntil` supplies additional owner protection and must be retained, not assumed absent.
- **Smallest correction:** preserve both tokens into the send implementation; validate their original identities before every attempt and retain their existing owner/borrow protection through dispatch. Where close can overlap sending, serialize that operation with close or borrow an owned duplicate. Keep genuinely raw compatibility entrypoints explicitly raw rather than routing typed operations through them.
- **Regression:** fake-engine stale payload and stale socket cases with distinct generations of one integer must dispatch zero sends; deterministic close/reuse during retry; a successful real SCM_RIGHTS control with owned descriptors. A mere equality check on raw integers is insufficient.
- **Tracking:** **expanded gap** in resolved `issue-20260910-225751-359-validate-native-transfers-and-descriptor-acquisiti.md` (RH-03/04) and the typed-boundary intent of resolved `issue-20260907-080829-thread-filedescriptor-through-platform-downcalls-and-classif.md`; no existing issue found for this exact send-side identity loss. Historical EINTR send issues are fixed and unrelated.
- **Dependency:** shared transport correction precedes caller-specific helper/capability tests; do not patch each helper with fresh integer wrapping.

**Needed:**
1. Implement only the bounded correction described above, first reproducing its trigger with the stated negative and positive controls. Preserve all ownership, failure and compatibility invariants; run owning focused checks and the project completion gate before closure. Keep native, hosted and published-artifact qualification separate from source-only or isolated evidence.

---
<!-- id: issue-20261103-235244-414-preserve-descriptor-generations-through-typed-scm-  file: issue-20261103-235244-414-preserve-descriptor-generations-through-typed-scm-.md -->
