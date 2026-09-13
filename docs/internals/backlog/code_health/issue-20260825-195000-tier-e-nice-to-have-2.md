---
title: "Add SO_RCVTIMEO to daemon session socket (bounded recv blocking)"
severity: "LOW"
status: "open"
priority: low
component: "tier-e"
target_modules:
  - "profiler"
target_files:
  - "profiler/src/main/kotlin/io/mazewall/profiler/tierE/daemon/TierEClientSession.kt"
  - "profiler/src/main/kotlin/io/mazewall/profiler/tierE/daemon/TierEKotlinDaemon.kt"
effort: "small"
autonomy: "supervised"
open_questions: false
dependencies: []
paperclip_issue_id: "e68a1beb-6f8b-4015-93d1-354534bbabb3"
paperclip_identifier: "MAZ-1387"
---

# 🟢 [Severity: LOW]: Add SO_RCVTIMEO to daemon session socket

**Context:** A controller that connects but never sends data blocks the session thread's
recv() indefinitely, holding the single-session slot and preventing any other controller
from connecting.

**Needed:** Set the equivalent Java socket receive timeout to 30 s after accept().
On timeout, `readUTF()` raises `SocketTimeoutException`; the session treats it as
an idle event, rechecks `stopRequested`, and loops. No `PosixFfi` exists in the
current profiler implementation, so no native socket dependency is introduced.
