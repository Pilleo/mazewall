# Platform module guidance

`:platform` owns shared syscall metadata, FFM layouts, native-engine abstractions, and typed native values.

- Treat `Syscall.kt` and `Arch.kt` as global locks. Add a syscall for every supported architecture with tests; do not make drive-by edits.
- Match `sock_filter` exactly: `code`/`jt`/`jf`/`k` use 16/8/8/32 bits. Do not widen `k` or a C `int` to `JAVA_LONG`; keep layouts aligned with the x86_64 and aarch64 ABIs in `Layouts.kt`.
- Keep raw FFM (`MemorySegment`, layouts, downcalls) behind `NativeEngine` and `io.mazewall.ffi`; preserve the ArchUnit isolation boundary.
- Use confined arenas with `Arena.ofConfined().use { }`. Capture `errno` immediately after a native call and never share confined segments across threads.
- Do not block JVM coordination syscalls. See `enforcer/AGENTS.md` for the complete invariant list.
- Before changing layouts or downcalls, read `.agents/skills/ffm_safety/SKILL.md` and `docs/internals/designs/enforcer/containment-design.md`.

Verification:

- `./gradlew :platform:test`
- `./gradlew :platform:check`
