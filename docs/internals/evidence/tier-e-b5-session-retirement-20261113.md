# B5 shared session-to-pin retirement — bounded evidence, 2026-11-13

## Root acceptance

Root accepted the shared session-to-pin retirement increment with fullsession001 as **ACCEPT_SCOPED_SHARED_SESSION_PIN_RETIREMENT_WITH_FULL_GATE**. This is bounded to the two-file increment on top of commit `186aee7c7c81c73074be50201d9e1f3e6b8a7446`; it closes no issue and does not complete B5/D4.

## Fullsession001

Fullsession001 manifest SHA-256: `5a36325f2b05dd574650505b0bee5c9d6d3531888bcc029a48935a5ec55eda03`. Source manifest SHA-256: `061a15dfb27f8585021b8d973ded531bb5478537c965af4dcd687bc95e77fe06`. Gradle, pipeline, wrapper, and container exited 0, and physical retirement passed. The run took 39m18s across 248 tasks (108 executed, 140 up-to-date), with 19 outer Test tasks. Its 694 XML files record 4,705 invocations, 45 skips, zero failures, and zero errors. Unit coverage was 81,898/99,787; combined coverage was 82,325/99,787.

The run recorded 61 fresh child executions, which establish freshness and identity only; no per-case or class-probe claim is made. Only `:enforcer:integrationTestFreshJvm` used the nondefault 15-second setting; the default remains five seconds and full-run propagation was `NOT_SAMPLED`. The 45 skipped invocations remain unqualified, and original cold 20+20 was not established. Three profiler socket and three suppressed directory `ENOSYS` exceptions leave issue 752 retirement `Unknown`.

## Shared retirement behavior and boundaries

The increment factors a shared concrete session-to-pins retirement primitive used by existing B4 and Live cleanup paths. Live native retirement remains first. When the same session retirement is blocked in flight, cleanup stays counted and retains helper pins and original resources until retirement settles. A thrown retirement failure remains sticky and is not replayed; retry guards were not relaxed.

Tests use genuine session objects and socket FDs, with synthetic relay-protocol identity. They do not establish OS control of a helper PID, helper-process readiness/retirement/lineage, or a new production attachment/startup path. Live/B6 activation remains closed. Exported API and native ABI are unchanged; no dependency was added.

Earlier RED mixed-test failure/lint failure and GREEN001 lint failure remain failed history; fullsession001 does not relabel them. The previous 19-path increment is committed as `186aee7c7c81c73074be50201d9e1f3e6b8a7446`; these two source/test files remain unstaged and uncommitted. No issue closure or broader qualification follows.

LOCALONLY identifiers: fullsession001 manifest SHA-256 `5a36325f2b05dd574650505b0bee5c9d6d3531888bcc029a48935a5ec55eda03`; source manifest SHA-256 `061a15dfb27f8585021b8d973ded531bb5478537c965af4dcd687bc95e77fe06`. These are local audit identifiers, not portable proof by themselves.

See the tracked [open issue 842](../backlog/issue-20261108-141254-842-b5-d4-bind-captured-engine-confined-helper-authori.md) and [containment design](../designs/enforcer/containment-design.md) for remaining work.
