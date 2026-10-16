# Global OpenCode build tooling

## Goal and scope

Provide global PTY controls and useful Gradle-output reduction without losing
diagnostics or changing a project's build. Runtime files live in one user-level
tooling repository, `/home/leanid/.local/share/opencode/build-tooling`; OpenCode
loads its entrypoints through `/home/leanid/.config/opencode/opencode.jsonc`.
Projects need no new files, dependencies, task wiring, or environment fingerprints.

This document specifies two independent deliverables:

1. A pinned PTY adapter with conservative initial workflow behavior.
2. A capture-first Gradle presentation hook for native Bash/shell results.

Plans are in [global PTY](../../../superpowers/plans/2026-10-11-global-pty.md)
and [Gradle output](../../../superpowers/plans/2026-10-11-gradle-output.md).
Writing these documents does not authorize implementing either plan.

## Observed baseline

- Linux; OpenCode **1.18.35**, Bun **1.4.2**, Node **24.19.0**.
- Existing global additions: OMO standalone AST MCP from `omo-ai@5.1.29`,
  RTK **0.51.0**, notifier **0.8.1**. Existing Ponytail, AgentMemory, and jbcontext
  remain configured.
- RTK excludes `env`, `printenv`, `gradle`, `gradlew`, and `./gradlew`.
- The mazewall build is blocked at `:buildSrc:verificationActionsTest` by a
  `GradleWorkerMain` class-loading failure. These tooling plans must not attempt
  to repair or rerun that build.
- Replaying recorded output through RTK reduced **57,284 bytes to 3,507 bytes**,
  preserving the exit code, the class-loading message, and a recovery reference.
  This is a byte measurement, not an API-token or billing benchmark.
- RTK's ordinary recovery skips failures below **500 bytes**. A short fixture
  lost an unrecognized diagnostic without a recovery reference.

## Global constraints

- Runtime changes are global; no target-project file or Gradle logic changes.
- Preserve the existing provider/model settings, MCP servers, plugins, and permissions.
- Never modify, filter, or handle `GITHUB_TOKEN`.
- Do not register the full OMO plugin or its orchestration/continuation hooks.
- Execute each requested command once; preserve its arguments, exit status,
  and native interruption/timeout information.
- Use pure decisions, closed outcomes, strict types, and minimum visibility.
- Validate with isolated HOME/XDG fixtures and local fake programs. No paid model calls.
- No automatic retries, task starts, merges, or prompt-driven continuation.
- Global settings and logs must not be committed into the tooling repository.
- Stop deployment and restore its configuration backup if live validation fails.

## PTY contract

Pin `opencode-pty@0.4.0`, whose declared OpenCode requirement is `>=1.3.13`.
Use its V1 `PTYPlugin` entrypoint, not its V2 entrypoint. A small adapter preserves
upstream hooks and adds two initial-profile rules:

- `notifyOnExit: true` is rejected. Upstream calls `session.promptAsync`, which
  can start another model turn. Desktop notifier behavior is independent.
- A requested working directory must resolve inside the current workspace.
  Compare canonical paths with `path.relative`, not string prefixes.

Expose the five terminal tools. Do not register its separate TUI plugin or
invoke its browser commands automatically. Deny `pty_spawn`, `pty_write`, and
`pty_kill` for `plan`, `explore`, and the existing `fleet-tests` role; preserve
their existing permission entries. Do not convert `bash: ask` into `allow`.
Commands requiring that prompt continue through native Bash.

These are workflow controls, not an OS sandbox or a complete shell authorization
boundary. PTY V1 checks global Bash configuration and differs from native Bash;
deployment must prove the actual effective configuration.

PTY buffers are bounded and session-local. For durable build logs, use the
existing util-linux `script` **2.41.3** with `--quiet --return --log-out` and the
authorized command passed to `--command`. Pre-create logs with mode `0600` under
a private global log directory. Do not log input/keystrokes. A spawn result is
not a completed build; read actual terminal state and exit code.

## Gradle capture/presentation contract

Keep RTK's Gradle exclusions: pre-execution filtering would destroy data before
a post-execution hook could archive it. Gradle optimization is supplied by the
new hook, rather than disabled altogether.

The hook matches only simple native `bash`/`shell` Gradle invocations. Unrecognized
or compound commands pass unchanged. It never rewrites or executes commands.

For a matched result:

1. If native metadata reports truncation, verify and reuse its absolute
   `outputPath`. Do not load the whole large archive merely to format a preview.
2. Otherwise retain the complete native result text, including short and empty
   results, in a new mode-`0600` file under
   `/home/leanid/.local/state/opencode/gradle-logs`.
3. Preserve the original metadata and exit code. Represent outcomes as success,
   failure, or incomplete; an absent/null/invalid exit is not success.
4. On success, remove only ordinary task-progress lines and aggregate their
   reported statuses. Keep unknown lines, warnings, report paths, and build summaries.
5. On failure or incomplete execution, preserve the native visible result text.
   Do not introduce another error-keyword inclusion filter.
6. Append outcome and full-log references. Preview-derived counts must say
   `visible preview` when native output was truncated.

If archival fails, leave the original text uncompressed, preserve metadata, and
append a visible capture-failure message. Never claim a full archive exists.

PTY output is not a native Bash result. Its durable `script` log is retrieved
with existing file tools; `pty_read` provides bounded live views. The Gradle hook
must not interpret `pty_spawn` or `pty_read` as completed Gradle executions.

## Verification and rollout

Both plans use the same user-level Git repository for source/tests only. They
can execute independently; the second plan does not require PTY. Each ends with
an isolated live OpenCode check and local commits before global activation.
Do not upgrade OpenCode as part of either task. Restart it after approved deployment.

Primary implementation references:

- [OpenCode 1.18.35 shell result contract](https://github.com/anomalyco/opencode/blob/v1.18.35/packages/opencode/src/tool/shell.ts)
- [PTY project](https://github.com/shekohex/opencode-pty)
- [RTK 0.51.0 Gradle filter](https://github.com/rtk-ai/rtk/blob/v0.51.0/src/cmds/jvm/gradlew_cmd.rs)
- [RTK recovery threshold](https://github.com/rtk-ai/rtk/blob/v0.51.0/src/core/tee.rs)
