---
document_type: execution_plan
base_revision: 41ed2b999ad316a0f7e5e6548d49bd9b748d28f7
---

# Global OpenCode V1 Workflow Improvements Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development or superpowers:executing-plans to implement this plan task-by-task. Use subagents only if the operator selects that execution approach. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement the five approved V1 improvements and resolve Superpowers/Ponytail overlap without introducing another orchestration system.

**Architecture:** Extend the existing user-level tooling repository with a small canonical launcher, a pinned TokenScope entrypoint, and two native prompt commands. Make additive changes to global OpenCode and Zed configuration; retain Ponytail as the default discipline and Superpowers as targeted procedures.

**Tech Stack:** OpenCode 1.18.35, Zed 1.23.2, Bun 1.4.2, Node 24.19.0, TypeScript 5.9.3, Python 3, native V1 ACP/LSP, Context7 MCP.

**Spec:** [Global V1 workflow design](../../internals/designs/core/opencode-v1-workflow-improvements.md).

**Execution source base:** `4e6b50d1220309a80203b9c7ee01021edadd5a0d` in `/home/leanid/.local/share/opencode/build-tooling`.

**Document type:** `execution_plan`

**Base revision:** `41ed2b999ad316a0f7e5e6548d49bd9b748d28f7`

## Global Constraints

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

## Approved five-point mapping

| Approved recommendation | Tasks |
| --- | --- |
| Align terminal and Zed | 1 |
| Native LSP navigation and web search | 2 |
| TokenScope baseline | 3 |
| Built-in models and skill activation, including requested overlap assessment | 4-5 |
| Context7 and a checkpoint command | 6-7 |
| Integrated verification and handoff | 8 |

## File map

Tracked source under `/home/leanid/.local/share/opencode/build-tooling`:

- Create `bin/opencode-v1`: canonical launcher with targeted flags and exact argv/exit forwarding.
- Create `tests/v1-launcher.test.ts`: real subprocess launcher contract.
- Modify `tests/support/fake-provider.ts`: optional finite scripted tool sequence, preserving existing Bash-only callers.
- Modify `tests/gradle-live.test.ts`: native tool availability proof in the existing isolated first-party fixture.
- Modify `tests/pty-startup.test.mjs`: native catalog/config proof while preserving all lifecycle assertions.
- Modify `package.json` and `bun.lock`: pin TokenScope; retain all existing pins and overrides.
- Create `src/tokenscope-plugin.ts`: default-only runtime export.
- Create `commands/tokenscope.md`, `commands/checkpoint.md`: versioned native prompt templates.
- Create `docs/v1-profile.md`: operator command, activation policy, diagnostics, measurement, limits, and receipts.

Global deployment targets:

- Create `/home/leanid/.local/bin/opencode-v1` from the verified launcher.
- Modify `/home/leanid/.config/zed/settings.json`: only the existing OpenCode agent's runtime fields.
- Modify `/home/leanid/.config/opencode/opencode.jsonc`: additive update guard, plugin/MCP entries, built-in model overrides, and one named skill denial.
- Modify `/home/leanid/.config/opencode/AGENTS.md`: append the concise workflow policy.
- Create `/home/leanid/.config/opencode/commands/tokenscope.md` and `checkpoint.md` from verified templates.

Private evidence goes under the existing ignored `.evidence/` directory. Global
backups go under `/home/leanid/.local/state/opencode/setup-backups/`.
Do not stage either location, real config files, or the target project worktree.

## Shared execution discipline

- [ ] Inspect `git status --short --branch` in the tooling repository and verify the source base before writing. If it changed, inspect and preserve the intervening work rather than overwriting it.
- [ ] Obtain task-owned isolation before executable changes. The existing `work/global-build-tooling` branch belongs to the previous delivery; do not assume it is free for concurrent work. Use the using-git-worktrees procedure for an execution worktree. Before activation the operator-controlled source update must put the reviewed files and lockfile at the canonical path; no merge/push is implied or automated by this plan. If that update is not authorized, retain the worktree and stop deployment rather than registering an unreviewed canonical path.
- [ ] Record each task's base/head, exact checks, review, backup, and rollback state in a private execution ledger. Resume completed tasks from that ledger instead of repeating them after compaction.
- [ ] Run focused checks after actual changes. Configuration-only edits need syntax/effective-configuration checks, not fabricated RED/GREEN unit tests. New launcher/helper behavior does need meaningful regression checks.
- [ ] Capture debug output privately and inspect only approved fields. Never print full effective provider configuration.
- [ ] Run global validation from a clean temporary working directory, never a backup directory containing an `opencode.jsonc` copy.

Use this concrete backup recipe before each global stage; change only the listed
source paths for that stage:

```python
from pathlib import Path
import tempfile, shutil

base = Path.home() / ".local/state/opencode/setup-backups"
base.mkdir(parents=True, exist_ok=True)
backup = Path(tempfile.mkdtemp(prefix="v1-workflow-", dir=base))
backup.chmod(0o700)
sources = [Path.home() / ".config/opencode/opencode.jsonc"]
for source in sources:
    destination = backup / source.name
    if source.exists():
        shutil.copyfile(source, destination)
        destination.chmod(0o600)
    else:
        (backup / (source.name + ".absent")).write_text("absent before this stage\n")
        (backup / (source.name + ".absent")).chmod(0o600)
print(backup)
```

Record original file modes. Before restoration compare the current bytes with the
stage's deployed bytes; an intervening user edit stops restoration for that file.
For a newly created file, restore absence rather than inventing an empty config.

## Task 1: Canonical V1 launcher and Zed runtime alignment

**Files:** `bin/opencode-v1`, `tests/v1-launcher.test.ts`, `docs/v1-profile.md`; global launcher, Zed settings, and JSONC update guard after verification.

**Interfaces:** Produces `opencode-v1 [original argv...]`, forwarding to `$HOME/.opencode/bin/opencode`. Later tasks use its three feature/update environment values. No new OpenCode installation or PATH rewrite.

- [ ] **Step 1: Verify prerequisites and capture the mutable baseline.**

```bash
/home/leanid/.opencode/bin/opencode --version
bun --version
node --version
zed --version
```

Require 1.18.35, 1.4.2, v24.19.0, and Zed 1.23.2. A differing version needs a
compatibility finding, not an automatic upgrade/downgrade. Privately record Zed's
OpenCode entry, full-file hashes, and the current selected model/effort/favorites.

- [ ] **Step 2: Add the real forwarding regression before the launcher.**

```typescript
// tests/v1-launcher.test.ts
import { test, expect } from "bun:test";
import { mkdtemp, mkdir, writeFile, rm } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join, resolve } from "node:path";

test("launcher forwards exact argv, targeted flags, and failure without retry", async () => {
  const home = await mkdtemp(join(tmpdir(), "v1-launcher-"));
  try {
    await mkdir(join(home, ".opencode/bin"), { recursive: true });
    await writeFile(join(home, ".opencode/bin/opencode"),
      '#!/usr/bin/env python3\nimport os,sys,json\nprint(json.dumps({"args":sys.argv[1:],"lsp":os.environ.get("OPENCODE_EXPERIMENTAL_LSP_TOOL"),"search":os.environ.get("OPENCODE_ENABLE_EXA"),"update":os.environ.get("OPENCODE_DISABLE_AUTOUPDATE")}))\nraise SystemExit(7)\n',
      { mode: 0o755 });
    const args = ["acp", "space in argument", "single'quote", "--literal=$x"];
    const child = Bun.spawn(["sh", resolve("bin/opencode-v1"), ...args], {
      env: { HOME: home, PATH: process.env.PATH ?? "/usr/bin:/bin",
        XDG_CONFIG_HOME: join(home, "config"), XDG_DATA_HOME: join(home, "data"),
        XDG_STATE_HOME: join(home, "state"), XDG_CACHE_HOME: join(home, "cache") },
      stdout: "pipe", stderr: "pipe",
    });
    const stdout = new Response(child.stdout).text();
    const stderr = new Response(child.stderr).text();
    expect(await child.exited).toBe(7);
    expect(await stderr).toBe("");
    const lines = (await stdout).trim().split("\n");
    expect(lines).toHaveLength(1);
    expect(JSON.parse(lines[0])).toEqual({ args, lsp: "true", search: "1", update: "1" });
  } finally { await rm(home, { recursive: true, force: true }); }
});
```

Run `bun test tests/v1-launcher.test.ts`. Expected RED: the missing launcher exits
with a different status and cannot produce the expected forwarding receipt.

- [ ] **Step 3: Add the minimal launcher.**

```sh
#!/bin/sh
export OPENCODE_EXPERIMENTAL_LSP_TOOL=true
export OPENCODE_ENABLE_EXA=1
export OPENCODE_DISABLE_AUTOUPDATE=1
exec "$HOME/.opencode/bin/opencode" "$@"
```

Save as `bin/opencode-v1`, set executable mode, and run the forwarding test plus
`sh -n bin/opencode-v1`. Require one output receipt and native exit 7.

- [ ] **Step 4: Commit the verified launcher before activation.**

```bash
git add bin/opencode-v1 tests/v1-launcher.test.ts
git commit -m "feat: add canonical V1 launcher with targeted native features"
```

Promote the reviewed commit to the canonical tooling directory. Do not install
the global launcher yet: Task 2 activates it only after the native capability
checks. Pre-activation checks call the reviewed source with `sh` directly.

- [ ] **Step 5: Prepare the exact runtime-selection patch; defer activation to Task 2.**

Privately record the current `settings.json`, JSONC overlay, and launcher/absence
baseline. Prepare these runtime fields for the existing Zed OpenCode entry:

```json
{
  "agent_servers": {
    "opencode": {
      "type": "custom",
      "command": "/home/leanid/.local/bin/opencode-v1",
      "args": ["acp"]
    }
  }
}
```

The planned OpenCode overlay delta is `"autoupdate": false`. Preserve current Zed
defaults and favorites, not the earlier research snapshot. Compare the parsed
Zed document to the backup with only these three fields changed, and the OpenCode
overlay with only the update guard changed. Do not apply global changes until
Task 2's offline/native behavior gates pass; this task proves the launcher/ACP
contract independently.

- [ ] **Step 6: Verify native selection and ACP startup without generation.**

Require `sh bin/opencode-v1 --version` to return 1.18.35. In temporary HOME/XDG/config
roots, symlink the installed binary to the fixture's `.opencode/bin/opencode`,
seed pinned SDK config dependencies as in `tests/pty-startup.test.mjs`, and start
the launcher with `acp`. Send this newline-delimited request on stdin:

```json
{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":1,"clientCapabilities":{},"clientInfo":{"name":"v1-workflow-fixture","version":"1"}}}
```

Require a successful response with protocolVersion 1 and no stdout noise. Do not
send `session/prompt`. Close stdin, require the owned process to exit within five
seconds, and retain stderr privately if it does not. Confirm the same global
file hashes remain intact after this isolated proof. A failed proof blocks global
activation.

## Task 2: Native navigation, diagnostics, and web-search capability checks

**Files:** `tests/pty-startup.test.mjs`, `tests/gradle-live.test.ts`, `docs/v1-profile.md`; private disposable LSP fixtures. Existing production adapters remain unchanged.

**Interfaces:** Consumes the launcher flags from Task 1. Produces evidence that the native `lsp` and `websearch` tools are actually available and the installed language servers report a deliberate error. Navigation and diagnostic outcomes are separate receipts.

- [ ] **Step 1: Extend the isolated startup fixture's explicit environment.**

Add these values to its existing allowlisted `env` object:

```javascript
Object.assign(env, {
  OPENCODE_EXPERIMENTAL_LSP_TOOL: "true",
  OPENCODE_ENABLE_EXA: "1",
});
```

After the existing PTY ID checks, add:

```javascript
for (const name of ["lsp", "websearch"]) assert(ids.includes(name), name);
```

Retain the private roots, SDK seed, real-config/session preservation checks,
bounded startup/cleanup, and the original five PTY assertions.

- [ ] **Step 2: Prove model-facing tool availability in the native execution fixture.**

Add the same two flags to `tests/gradle-live.test.ts`'s allowlisted environment.
Add only the two corresponding permissions to the fixture's existing object:

```typescript
Object.assign(config.permission, { lsp: "allow", websearch: "allow" });
```

After the successful CLI completion, inspect the captured provider request:

```typescript
const offered = provider.requests.flatMap((body): unknown[] =>
  Array.isArray(body.tools) ? body.tools : []).filter(record)
  .flatMap((definition) => record(definition.function) &&
    typeof definition.function.name === "string" ? [definition.function.name] : []);
expect(offered).toContain("lsp");
expect(offered).toContain("websearch");
```

The fake provider still executes only its original Bash fixture. These additions
must not call hosted search or a real model. Existing once-only/exit/log/network
assertions stay intact. Run both changed fixture files and the strict typecheck.

- [ ] **Step 3: Probe real LSP diagnostics using native no-model commands.**

The installed CLI exposes these commands, verified during planning:

```bash
fixture=$(mktemp -d /tmp/v1-lsp-fixture-XXXXXXXX)
cd "$fixture"
sh /home/leanid/.local/share/opencode/build-tooling/bin/opencode-v1 debug lsp diagnostics src/main/kotlin/Probe.kt
sh /home/leanid/.local/share/opencode/build-tooling/bin/opencode-v1 debug lsp diagnostics probe.cpp
sh /home/leanid/.local/share/opencode/build-tooling/bin/opencode-v1 debug lsp document-symbols "file://$fixture/src/main/kotlin/Probe.kt"
```

Create the source/config fixture before the diagnostic commands. Execute with its
directory as cwd and temporary
HOME/OPENCODE_TEST_HOME/all four XDG roots. Seed the existing SDK as in the startup
fixture. Configure only the actual installed language-server commands through a
private `OPENCODE_CONFIG_CONTENT`; resolve their absolute executable paths before
changing HOME. Managed clangd is not assumed to be on PATH.

Write these complete disposable source files at `src/main/kotlin/Probe.kt` and
`probe.cpp`, creating the Kotlin source directory first:

```kotlin
fun answer(): Int = "wrong type"
```

```cpp
int answer() { return "wrong type"; }
```

For Kotlin, create `settings.gradle.kts` with
`rootProject.name = "v1-lsp-fixture"` and use the project's pinned Kotlin version
**2.4.20-Beta1** if a Gradle model is required. Its disposable `build.gradle.kts`
content is:

```kotlin
plugins { kotlin("jvm") version "2.4.20-Beta1" }
repositories { mavenCentral() }
kotlin { jvmToolchain(25) }
```

All wrapper/build/cache work must
stay in the disposable fixture; never point this probe's root at the user
worktree. Use the existing installation/cache rather than upgrading a server.

Capture each command under a finite 60-second parent timeout, using the existing
`strace --kill-on-exit -f -qq` fixture discipline so detached owned language-server
children cannot survive a deadline. Require an error
diagnostic at the deliberate mismatch. The corrected source files are:

```kotlin
fun answer(): Int = 1
```

```cpp
int answer() { return 1; }
```

Require that exact error to disappear and a document symbol for `answer`.
An empty cold-start result is **incomplete**, not PASS. If root/import/capability
requirements prevent a result, retain the failure, diagnose once, and mark this
gate blocked; do not weaken it to successful initialization.

- [ ] **Step 4: Activate the jointly verified launcher/native profile.**

Only after Steps 1-3 pass, take fresh backups and apply Task 1's prepared global
patches with `apply_patch`. Install the launcher with mode 0755; require exact
equality to the reviewed source. Recheck the target executable's version, the
fresh effective native tool catalog, ACP initialization, and unchanged global
provider/plugin/MCP/permission fields. If this combined stage fails, restore its
own backups and remove the newly created launcher only when the recorded current
bytes still match the deployment. Do not edit Zed registry caches.

- [ ] **Step 5: Record hosted-search behavior separately.**

Registration and model-facing availability are offline gates. After those pass,
perform one public, non-secret search for `OpenCode 1.18.35 LSP tool flag` through
the native tool in an explicitly operator-run session. Require useful source
URLs. This is a hosted-service check and is not part of the localhost-only test
suite. Do not launch a paid model turn from a worker merely to exercise it.

- [ ] **Step 6: Commit the verified fixture changes and document distinct outcomes.**

```bash
git add tests/pty-startup.test.mjs tests/gradle-live.test.ts docs/v1-profile.md
git commit -m "test: verify native V1 navigation and search exposure"
```

Record native tool exposure, real diagnostic/symbol checks, and hosted-service
status separately. An unperformed hosted check stays pending, not silently PASS.

## Task 3: Pinned, on-demand TokenScope and an honest baseline

**Files:** `package.json`, `bun.lock`, `src/tokenscope-plugin.ts`, `commands/tokenscope.md`, `tests/support/fake-provider.ts`, `docs/v1-profile.md`; global plugin and command after isolated proof.

**Interfaces:** Produces a default-only plugin entrypoint and `/tokenscope`. Extends `fakeProvider(command: string, calls?: readonly FixtureCall[])` without breaking existing callers. Reports remain outside the project and contain uncertainty rather than invented precision.

- [ ] **Step 1: Install only the pinned analysis dependency in the task-owned repository.**

Merge this dependency into `package.json`:

```json
{
  "dependencies": {
    "@ramtinj95/opencode-tokenscope": "1.8.1"
  }
}
```

Run `bun install --ignore-scripts`. Retain PTY and all existing SDK/native pins.
Require the published package's `dist/tokenscope.js`, bundled data files, and
dependencies to be present. Do not run a remote installer or add a second global
installation. Stop on packaging or unsupported-host errors.

- [ ] **Step 2: Add the entrypoint and exact native prompt template.**

```typescript
// src/tokenscope-plugin.ts
export { default } from "@ramtinj95/opencode-tokenscope";
```

```markdown
---
description: Inspect recorded session usage, cache reuse, and context contributors
---
Call tokenscope directly with includeSubagents=true and sessionID unset, unless
I explicitly supplied a session ID. Do not delegate.
Read the exact private report path it returns. Summarize recorded usage, cache
reuse, leading retained-content contributors, loaded skills, child-session
totals, and any warnings in at most 250 words. Include the report path.
Keep recorded telemetry, local token estimates, and public-price estimates
separate. Zero recorded cost is not proof of free usage or a provider invoice.
Do not change files, prune context, or repeat task commands.
```

Save the template as `commands/tokenscope.md`.

- [ ] **Step 3: Extend the existing fake provider for finite tool sequences.**

Use this narrow test-helper interface:

```typescript
export type FixtureCall = {
  readonly name: "bash" | "read" | "edit" | "lsp" | "tokenscope";
  readonly args: Record<string, unknown>;
};
```

Default `calls` to the existing one Bash invocation, with its current command,
description, and timeout. Replace the single `invoked` latch with an index and
pending tool-call ID. Emit the next call only when its tool is offered and the
previous emitted ID has a matching tool-result message. A repeated request that
omits the result returns text and never reissues a call. Keep the first ID exactly
`fixture-call` so the existing Gradle regression remains compatible; subsequent
IDs are `fixture-call-1`, `fixture-call-2`, in order.

Use this complete implementation for `tests/support/fake-provider.ts`; its
existing `record` guard is included so the task is self-contained:

```typescript
const record = (value: unknown): value is Record<string, unknown> =>
  value !== null && typeof value === "object" && !Array.isArray(value);

export type FixtureCall = {
  readonly name: "bash" | "read" | "edit" | "lsp" | "tokenscope";
  readonly args: Record<string, unknown>;
};

export function fakeProvider(command: string, calls: readonly FixtureCall[] = [{
  name: "bash", args: { command, description: "Execute isolated fixture once", timeout: 5000 },
}]) {
  const requests: Record<string, unknown>[] = [];
  const unexpected: string[] = [];
  let index = 0;
  let pending: string | undefined;
  const server = Bun.serve({
    hostname: "127.0.0.1", port: 0,
    async fetch(request) {
      const path = new URL(request.url).pathname;
      if (request.method !== "POST" || path !== "/v1/chat/completions") {
        unexpected.push(`${request.method} ${path}`);
        return new Response("Unexpected endpoint", { status: 400 });
      }
      const body: unknown = await request.json();
      if (!record(body) || !Array.isArray(body.messages) || !body.messages.every(record)) {
        unexpected.push("Invalid chat request");
        return new Response("Invalid chat request", { status: 400 });
      }
      requests.push(body);
      const tools: unknown[] = Array.isArray(body.tools) ? body.tools : [];
      const available = tools.filter(record).map((tool) => record(tool.function) ? tool.function.name : undefined);
      const previousReturned = pending === undefined || body.messages.some(
        (message) => message.role === "tool" && message.tool_call_id === pending);
      const next = calls[index];
      const invoke = previousReturned && next !== undefined && available.includes(next.name);
      if (invoke) {
        pending = index === 0 ? "fixture-call" : `fixture-call-${index}`;
        index++;
      }
      const delta = invoke
        ? { role: "assistant", tool_calls: [{ index: 0, id: pending, type: "function", function: {
          name: next.name, arguments: JSON.stringify(next.args),
        } }] }
        : { role: "assistant", content: "Fixture finished." };
      const chunk = (value: unknown, finish: string | null) => ({
        id: "fixture", object: "chat.completion.chunk", created: 1, model: "mock",
        choices: [{ index: 0, delta: value, finish_reason: finish }],
      });
      return new Response(
        `data: ${JSON.stringify(chunk(delta, null))}\n\ndata: ${JSON.stringify(chunk({}, invoke ? "tool_calls" : "stop"))}\n\ndata: [DONE]\n\n`,
        { headers: { "Content-Type": "text/event-stream" } },
      );
    },
  });
  return { url: server.url.toString(), requests, unexpected, close: () => server.stop(true) };
}
```

Add this focused protocol regression to `tests/gradle-live.test.ts` before
changing the helper; its expected RED is the old helper not emitting the requested
read/TokenScope sequence:

```typescript
test("scripted tools advance only after their exact prior result", async () => {
  const provider = fakeProvider("unused", [
    { name: "read", args: { filePath: "/fixture/Probe.kt" } },
    { name: "tokenscope", args: { includeSubagents: true } },
  ]);
  const request = (messages: unknown[]) => fetch(provider.url + "v1/chat/completions", {
    method: "POST", headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ messages, tools: ["read", "tokenscope"].map((name) =>
      ({ type: "function", function: { name } })) }),
  }).then((response) => response.text());
  try {
    expect(await request([{ role: "user", content: "Run fixture" }])).toContain('"name":"read"');
    expect(await request([{ role: "user", content: "Run fixture" }])).not.toContain('"tool_calls"');
    expect(await request([{ role: "tool", tool_call_id: "wrong-id", content: "ignored" }])).not.toContain('"tool_calls"');
    expect(await request([{ role: "tool", tool_call_id: "fixture-call", content: "file text" }])).toContain('"name":"tokenscope"');
    expect(await request([{ role: "tool", tool_call_id: "fixture-call-1", content: "report path" }])).toContain("Fixture finished.");
    expect(provider.unexpected).toEqual([]);
  } finally { await provider.close(); }
});
```

Keep the existing local endpoint validation and close behavior. Do not remove the
original once-only test or detached-child cleanup test.

- [ ] **Step 4: Prove the installed plugin through native first-party execution.**

Parametrize the existing native test in `tests/gradle-live.test.ts` with
`for (const scenario of ["gradle", "tokenscope"] as const)`, keeping the
protocol/deadline tests outside that loop. Wrap the existing native test call;
retain its 55-second timeout and use a scenario-specific test name.
The test body uses `scenario` to select its entrypoint and requested tool:

Import the new test-only type with the existing helper import:

```typescript
import { fakeProvider, type FixtureCall } from "./support/fake-provider.ts";
```

```typescript
const plugin = pathToFileURL(resolve(scenario === "gradle"
  ? "src/gradle-output-plugin.ts" : "src/tokenscope-plugin.ts")).href;
const provider = fakeProvider("./gradlew build", scenario === "gradle" ? undefined : [
  { name: "tokenscope", args: { includeSubagents: true } },
]);
```

Keep the exact existing Gradle assertions in the `gradle` branch. Add only
`tokenscope: "allow"` to the TokenScope fixture's permission object. Its CLI
prompt is `Run tokenscope once and finish; do not run shell commands or retry.`
Require its native tool event to be `tokenscope`, not Bash. The marker and Gradle
log assertions remain exclusive to the Gradle branch; shared exit, local-network,
deadline, evidence, and cleanup assertions still apply to both.

Reuse the existing isolated live fixture's config seeding, child deadline,
stdin EOF, `strace --kill-on-exit`, and retained evidence. Select only the absolute
TokenScope adapter URI. Offer the fake provider one call:

```typescript
const calls: readonly FixtureCall[] = [
  { name: "tokenscope", args: { includeSubagents: true } },
];
```

Keep the fixture provider local and select `fixture/mock`. Require exactly one
TokenScope tool event, its report path in the next provider request, a regular
owner-only report file outside the fixture worktree, and visible qualifications
for missing telemetry/unknown-model estimates. Copy the private report into
ignored evidence before cleanup. Do not require positive billing numbers from a
mock model or download tokenizer assets just to hide an uncertainty warning.

Run the existing Gradle tests after the helper change, strict typecheck, and a
native isolated startup with the PTY and TokenScope entrypoints together. Require
one registration of each tool and intact lifecycle cleanup.

- [ ] **Step 5: Commit, deploy additively, and collect on-demand baseline evidence.**

Commit the dependency, adapter, helper/checks, prompt, and docs before deployment.
After promoting those reviewed commits, run `bun install --ignore-scripts` in the
canonical tooling repository as well: Git does not promote ignored node_modules.
Require the same lockfile and installed package version there before activation.
Back up the JSONC overlay and command/absence receipt. Append exactly one URI:

```json
"file:///home/leanid/.local/share/opencode/build-tooling/src/tokenscope-plugin.ts"
```

Copy the verified prompt to the global `commands/tokenscope.md` with `apply_patch`.
Do not reorder the Gradle capture hook or duplicate existing plugins.

The operator can invoke `/tokenscope` in representative existing sessions after
restart. Record warnings, tool/skill contributors, cache reuse, and child-session
totals. Do not report before/after savings until comparable actual sessions exist.

## Task 4: Built-in model routing without a replacement fleet

**Files:** global JSONC overlay; `docs/v1-profile.md`.

**Interfaces:** `explore` uses `openai/gpt-6-luna`; `small_model` uses the same model; General remains intentionally inherited. Primary modes, current selected models, and permissions remain intact.

- [ ] **Step 1: Capture the effective role baseline privately.**

From a clean working directory, capture `opencode-v1 debug agent build`, `plan`,
`general`, and `explore`, plus `opencode-v1 agent list`. Inspect only mode/model/
permission fields. Confirm `fleet-tests` is absent. Verify `openai/gpt-6-luna`
exists in the current provider's model inventory; do not authenticate or switch
providers if it does not.

- [ ] **Step 2: Back up and merge these fields only.**

```json
{
  "small_model": "openai/gpt-6-luna",
  "agent": {
    "explore": { "model": "openai/gpt-6-luna" }
  }
}
```

Preserve Explore's existing PTY denials. Leave General's model unset to preserve
the invoking model, rather than making an implementation-capable subagent a
cheap worker. Preserve Build/Plan selection and Zed's current effort/favorites.

- [ ] **Step 3: Verify routing and all unchanged permissions.**

Require the fresh effective Explore model to resolve to provider `openai`, model
`gpt-6-luna`, and the fresh effective `small_model` to match. All original role
permission rules retain their order and values; no new agent appears. Compare
parsed before/after configuration with only these two model additions permitted.
Do not claim model quality or lower actual cost from this configuration check.

- [ ] **Step 4: Record the routing decision and review this independent stage.**

Commit documentation only. Any real-model comparison is operator-initiated and
records its workload/result; it is not silently run by the implementation worker.

## Task 5: Resolve Superpowers/Ponytail overlap through global policy

**Files:** global `AGENTS.md` and JSONC overlay; `docs/v1-profile.md`.

**Interfaces:** Ponytail remains the default. Targeted Superpowers skills remain available; only the blanket `using-superpowers` bootstrap is hidden/denied. No project or upstream skill file changes.

- [ ] **Step 1: Capture discovery and policy baselines privately.**

Use `opencode-v1 debug skill` and the four effective role dumps. Record the actual
Ponytail/Superpowers skill sources and current permissions. Verify the installed
bootstrap still contains its blanket before-any-response/1%-chance invocation
rule; if upstream changed, re-evaluate this task against the overlap table in the
spec. Duplicate source paths do not establish duplicate token injection.

- [ ] **Step 2: Add the narrow skill denial without changing unrelated authority.**

The current global base uses `permission: "allow"`. Preserve that exact shorthand
semantics in the overlay while adding one denial:

```json
{
  "permission": {
    "*": "allow",
    "skill": {
      "*": "allow",
      "using-superpowers": "deny"
    }
  }
}
```

If the execution-time baseline is no longer `allow`, do not apply this fragment
unchanged: preserve the actual ordered rules and add only the named denial.
Role-specific restrictions continue to apply. Do not use unsupported
`opencode/autoinvoke` metadata, modify cached package bodies, or delete checkouts.

- [ ] **Step 3: Append this exact concise policy after existing global guidance.**

```markdown
## Coding discipline and workflow selection

- Ponytail is the default scope and coding-style discipline: smallest complete
  change, existing patterns, preserved validation, and concise evidence.
- Superpowers is an on-demand procedure library. Load a relevant debugging,
  testing, planning, or review skill when its procedure helps the actual task;
  do not load a whole lifecycle for every question or trivial edit.
- Nontrivial behavior changes and bug fixes need meaningful regression checks.
  Trivial reversible config/docs changes need focused validation, not tests
  that merely mirror their implementation. Preserve project-required gates.
- Root-cause investigation and evidence-before-completion remain mandatory.
  Reuse valid checks for the same revision; repeat when changes or a concrete
  unresolved concern justify it, not merely because another skill requests it.
- Perform one scoped review with the necessary connected-code evidence.
  Do not automatically duplicate reviews across Ponytail and Superpowers.
- Use delegated workflows only when explicitly requested or selected. Never
  let a default style skill start agents, continuation, merges, or publishing.
- An approved plan is authorization to execute its scoped tasks, not a reason
  to restart design approval at every step. Ask when scope or risk materially
  changes. Explicit operator instructions and project requirements take priority.
```

Back up `AGENTS.md` and the JSONC overlay first. Preserve the AgentMemory and
jbcontext blocks byte-for-byte. This resolves overlapping procedures; it does
not override project security invariants, authorized review workflows, or model
system/developer instructions.

- [ ] **Step 4: Verify native visibility and retained procedure access.**

Require `using-superpowers` to be denied/absent from model-facing discovery for
normal roles. Require Ponytail, systematic-debugging, test-driven-development,
verification-before-completion, writing-plans, and the chosen execution skill to
remain accessible. Read-only effective-agent checks must show no changed action
other than the named skill denial. Preserve Plan/Explore PTY restrictions and
the absence of `fleet-tests`.

- [ ] **Step 5: Document evidence and review the policy once.**

Record the overlap table, before/after catalog, and exact global patch. A future
on-demand TokenScope comparison can measure catalog/loaded-skill changes; do not
claim a percentage saving now. Leave identical project/cross-harness files alone.

## Task 6: Context7 for public, version-aware dependency documentation

**Files:** global JSONC overlay; `docs/v1-profile.md`; private MCP check evidence.

**Interfaces:** Adds the `context7` remote MCP server at `https://mcp.context7.com/mcp`, retaining all existing servers. It supplies `resolve-library-id` and `query-docs`.

- [ ] **Step 1: Test the endpoint in an isolated OpenCode configuration.**

Use temporary HOME/XDG/config/work roots and the existing pinned SDK seed. The
only selected additional MCP is:

```json
{
  "mcp": {
    "context7": {
      "type": "remote",
      "url": "https://mcp.context7.com/mcp",
      "enabled": true
    }
  }
}
```

Run `opencode-v1 mcp list` and `opencode-v1 mcp debug context7`, with a finite
parent timeout and private captures. This is an intentional external MCP check,
not part of the offline mock-provider test. Do not run an auth/setup installer.

- [ ] **Step 2: Require actual docs behavior, not just a connection indicator.**

Use the connected MCP transport's standard `tools/call` request. The first
request's method and params are exactly:

```json
{
  "method": "tools/call",
  "params": {
    "name": "resolve-library-id",
    "arguments": {
      "libraryName": "Gradle",
      "query": "Gradle task configuration avoidance"
    }
  }
}
```

Call `resolve-library-id` with
`libraryName: "Gradle"`, `query: "Gradle task configuration avoidance"`, then
`query-docs` with the returned exact library ID and the same public query.
For `query-docs`, `params.name` is `query-docs` and `params.arguments` contains
`libraryId` set to the exact ID actually returned, and `query` set to the public
query above. Repeat for `libraryName: "Kotlin"`,
`query: "Kotlin sealed classes and exhaustive when"`.
Require useful documentation and source/library identifiers. Record versions
actually available; do not claim coverage for 2.4.20-Beta1 unless returned.

Do not send private project code. If the anonymous endpoint requires auth, is
rate-limited, or lacks usable docs, keep this stage blocked and leave live
configuration unchanged. Credential setup is a separate operator action.

- [ ] **Step 3: Deploy the proven server additively.**

Back up the JSONC overlay and merge the proven entry. Compare every old MCP entry
for exact equality; no duplicate AgentMemory/jbcontext/AST definitions. Require
fresh native connection/catalog checks and useful docs calls. Roll back this
stage if they fail.

- [ ] **Step 4: Document the lookup rule.**

Append to the global workflow guidance:

```markdown
- Use Context7 for dependency/API documentation when its requested library and
  version are available. State the version actually retrieved. Use official
  sources for missing versions, Linux UAPI, and JDK FFM contracts; indexed docs
  do not replace those authorities.
```

Back up `AGENTS.md` if it changes in this stage. Commit only sanitized source-side
documentation and record the external-check outcome separately from unit tests.

## Task 7: A native checkpoint command that also works through ACP

**Files:** `commands/checkpoint.md`, `docs/v1-profile.md`; global native command file.

**Interfaces:** `/checkpoint` returns a compact current-session Markdown handoff. It does not depend on TUI APIs, new sessions, agents, files, or automatic command execution.

- [ ] **Step 1: Create the exact prompt template.**

```markdown
---
description: Produce a compact evidence-preserving handoff for the current task
---
Produce a self-contained Markdown checkpoint, at most 500 words, for this task.
Use these sections: Goal; Decisions and constraints; Workspace and revision;
Completed work and evidence paths; Pending work and blockers; Exact next action.
Preserve operator corrections, approved scope, dirty-worktree ownership, full-log
paths, and the distinction between executed checks and checks merely planned.
Use only facts already established in this session. Label missing or stale
revision/test facts unknown; do not guess or present old checks as fresh proof.
Return the checkpoint here. Do not write files, create another session, call
agents, rerun commands, or resume pending work. $ARGUMENTS may specify the focus.
```

Do not add an `agent` or model override: the command stays in the current session
and honors the selected primary role/model. This avoids an unnecessary mode switch.

- [ ] **Step 2: Verify native command discovery in isolation.**

Copy the template into the temporary config's `commands` directory. Inspect its
fresh effective `command.checkpoint` entry via native `debug config` privately.
Require the exact template, description, and no subtask/model/agent override.
Initialize ACP and create a fixture session without prompting; require the
available-command update to include checkpoint and tokenscope. No TUI endpoint
is used and no generation is required for discovery.

- [ ] **Step 3: Commit and deploy the template.**

Commit tracked `commands/checkpoint.md`; back up the global target or record its
absence, then copy the verified content using `apply_patch`. Require byte equality
and command discovery from a clean directory. The operator can manually invoke it
after restart; record that UI check rather than automating a paid model turn.

## Task 8: Integrated completion, comparison boundaries, and restart handoff

**Files:** `docs/v1-profile.md`; private completion receipt. No new runtime features.

**Interfaces:** Consumes all stage receipts and produces an honest operator handoff, tested source revision, rollback pointers, and pending external/UI checks.

- [ ] **Step 1: Verify the final effective configuration against the original baseline.**

Allowed deltas are exactly: canonical launcher selection in Zed, update guard,
one TokenScope entrypoint, one Context7 server, Explore/small-model routing, one
skill denial, the concise global guidance, and two native command files. Everything
else retains its prior value/order. Primary modes remain Build and Plan; General
and Explore remain built-in subagents; `fleet-tests` remains absent.

- [ ] **Step 2: Prove actual post-edit feedback through the local scripted provider.**

Use Task 3's finite `FixtureCall` helper with the existing native fixture's
isolated roots, SDK seed, real language-server configuration, ownership deadline,
and local provider. Start a fresh disposable Kotlin fixture with the corrected
source from Task 2. Let `file` be its absolute `src/main/kotlin/Probe.kt` path.
The cold sequence is exactly:

```typescript
const file = join(work, "src/main/kotlin/Probe.kt");
const calls: readonly FixtureCall[] = [
  { name: "edit", args: { filePath: file,
    oldString: "fun answer(): Int = 1", newString: 'fun answer(): Int = "wrong type"' } },
  { name: "edit", args: { filePath: file,
    oldString: 'fun answer(): Int = "wrong type"', newString: "fun answer(): Int = 1" } },
  { name: "lsp", args: { operation: "documentSymbol", filePath: file, line: 1, character: 1 } },
];
```

The warm sequence prepends `{ name: "read", args: { filePath: file } }` before the
same three calls in a separate fresh fixture. Grant only `read`, `edit`, and `lsp`
to those fixture sessions. Match provider tool messages by each emitted call ID,
not by concatenating all historical messages: the broken edit must deliver its
error block, the corrected edit must not repeat that mismatch, and navigation
must return `answer`. Verify the final on-disk source is corrected. Empty or stale
diagnostics stay incomplete; do not replace this gate with a symbols-only check.
Use separate focused native test cases; keep the existing Gradle assertions and
network discipline intact. Required dependency import that cannot use the isolated
cache is diagnosed and reported, not silently allowed to touch the real worktree.

- [ ] **Step 3: Run the standalone completion gate once after focused checks pass.**

```bash
bun test
bun run typecheck
node --test tests/pty-startup.test.mjs
git diff --check
```

Require no failing tests/type errors and owned-fixture cleanup. Existing PTY
policy/lifecycle and capture-first Gradle checks must still pass. Run no target
project Gradle task. Re-run only when a later code change or specific unresolved
failure requires it.

- [ ] **Step 4: Record distinct acceptance statuses.**

The receipt lists each gate as PASS, FAIL, or PENDING with exact evidence:

| Gate | Required evidence |
| --- | --- |
| Launcher | Exact argv/exit test and version 1.18.35 |
| ACP | Initialize response, clean stdout, owned process exit |
| Native capabilities | Catalog and model-facing lsp/websearch definitions |
| Real LSP | Deliberate Kotlin/C++ errors, corrected result, answer symbol, native cold/warm post-edit feedback |
| TokenScope | Installed-package native execution, private report, honest warnings |
| Routing/policy | Effective roles/skill permissions and unchanged unrelated authority |
| Docs | Context7 catalog and useful public queries, with actual version coverage |
| Commands | Native config and ACP available-command discovery |
| Existing tooling | Full standalone tests/typecheck and preservation checks |
| Zed/UI and search | Fresh operator connection and explicit manual checks |

Do not label the entire rollout verified while a required behavior gate is FAIL
or PENDING. A hosted/real-model/UI check not executed by the worker remains an
explicit handoff item; the source/config portions can be reported separately.

- [ ] **Step 5: Write the operator guide and source-side completion commit.**

The guide includes the terminal command `opencode-v1`, the fact that raw
`opencode` bypasses its flags, exact tested version/pins, all private backup
paths, usage of `/tokenscope` and `/checkpoint`, the overlap policy, Context7
version limits, and the distinction between telemetry and invoices. It documents
restoration of only the changed global files, never deletion of session storage.

Commit source/docs only and leave the reviewed runtime repository intact. Do not
push/merge through another control plane. Tell the operator to quit/restart the
terminal client and open a fresh OpenCode agent thread in Zed. Reuse the selected
execution workflow's single scoped/final review, not duplicate reviews merely
because both skill packs are installed.

## Plan-author self-review

- The five approved recommendations map to explicit tasks; fleet-tests is absent
  and V2 stays deferred.
- Superpowers/Ponytail overlap has concrete source evidence, precedence rules,
  retained verification, and a narrow global activation change.
- General's model inheritance is intentional; only bounded Explore/title routing
  changes, so an implementation-capable role is not silently downgraded.
- Configuration edits have exact fragments, preservation deltas, private backups,
  independent rollback points, and no fake config-unit-test ceremony.
- Tests reuse existing first-party fixture boundaries; no new orchestration or
  production LSP client is proposed.
- Registration, real diagnostics, hosted-service checks, and operator UI checks
  remain distinct. This document records planned gates, not deployment results.
- Runtime execution starts only after the operator chooses execution; authoring
  this plan does not modify global configuration or launch model calls.

### Authoring verification evidence

On 2026-10-11, temporary extraction of the plan examples verified eight JSON
blocks, eleven TypeScript blocks, two JavaScript blocks, seven shell blocks, and
three relative document links. The complete temporary launcher/protocol examples
passed **2 tests, 0 failures, 10 assertions**; the helper passed strict TypeScript
checking. These are example checks, not completed rollout tasks.

`./scripts/adkw check-backlog` and `git diff --check` passed. The plan is saved
under the existing `.gitignore:118:/docs/superpowers/` rule; the supporting design
is a new untracked document. No source/config file was staged or committed, and
the standalone runtime repository remained clean at its recorded base.
