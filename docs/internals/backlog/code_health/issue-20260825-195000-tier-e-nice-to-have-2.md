---
title: "Add SO_RCVTIMEO to daemon session socket (bounded recv blocking)"
severity: "LOW"
status: "open"
priority: low
component: "tier-e"
target_modules:
  - "tier-e-proto"
target_files:
  - "tier-e-proto/src/main/kotlin/io/mazewall/tierE/ffi/PosixFfi.kt"
  - "tier-e-proto/src/main/kotlin/io/mazewall/tierE/daemon/TierEDaemon.kt"
effort: "small"
autonomy: "supervised"
open_questions: false
dependencies: []
paperclip_issue_id: 47ac22e7-88dd-45a7-a669-31c08f9c69d2
---

# 🟢 [Severity: LOW]: Add SO_RCVTIMEO to daemon session socket

**Context:** A controller that connects but never sends data blocks the session thread's
recv() indefinitely, holding the single-session slot and preventing any other controller
from connecting.

**Needed:** Set SO_RCVTIMEO (e.g., 30 s) via setsockopt in PosixFfi after accept().
On timeout, recv returns -1/EAGAIN → session thread checks stopRequested and loops,
allowing SHUTDOWN signals to be processed even while a client is idle.
