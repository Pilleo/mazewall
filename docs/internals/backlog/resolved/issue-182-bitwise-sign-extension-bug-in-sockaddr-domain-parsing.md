---
title: "Bitwise Sign-Extension Bug in `sockaddr` Domain Parsing"
severity: "HIGH"
status: "resolved"
priority: high
paperclip_issue_id: 0481cf09-7935-4834-9e12-62d8c33ea995
paperclip_identifier: MAZ-357
---

# 🔴 [Severity: HIGH]: Bitwise Sign-Extension Bug in `sockaddr` Domain Parsing
**Context:** In `SupervisorSessionHandler.kt`, `connectSocketInSupervisor` extracts the network domain from `sockaddrBytes` via `sockaddrBytes[0].toInt() or (sockaddrBytes[1].toInt() shl 8)`. Because Kotlin's `Byte.toInt()` does sign extension, domains >= 128 (where the first byte is `0x80` or greater) are sign-extended to a negative 32-bit integer. This negative domain is then passed to the kernel's `socket()` syscall, failing with `EINVAL`.
**Needed:** Use bitwise AND `0xFF` during extraction to mask out sign-extension. The fix should be: `(sockaddrBytes[0].toInt() and 0xFF) or ((sockaddrBytes[1].toInt() and 0xFF) shl 8)`.
