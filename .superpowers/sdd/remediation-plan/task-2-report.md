# Task 2 — canonical profiler evidence

## Result

Implemented canonical open-flag evidence through parsing, observation compilation, collector merging, behavioral identity, copying, and BoB JSON persistence.

- Missing `OPENAT2` structures or `open_how.flags` now produce explicit `Incomplete(0, {"open_how.flags"})` evidence.
- Legacy `OPEN`/`OPENAT` factory events do not silently turn absent flag operands into readable opens.
- `BobCompiler` rejects absent or incomplete evidence instead of compiling a permissive read rule.
- Collector merge keys retain typed evidence, including unknown symbolic flags.
- `TraceEvent.Open.copy` does not carry evidence across syscall-kind changes; changing the numeric flags creates complete replacement evidence.
- BoB stack-profile JSON has additive evidence fields and remains backward-compatible with older JSON.

## Verification

```text
./gradlew --no-daemon :profiler:test --tests '*TraceEventTest' --tests '*StraceLogParserTest' --tests '*BobCompilerTest' --tests '*CollectorHybridTest'
BUILD SUCCESSFUL
```

The focused suite completed successfully after the final changes. No `tools/` files were modified.
