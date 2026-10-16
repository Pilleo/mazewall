# Global OpenCode V1 workflow improvements

## Status and approved scope

The operator approved planning the five recommendations from the V1 ecosystem
review. This document specifies that rollout; it does not authorize deployment.
The accompanying [implementation plan](../../../superpowers/plans/2026-10-11-opencode-v1-workflow-improvements.md)
also covers the requested Superpowers/Ponytail overlap assessment.

The five recommendations are:

1. Align the terminal and Zed on the tested V1 executable.
2. Expose native LSP navigation and native web search.
3. Add on-demand TokenScope measurement.
4. Tune built-in agent models and overlapping skill activation.
5. Add Context7 documentation lookup and a small checkpoint command.

## Observed baseline: 2026-10-11

- Project revision: `41ed2b999ad316a0f7e5e6548d49bd9b748d28f7`.
- Standalone tooling revision: `4e6b50d1220309a80203b9c7ee01021edadd5a0d`.
- Terminal executable: `/home/leanid/.opencode/bin/opencode`, version **1.18.35**.
- Zed: **1.23.2**. Its `opencode` agent is registry-managed and its cached
  executable is **1.19.0**.
- Zed's selected defaults are `openai/gpt-6.1-sol`, `effort: high`, `mode: build`.
  The effort value changed after the earlier research snapshot; preserve the
  value present at execution time rather than restoring an old snapshot.
- The project enables LSP and overrides `kotlin-ls` with `kotlin-lsp --stdio`.
  Native LSP navigation and web-search flags are absent in the inspected
  environment and Zed agent entry.
- Global plugins include the capture-first Gradle hook, RTK **0.51.0**, notifier
  **0.8.1**, PTY **0.4.0** through its policy adapter, and inherited Ponytail.
  AgentMemory, jbcontext, and standalone AST MCP remain configured.
- `fleet-tests` was removed at the operator's request. It must remain absent.
- `general` and `explore` are built-in subagents. Only Build and Plan are primary
  modes. No extra agent framework is required to configure these built-ins.
- Global user command and skill-override directories are currently empty.
- The inherited global permission is the shorthand `allow`; existing Plan and
  Explore PTY mutator denials live in the JSONC overlay.
- The seven-day native statistics report shows substantial prompt-cache reuse.
  Its call counts and cost fields are not an invoice or a measured savings claim.

## Architecture and choices

Use the existing global source repository at
`/home/leanid/.local/share/opencode/build-tooling` for the small launcher,
TokenScope entrypoint, prompt templates, checks, and documentation. Runtime
configuration remains in the existing global OpenCode and Zed files.

| Approach | Assessment |
| --- | --- |
| Native V1 configuration, one launcher, one analysis plugin, one docs MCP | Selected: small surface, existing tooling, independently verifiable stages |
| Add a complete agent/orchestration bundle | Duplicates existing control-plane and process instructions |
| Move to V2 and add replacement LSP | Deferred explicitly by the operator |

The canonical terminal command becomes `opencode-v1`. It forwards to the existing
absolute V1 executable; it does not replace the installer-managed `opencode`
binary, change the shell's PATH, or install another CLI. Zed uses the same
launcher. Document that plain `opencode` bypasses the launcher flags.

## Global constraints

- Stay on OpenCode **1.18.35**; do not install, switch to, or prepare OpenCode V2.
- Runtime changes are global; do not modify project code, Gradle wiring, or project skills.
- Preserve existing plugins, MCP servers, provider credentials, primary model choices, and permission behavior except for the explicitly listed additions.
- Never modify, filter, or handle `GITHUB_TOKEN`.
- Keep `fleet-tests` absent; use only built-in agents for model routing.
- Preserve PTY mutator denials and RTK Gradle exclusions.
- No automatic retries, task starts, merges, paid model calls, or prompt-driven continuation.
- Validate new behavior in temporary HOME/XDG/config/work directories before activation.
- Back up each changed global file privately; restore the current stage on validation failure without overwriting intervening user changes.
- Keep global settings, session content, raw reports, and credentials out of source control.
- Do not run the target project's Gradle build as part of this rollout.

## Runtime and feature contract

The launcher sets only the targeted native features and update guard:

```sh
export OPENCODE_EXPERIMENTAL_LSP_TOOL=true
export OPENCODE_ENABLE_EXA=1
export OPENCODE_DISABLE_AUTOUPDATE=1
exec "$HOME/.opencode/bin/opencode" "$@"
```

The global JSONC overlay also sets `autoupdate: false`. Do not enable the broad
`OPENCODE_EXPERIMENTAL` switch. Preserve command arguments, stdin, signals, and
exit status with `exec`; do not add a wrapper retry or command-output filter.

Native navigation is distinct from post-edit diagnostics. Test both. Successful
server initialization alone is not semantic-diagnostic evidence. A cold or silent
server is not a clean file. Web-search registration is verified offline; one
public search is a separately recorded hosted-service check, not a model call.

Change the existing Zed `opencode` entry to `type: custom`, with absolute launcher
path and `args: ["acp"]`. Preserve its favorites and default config options, all
other agents, context servers, and editor settings. Do not edit registry cache
files or replace the complete settings document.

## TokenScope contract

Pin `@ramtinj95/opencode-tokenscope` to **1.8.1** in the tooling repository and keep
the existing SDK/plugin overrides at **1.18.35**. Load a default-only local
entrypoint so OpenCode does not discover helper exports as plugin factories.

Analysis is on demand. Register `/tokenscope` as a native prompt command; its
report summary must keep recorded telemetry, retained-content estimates, and
price estimates separate. Reports remain private and outside project worktrees.
Missing telemetry, unknown tokenizers, and zero recorded cost must remain visible
uncertainties. Do not add passive telemetry, pruning, or another memory system.

## Built-in model routing

- Set `explore.model` to **`openai/gpt-6-luna`**.
- Set `small_model` to **`openai/gpt-6-luna`** for lightweight title work.
- Preserve Build/Plan model selection and Zed's current effort settings.
- Leave General inheriting the invoking model deliberately: it can implement
  changes and review substantial work, so do not silently make it a cheap worker.
- Configure no custom replacement for the removed test agent.

Native configuration proves routing, not comparative model quality. A real-model
quality/cost trial remains an operator-initiated follow-up; do not certify it from
a localhost mock-provider test or claim savings from API-rate estimates.

## Superpowers and Ponytail: overlap and conflict assessment

| Area | Source evidence | Decision |
| --- | --- | --- |
| Small, complete changes | Ponytail lines 21-44; Superpowers TDD lines 130-166 | Reinforce each other; retain one concise default |
| Root-cause fixes | Ponytail lines 38-40; systematic-debugging lines 48-188 | Use targeted debugging for actual failures, not a whole lifecycle for every edit |
| Verification | Ponytail lines 39-44; verification-before-completion lines 24-48 | Keep evidence-before-claims; reuse valid same-revision checks instead of ritual reruns |
| Test scope | Ponytail lines 41-44; TDD lines 18-45 and 283-320 | Nontrivial behavior/bug fixes get meaningful tests; trivial config/docs changes get focused validation, not mirrored unit tests |
| Design and approval | Ponytail full-mode action; brainstorming's mandatory gates | Apply design work to ambiguity/new behavior; executing an approved plan does not restart approval at each step |
| Skill loading | using-superpowers lines 10-24 and 33-49 | Disable this blanket bootstrap for normal OpenCode use; load relevant procedure skills on demand |
| Review | ponytail-review lines 16-67; requesting-code-review lines 12-44 | One scoped review with connected-code evidence; no automatic duplicate review merely because both packs are installed |
| Delegation | requesting-code-review and subagent-driven-development | Use only for an explicitly selected workflow/request; no background orchestration from the default style |

Ponytail is the default scope/style discipline. Superpowers remains installed as
an on-demand procedure library: debugging, meaningful TDD, planning, and review
can still be selected for suitable tasks. Do not edit upstream or cached package
skill bodies. Do not remove the Superpowers checkout or cross-harness copies.

Hide and deny only `using-superpowers` through native skill permissions. Preserve
all other skill permissions. The current inherited `allow` shorthand must remain
equivalent for every action except that named skill. The newer
`opencode/autoinvoke` metadata was not found in the pinned executable; this rollout
must not rely on it.

Append a short precedence/activation policy to global `AGENTS.md` after its
existing AgentMemory and jbcontext blocks. Project requirements and explicit
operator instructions retain precedence. Preserve security tests, kernel checks,
and project quality gates; lean does not mean weakening verification.

Identical project skill copies are an observed maintenance issue, not proof of
double token injection. Inventory their effective discovery paths, but do not
delete or rename project/cross-harness skills in this global rollout.

## Documentation and continuity

Register Context7 at **`https://mcp.context7.com/mcp`**. Verify its two relevant
tools, `resolve-library-id` and `query-docs`, with public Kotlin/Gradle queries.
No API-key setup or credential migration is part of this plan. A rate-limit or
authentication failure is recorded as blocked; it does not justify disabling
other MCPs or enabling an untested server.

Use version-specific docs when available. If Context7 lacks the required version,
use the relevant official source and state that limitation; indexed docs are not
authority for Linux UAPI or JDK FFM contracts.

The native `/checkpoint` command returns a short Markdown handoff in the current
session. It does not create another session, invoke the TUI API, write files,
launch agents, or repeat pending checks. Unknown or stale revision/test facts are
labeled explicitly. This makes it usable through terminal and Zed ACP.

## Rollout and acceptance

Each task has an independent validation/rollback checkpoint. Before deployment,
capture the current file bytes privately and compare only approved deltas. Never
run validation from a backup directory containing `opencode.jsonc`: it would be
loaded as project config.

Completion requires identical V1 executable selection, ACP initialization, native
tool exposure, real LSP feedback, TokenScope's private report and honest warnings,
model/permission preservation, the overlap policy, working docs lookup, and native
command discovery. A registration-only check does not complete a behavior gate.

Finish with the standalone toolkit's full test/typecheck gates and a fresh Zed
connection. Record the exact tested revision, successful checks, blocked external
checks if any, and private backup paths. Do not treat planned checks as results.

## References

- [V1 tools and experimental LSP navigation](https://opencode.ai/docs/tools/)
- [V1 agent models and inheritance](https://opencode.ai/docs/agents/)
- [V1 skills and per-agent permissions](https://opencode.ai/docs/skills/)
- [Native commands](https://opencode.ai/docs/commands/)
- [TokenScope 1.8.1](https://github.com/ramtinJ95/opencode-tokenscope)
- [Context7](https://github.com/upstash/context7)
- [Prior global build tooling](global-build-tooling.md)
