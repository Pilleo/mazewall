# eBPF Event Field Value Whitespace Preservation

**Source:** Codex PR review comment 3796525664  
**Priority:** P2  
**Status:** Backlogged  
**Created:** 2026-08-20

## Problem

Recorded eBPF events are tokenized on every whitespace boundary with no quoting or escaping support, so a valid path such as `path=/tmp/My File` is compiled as `/tmp/My` while the remaining token is discarded. Profiles for files or endpoints containing spaces consequently produce incorrect Bills of Behavior and enforcement paths.

## Impact

Files or endpoints with spaces in their paths will have incomplete or incorrect path information in the Bill of Behavior, potentially leading to:
- Missing path entries
- Incorrect path parsing
- Enforcement policies that don't match the actual filesystem operations

## Solution

Define and parse an escaped or length-delimited event format instead of splitting raw values on whitespace. This could involve:
- Using quoted strings in eBPF event recording
- Using a length-prefixed format
- Using a different delimiter that doesn't appear in paths
- Implementing proper escaping for whitespace in path values

## Related Files

- `profiler/src/main/kotlin/io/mazewall/profiler/ebpf/*` - eBPF event parsing
- Event recording format in eBPF collectors

## Notes

This is a data integrity issue in the profiler's event collection, not a security bypass. However, it can lead to incomplete Bills of Behavior being marked as complete, which could be accepted by `toPolicy()` without `allowIncomplete=true` if not properly guarded.
