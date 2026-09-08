# R-0001 · MAZ-1175: Typed Landlock installation state machine

- status: draft
- date: 2026-09-07
- tags: —
- context_files: enforcer/src/main/kotlin/io/mazewall/landlock/LandlockState.kt, enforcer/src/main/kotlin/io/mazewall/landlock/Landlock.kt, enforcer/src/main/kotlin/io/mazewall/landlock/LandlockApplyResult.kt, enforcer/src/main/kotlin/io/mazewall/enforcer/api/ContainedExecutors.kt, enforcer/src/test/kotlin/io/mazewall/landlock/**, enforcer/src/test/kotlin/io/mazewall/enforcer/**, docs/internals/backlog/code_health/issue-20260907-080955-split-landlock-install-into-evaluate-plus-kernel-effects.md, specs/spec.md
- links: —
- depends_on: —
- contracts: [{"relation": "changes", "id": "contract:landlock.install.lifecycle"}, {"relation": "consumes", "id": "contract:containment.landlock-before-seccomp"}, {"relation": "changes", "id": "contract:landlock.apply.outcome"}]
- product_signoff: —

## Functional requirements
- Refactor internal Landlock installation into a typed pure evaluation lifecycle and an explicit native-effect interpreter while preserving existing containment behavior.

## Non-functional requirements (by lens)
- **security**: Preserve fail-closed handling of EPERM/EACCES, irreversible containment reporting, Landlock-before-Seccomp ordering, and owned-FD lifecycle.
- **architecture**: Use a pure typed evaluate-plus-effects boundary; preserve established NativeEngine/FFM isolation and public behavior without changing Seccomp design.
- **testability**: Exercise transition and error matrices deterministically with MockNativeEngine rather than kernel ruleset installation.

## Acceptance criteria (→ DoD)
- The internal lifecycle has typed events and a pure evaluate(state, event) that returns next state plus effects; evaluation performs no native calls and session state changes only from evaluation results, verified by focused unit tests.
- The effect protocol explicitly covers ruleset creation, rule addition, self-restriction, and owned FD closure; its interpreter uses the established native boundary and returns native results as events, verified with MockNativeEngine without a real ruleset FD.
- Mocked create, add-rule, and restrict-self failures retain their operation and errno/cause for current translation; EPERM and EACCES never succeed or silently bypass, verified under fail-closed and explicit bypass configurations.
- Successful and unsuccessful runs preserve current Applied, Rejected, and explicitly configured Bypassed behavior and public containment receipt semantics, verified by existing and new installation tests.
- ContainedExecutors completes Landlock before Seccomp on every applicable path, verified by an ordering regression test.
- Ruleset FDs close exactly once across success and failure paths; confined allocation and immediate native error capture remain preserved, verified by mock lifecycle tests.
- ./gradlew :enforcer:test passes; integrationTest runs only if a changed interpreter path cannot be covered with mocks.

## Open questions
—
