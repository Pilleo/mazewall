# B5 B4-local helper recipe — conditional full-build evidence

## Scope

This evidence covers the internal `ReviewedHelperSupportRecipe` structural snapshot consumed by existing B4 helper capture. The recipe copies reviewed path/digest membership and checks its bounded count, SHA-256 syntax, and lexical duplicates without filesystem access. Existing B4 capture retains its own path, authorization, file identity, content, pin ownership, and rollback checks. This result does not establish a Live guard change, authenticated post-claim provenance, B6, trusted custody, helper readiness, or Live activation.

## Focused and source evidence

The focused result records 26 cases passing against source identified as `c74f`. The semantically equivalent `569a` edit received review, and final KtLint completed with exit code 0 (`9924`). Earlier failed red-wrapper and lint attempts remain in the record; they are not counted as successful runs. The red wrapper's intended missing-validation assertions were observed, but the wrapper exited 95 because of a receipt-name control mismatch. A subsequent GREEN002 wrapper also exited 95 due to test KtLint failure; its focused case outcomes are preserved separately from the successful later formatting check.

## Conditional full build

The independently audited full-build packet was released once under manifest `5657b5b0179586f93668daa420812aa48b77b84fc4a8a4d1fc9fc73235cff21e`. Gradle reported `BUILD SUCCESSFUL` in 39m33s, with 248 actionable tasks (114 executed, 134 up-to-date). Source-before/after manifests and index snapshots matched, the container retired, and the host process was absent after wait. The run recorded 52 fresh task-owned unit-child coverage outputs.

The full run produced 694 retained JUnit XML files with 4,679 test invocations, 45 skips, zero failures, and zero errors. These are XML invocations, not deduplicated cases. Enforcer instruction coverage was 80,261/97,795 (82.070658%) for unit tests and 80,693/97,795 (82.512398%) combined.

Only the owning `:enforcer:integrationTestFreshJvm` task used the nondefault 15,000 ms prewarm property. The default remains five seconds; propagation through the full-run supervisor was `NOT_SAMPLED`. The result is conditional on that task setting and does not establish default-policy reliability. All 45 skips remain unqualified.

## Remaining limits

The build log contains three profiler socket `Function not implemented` shutdown exceptions, each with a suppressed directory exception. Issue 752 process, FD, pump, and directory retirement remains Unknown; build success does not prove those resources retired. A separate Landlock `ENOSYS` warning also appears in the log. Issues 338, 752, and 842 remain open. No issue closure or broader B5 qualification follows from this checkpoint.

## Local audit artifacts

LOCALONLY audit artifacts: `b5-helper-recipe-full-result-20261112-001.md` (report SHA-256 `7267799fe1b9e8688ccc453080465e4783c5f8cbea5b5ceaae0cbd01b1373ed9`) and the released packet directory `b5-helper-recipe-full-gate-proposal001/` (manifest SHA-256 `5657b5b0179586f93668daa420812aa48b77b84fc4a8a4d1fc9fc73235cff21e`). These repository-local artifacts are useful for local review; their names, manifests, logs, and receipts are not portable proof on their own.
