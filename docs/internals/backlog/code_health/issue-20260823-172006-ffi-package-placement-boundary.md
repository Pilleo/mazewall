---
title: "FFM Package Placement Boundary: Document or Enforce platform-vs-enforcer ffi Split"
severity: "LOW"
status: "open"
priority: low
component: "enforcer"
target_modules:
  - ":platform"
  - ":enforcer"
target_files:
  - "docs/internals/designs/core/architectural-map.md"
  - ".agents"
effort: "small"
autonomy: "autonomous"
open_questions: false
dependencies: []
---

# 🟡 [Severity: LOW]: FFM Package Placement Boundary

**Context:** Raw-memory/FFM code lives in BOTH `platform/src/main/kotlin/io/mazewall/ffi/**`
(NativeConstants, LinuxNative, RealNativeEngine, memory segments) and
`enforcer/src/main/kotlin/io/mazewall/ffi/**` (memory wrappers like NativeArena usage sites,
networking such as SupervisorSeccompNotifInstaller). The architectural map mandates "all raw
memory/FFM/Unsafe manipulations isolated to `io.mazewall.ffi`" but does not state which MODULE owns
what, why supervisor socket/msghdr FFM lives in :enforcer while LinuxNative lives in :platform, and
no ArchUnit rule enforces the placement — so drift accumulates silently (new FFM code can appear in
any package).

**Needed:**
1. Write the rationale into `architectural-map.md`: platform owns generic ABI constants/engine +
   memory primitives; enforcer owns domain-specific structures (seccomp notif msghdr, sock_fprog
   wiring) that depend on enforcer types.
2. Add ArchUnit rules: (a) classes touching `java.lang.foreign.*` must reside in `io.mazewall.ffi..`;
   (b) `io.mazewall.ffi..` in :enforcer must not be accessed from :platform (module direction).
3. Fix any current violations the rules surface (move or justify-and-whitelist explicitly).

