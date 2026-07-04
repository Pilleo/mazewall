# Backlog Deduplication and Resolution Report

The goal of this patch was to clean up the `docs/internals/backlog` directory, which had become polluted with duplicated entries and obsolete issues. Each removed or moved file is listed below with the motivation for its modification.

## 1. Resolved & Obsolete Issues (Moved to `resolved/`)
The following issues were identified as referencing obsolete/deleted source targets (e.g. `ContainerStateRegistry` has been replaced, `SockFProg` was removed/renamed) or they were explicitly marked internally as WONTFIX/completed. They were moved to `docs/internals/backlog/resolved/` and their status changed from `open` to `resolved`:

- `issue-058-sbobparser-syntactic-pruning-inaccuracy.md`: SbobParser no longer exists as a target file.
- `issue-072-contract-based-invariant-validation.md`: Platform.kt validation constraints were either met or obsolete.
- `issue-073-delegated-properties-for-thread-local-sandbox-state.md`: `ContainerStateRegistry` was refactored and no longer exists.
- `issue-102-permanent-thread-pool-contamination-classloader-leaks-and-st.md`: Explicitly tagged as `🟢 [WONTFIX]` inside the file.
- `issue-103-containedexecutors-thread-local-state-persistence-and-poison.md`: Explicitly tagged as `🟢 [WONTFIX]` inside the file.
- `issue-132-missing-bpf-instruction-limit-validation-in-newsockfprog.md`: `SockFProg` no longer exists as a target.
- `issue-172-memory-segment-scopes-and-lifetimes-re-evaluation.md`: The file itself concluded with the recommendation: "FFM scoping here looks solid", making it a non-issue.

## 2. Duplicate Issues (Deleted)
Sequence matching was run on all open issue titles. The script identified numerous exact copies (similarity > 85%). For each duplicate set, one canonical file was retained while the redundant copies were deleted using `git rm`.

The following files were deleted because their exact or nearly identical counterpart was preserved:

**Performance Duplicates Removed:**
- `issue-084-potential-race-condition-in-async-io-thread-shutdown.md` (Duplicate of `issue-140-...`)
- `issue-088-suboptimal-bpf-ret-instruction-placement-in-emitlinearscan.md` (Duplicate of `issue-116-...`)
- `issue-089-missing-extensibility-in-exception-message-parsing.md` (Duplicate of `issue-128-...`)
- `issue-090-unhandled-ocloexec-omission-on-profiler-unix-sockets.md` (Duplicate of `issue-133-...`)
- `issue-120-native-memory-leak-in-containedexecutorswrap-under-high-conc.md` (Duplicate of `issue-118-...`)
- `issue-126-memory-segment-lifetime-leak-in-async-profiler-events.md` (Duplicate of `issue-092-...`)
- `issue-136-sbobparser-production-crashes-due-to-syntactic-subpath-pruni.md` (Duplicate of `issue-101-...`)
- `issue-137-overly-broad-catch-block-in-profilerdaemonreactorloop.md` (Duplicate of `issue-094-...`)
- `issue-174-uncaught-exceptions-in-containedexecutorwrapperkt-during-fil.md` (Duplicate of `issue-180-...`)

**Security Duplicates Removed:**
- `issue-085-uncaught-native-exceptions-escaping-bpf-installation.md` (Duplicate of `issue-135-...`)
- `issue-098-unhandled-signal-mask-inheritance-in-containedexecutors.md` (Duplicate of `issue-127-...`)
- `issue-099-toctou-in-usernotif-argument-dereferencing.md` (Duplicate of `issue-129-...`)
- `issue-100-missing-return-value-check-for-seccompnotifresp-ack.md` (Duplicate of `issue-131-...`)
- `issue-111-missing-bpfprogramstatus-and-bpflabel-type-safety.md` (Duplicate of `issue-141-...`)
- `issue-117-seccompinstallationstate-partial-failure-leaves-thread-unpri.md` (Duplicate of `issue-119-...`)
- `issue-134-toctou-in-path-normalization-under-multi-threaded-io.md` (Duplicate of `issue-097-...`)
- `issue-138-unhandled-signal-interruptions-eintr-during-seccomp-filter-i.md` (Duplicate of `issue-086-...`)
- `issue-176-toctou-in-path-normalization-pathnormalizerkt.md` (Duplicate of `issue-181-...`)
- `issue-179-uncaught-native-exceptions-escaping-landlock-installation.md` (Duplicate of `issue-135-...`)

**Testing Duplicates Removed:**
- `issue-013-manual-ffm-layout-maintenance-and-abi-drift-risk.md` (Duplicate of `issue-061-...`)
- `issue-015-algebraic-policy-composition-semigroupmonoid.md` (Duplicate of `issue-012-...`)
- `issue-083-unhandled-ioctl-fallbacks-during-legacy-jvm-syscall-tracing.md` (Duplicate of `issue-139-...`)
- `issue-096-missing-bpf-instruction-limit-validation-in-newsockfprog.md` (Duplicate of `issue-132-...`)
- `issue-130-unhandled-endianness-in-processvmreadv-socket-message-tracin.md` (Duplicate of `issue-095-...`)

*(Note: Unique, non-duplicate issues like `issue-183`, `issue-146`, `issue-034`, and `issue-035` were strictly preserved based on careful manual review).*
