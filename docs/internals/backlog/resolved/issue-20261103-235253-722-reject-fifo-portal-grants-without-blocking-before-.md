---
title: "Reject FIFO Portal grants without blocking before file-type validation"
severity: "MEDIUM"
status: "resolved"
priority: medium
dependencies: []
component: "portal-runtime"
target_modules: [":portals:runtime"]
target_files:
  - "portals/runtime/src/main/kotlin/io/mazewall/portal/PortalChannel.kt"
target_symbols:
  - "openGrantedRead"
open_questions: false
---

# 🟡 [Severity: MEDIUM]: Reject FIFO Portal grants without blocking before file-type validation

**Context:**
Repository-wide review, 2026-11-04: [PRT-03](../../reviews/2026-11-04-repository-review-findings.md#prt-03). The source review and reproductions below are not release qualification or implementation approval. Historical resolved issues remain archived; this is their newly scoped residual where applicable.

**P2 · liveness/input validation · NEW · high source/kernel-contract confidence; not natively reproduced · release boundary: Portal file grants**

**Trigger:** An approved directory contains a FIFO with no writer, and the host calls `openReadOnly(rootId, "fifo")`. This can arise from externally populated input directories; it does not require a symlink escape or an invalid root ID.

**Evidence:** `ProcessBroker.kt:203–212` enters `openGrantedRead` without a grant-acquisition deadline. `PortalChannel.kt:224–245` calls `openat2` with `O_RDONLY | O_CLOEXEC`, then checks regular-file status only after successful open (`258–262`). Linux blocking read-only FIFO open waits for a writer: <https://man7.org/linux/man-pages/man2/open.2.html>. `RESOLVE_BENEATH` constrains resolution; it does not turn this open into a nonblocking operation.

**Effect:** The promised rejection of nonregular input never runs while no writer arrives. The broker caller can remain in native open indefinitely, outside the RPC admission/deadline/retirement machinery. Existing directory rejection is not a FIFO liveness control.

**Smallest complete correction:** Make candidate acquisition nonblocking for FIFOs (and retain exact-descriptor regular-file validation and all existing traversal/symlink checks). If adopting an O_PATH-first approach instead, acquire the eventual readable FD from the pinned object, not a racy second lookup of the original relative path. A path-only precheck is insufficient.

**Regression:** In an isolated bounded fixture, create a FIFO under a real approved root, leave it without a writer, and require prompt rejection with no retained root/grant descriptors. Include ordinary-file success, directory rejection, and existing symlink/traversal controls. A blocking original fixture must have independently owned cleanup so a failing test cannot hang the suite. Tests currently inspected: `OpenGrantedReadTest.kt` and `PortalGrantRootsTest.kt`; neither covers a FIFO.

**Mapping/dependencies:** NEW grant-specific gap. `resolved/issue-20260823-121000-process-portal-openat2-granted-fd.md` fixes resolution, not FIFO opening; `resolved/issue-124-missing-support-for-opath-and-ocloexec-in-landlock-fallback.md` documents the analogous native trap in a different path. P08 (`issue-20261103-090026-755-make-granted-portal-file-inputs-convenient-and-own.md:48–53`) depends on existing nonregular-file rejection and should inherit this regression before expanding grant convenience.

**Needed:**
1. Implement only the bounded correction described above, first reproducing its trigger with the stated negative and positive controls. Preserve all ownership, failure and compatibility invariants; run owning focused checks and the project completion gate before closure. Keep native, hosted and published-artifact qualification separate from source-only or isolated evidence.

## Resolution Evidence (2026-11-21)
- Updated `openGrantedRead` in `PortalChannel.kt` to open with `O_NONBLOCK` and clear `O_NONBLOCK` via `LinuxNative.raw.fcntl` only after verifying `Files.isRegularFile` on `/proc/self/fd/<fd>`, with writerless FIFO and regular-file controls in `OpenGrantedReadTest`.

---
<!-- id: issue-20261103-235253-722-reject-fifo-portal-grants-without-blocking-before-  file: issue-20261103-235253-722-reject-fifo-portal-grants-without-blocking-before-.md -->
