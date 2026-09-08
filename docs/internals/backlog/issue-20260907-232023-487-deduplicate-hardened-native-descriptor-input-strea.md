---
title: "Deduplicate hardened native descriptor input stream"
severity: "LOW"
status: "open"
priority: high
dependencies: []
component: "core"
target_modules: []
target_files:
  - "platform/src/main/kotlin/io/mazewall/ffi/networking/NativeDescriptorInputStream.kt"
target_symbols:
  - "NativeDescriptorInputStream"
open_questions: false

paperclip_issue_id: "ef516bc9-b945-4708-a082-fb659e1a34a5"
paperclip_identifier: "MAZ-1244"
---

# 🟡 [Severity: LOW]: Deduplicate hardened native descriptor input stream

**Context:**
SupervisorSocketInputStream and NativeSocketInputStream independently implement the same owned-FD InputStream, EINTR retry, progressive backoff, interruption, and EOF logic. Future fixes can drift across the enforcer and profiler IPC paths.

**Needed:**
1. Extract the shared implementation into platform without changing descriptor ownership; retain thin module-local adapters only if names are needed.

---
<!-- id: issue-20260907-232023-487-deduplicate-hardened-native-descriptor-input-strea  file: issue-20260907-232023-487-deduplicate-hardened-native-descriptor-input-strea.md -->
