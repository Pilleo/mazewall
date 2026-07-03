---
title: "Bitwise Sign-Extension Bug in `sockaddr` Domain Parsing"
severity: "HIGH"
status: "resolved"
---

# 🔴 [Severity: MEDIUM]: Bitwise Sign-Extension Bug in `sockaddr` Domain Parsing

*   **Dimension:** FFM ABI & Memory Safety (The Low-Level View)
*   **Target Area:** `enforcer/src/main/kotlin/io/mazewall/enforcer/supervisor/SupervisorSessionHandler.kt`
*   **Failure Hypothesis:** When parsing the address family (domain) from `sockaddrBytes`, the code incorrectly performs bitwise operations on signed bytes, resulting in invalid domain integers for families >= 128.
*   **Context & Proof:** In `connectSocketInSupervisor`, the domain is extracted using: `sockaddrBytes[0].toInt() or (sockaddrBytes[1].toInt() shl 8)`. In Kotlin, `Byte.toInt()` performs sign extension. If the first byte is `0x80` or higher, it will be sign-extended to a negative integer (e.g., `0xFFFFFF80`). This corrupted integer is then passed to `LinuxNative.networking.socket(domain, 1, 0)`, which will fail with `EINVAL` (Invalid argument) because the kernel does not recognize negative domains, preventing connections to legitimate address families that map to values >= 128 (e.g., custom local AF values or specific vendor AF implementations).
*   **Recommendation:** Use bitwise AND to mask out the sign extension: `(sockaddrBytes[0].toInt() and 0xFF) or ((sockaddrBytes[1].toInt() and 0xFF) shl 8)`.
