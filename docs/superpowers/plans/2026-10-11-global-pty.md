---
document_type: execution_plan
base_revision: dc6f7bf44b68f494a4cb609702dc97c72fb892c4
---

# Global PTY Installation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Install globally available interactive/background terminal tools without implicit agent resumption or broader permissions.

**Architecture:** One user-level source repository contains a thin adapter around pinned `opencode-pty@0.4.0`. Global OpenCode configuration references that adapter. Its core tools are tested in isolation before activation; durable terminal logs use the existing `script` utility.

**Tech Stack:** Linux, OpenCode 1.18.35, Bun 1.4.2, Node 24.19.0, TypeScript, util-linux script 2.41.3.

**Spec:** [docs/internals/designs/core/global-build-tooling.md](../../internals/designs/core/global-build-tooling.md).

**Document type:** `execution_plan`

**Base revision:** `dc6f7bf44b68f494a4cb609702dc97c72fb892c4`

## Global Constraints

- Runtime changes are global; no target-project file or Gradle logic changes.
- Preserve the existing provider/model settings, MCP servers, plugins, and permissions.
- Never modify, filter, or handle `GITHUB_TOKEN`.
- Do not register the full OMO plugin or its orchestration/continuation hooks.
- Execute each requested command once; preserve its arguments, exit status, and native interruption/timeout information.
- Use pure decisions, closed outcomes, strict types, and minimum visibility.
- Validate with isolated HOME/XDG fixtures and local fake programs. No paid model calls.
- No automatic retries, task starts, merges, or prompt-driven continuation.
- Global settings and logs must not be committed into the tooling repository.
- Stop deployment and restore its configuration backup if live validation fails.

## File Map

All runtime source paths below are under `/home/leanid/.local/share/opencode/build-tooling`:

- `package.json`, `bun.lock`, `.gitignore`, `tsconfig.json`: standalone tooling/test dependencies, strict types, and source-control boundaries.
- `src/pty-policy.ts`: canonical workdir and notification-policy checks.
- `src/pty-profile.ts`: the sole OpenCode plugin entrypoint; delegates to upstream.
- `tests/pty-policy.test.ts`: policy and hook-preservation contracts.
- `tests/support/pty-fixture.ts`: owned fixture sessions and bounded lifecycle waits.
- `tests/pty-real.test.ts`: actual terminal execution, output, interruption, timeout, and durable logs.
- `tests/pty-startup.test.mjs`: first-party isolated server/tool-registration proof.
- `docs/pty-usage.md`: global operator/agent usage guidance.
- Modify `/home/leanid/.config/opencode/opencode.jsonc`: append plugin and narrow role overrides.

The current global configuration already contains RTK, notifier, and AST MCP.
Ponytail is inherited from `/home/leanid/.opencode/opencode.json`; AgentMemory and
jbcontext are inherited from `/home/leanid/.config/opencode/opencode.json`.
Preserve those files. The `fleet-tests` Markdown definition has `bash: ask`,
`edit: deny`, and `task: deny`; it must not acquire PTY execution authority.

## Task 1: Versioned global adapter and policy

**Files:** Create the repository bootstrap files, `src/pty-policy.ts`, `src/pty-profile.ts`, and `tests/pty-policy.test.ts`.

**Interfaces:**
- Consumes upstream `PTYPlugin` and OpenCode `Hooks`/`Plugin` types.
- Produces `withPtyPolicy(directory: string, hooks: Hooks): Promise<Hooks>` and a default plugin factory.
- Runtime entrypoint exports only the default factory; helper exports stay in another module.

- [ ] **Step 1: Check exact prerequisites and create the task-owned source workspace.**

```bash
opencode --version
bun --version
node --version
script --version
```

Expected: 1.18.35, 1.4.2, v24.19.0, and util-linux 2.41.3. If versions differ,
review compatibility before adapting; do not upgrade automatically.
If the tooling directory exists, inspect its Git status and use the
using-git-worktrees skill to obtain a task-owned worktree. Do not overwrite it.
For a new directory:

```bash
mkdir -p "$HOME/.local/share/opencode/build-tooling"
git -C "$HOME/.local/share/opencode/build-tooling" init --initial-branch=work/global-build-tooling
```

Create `package.json` (if bootstrapping a new repository):

```json
{
  "name": "user-opencode-build-tooling",
  "private": true,
  "type": "module",
  "scripts": { "test": "bun test", "typecheck": "tsc --noEmit" },
  "dependencies": { "opencode-pty": "0.4.0" },
  "devDependencies": {
    "@opencode-ai/plugin": "1.18.35",
    "@types/bun": "1.4.2",
    "typescript": "5.9.3"
  },
  "overrides": {
    "bun-pty": "0.4.10",
    "@opencode-ai/plugin": "1.18.35",
    "@opencode-ai/sdk": "1.18.35"
  }
}
```

For an existing repository, add these pinned dependency entries without replacing
its other fields. Create `.gitignore`:

```gitignore
node_modules/
.evidence/
```

Run `bun install --ignore-scripts` from that repository. Registry releases are
prebuilt; do not run development `prepare`/Rust build scripts automatically.
If the native PTY artifact is absent, stop and report the packaging failure.

Create `tsconfig.json`:

```json
{
  "compilerOptions": {
    "target": "ES2022",
    "module": "Preserve",
    "moduleResolution": "Bundler",
    "strict": true,
    "noEmit": true,
    "allowImportingTsExtensions": true,
    "types": ["bun"]
  },
  "include": ["src/**/*.ts", "tests/**/*.ts"]
}
```

- [ ] **Step 2: Add the failing policy tests.**

```typescript
// tests/pty-policy.test.ts
import { test, expect } from "bun:test";
import { mkdtemp, mkdir, rm, symlink } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { withPtyPolicy } from "../src/pty-policy.ts";

const input = { tool: "pty_spawn", sessionID: "fixture", callID: "call" };
test("preserves upstream hooks and defaults to no automatic prompt", async () => {
  const root = await mkdtemp(join(tmpdir(), "pty-policy-"));
  let calls = 0;
  const event = async () => {};
  try {
    const hooks = await withPtyPolicy(root, {
      event,
      "tool.execute.before": async () => { calls++; },
    });
    const output = { args: { command: "python3", args: ["-V"], workdir: root } };
    await hooks["tool.execute.before"]!(input, output);
    expect(hooks.event).toBe(event);
    expect(calls).toBe(1);
    expect(output.args).toEqual({ command: "python3", args: ["-V"], workdir: root, notifyOnExit: false });
    await expect(hooks["tool.execute.before"]!(input, {
      args: { command: "python3", args: ["-V"], notifyOnExit: true },
    })).rejects.toThrow("automatic");
  } finally { await rm(root, { recursive: true, force: true }); }
});

test("rejects sibling-prefix and symlink escapes; permits real descendants", async () => {
  const base = await mkdtemp(join(tmpdir(), "pty-boundary-"));
  const root = join(base, "project"), sibling = join(base, "project-other");
  await mkdir(root); await mkdir(sibling); await mkdir(join(root, "sub"));
  await symlink(sibling, join(root, "escape"));
  try {
    const hooks = await withPtyPolicy(root, {});
    for (const workdir of [sibling, join(root, "escape")]) {
      await expect(hooks["tool.execute.before"]!(input, { args: { workdir } })).rejects.toThrow("workspace");
    }
    await hooks["tool.execute.before"]!(input, { args: { workdir: join(root, "sub") } });
    const untouched = { args: { notifyOnExit: true } };
    await hooks["tool.execute.before"]!({ ...input, tool: "read" }, untouched);
    expect(untouched.args.notifyOnExit).toBe(true);
  } finally { await rm(base, { recursive: true, force: true }); }
});
```

- [ ] **Step 3: Run the red test.**

```bash
bun test tests/pty-policy.test.ts
```

Expected: missing `src/pty-policy.ts`. Dependency/native-loading errors are not
the intended red result; resolve packaging first without enabling the plugin.

- [ ] **Step 4: Implement the policy and entrypoint.**

```typescript
// src/pty-policy.ts
import type { Hooks } from "@opencode-ai/plugin";
import { realpath } from "node:fs/promises";
import { relative, isAbsolute, sep, resolve } from "node:path";

export async function withPtyPolicy(directory: string, hooks: Hooks): Promise<Hooks> {
  const root = await realpath(directory);
  return {
    ...hooks,
    "tool.execute.before": async (input, output) => {
      await hooks["tool.execute.before"]?.(input, output);
      if (input.tool !== "pty_spawn") return;
      const args = output.args as Record<string, unknown>;
      if (!args || typeof args !== "object" || Array.isArray(args)) throw new Error("Invalid PTY arguments");
      if (args.notifyOnExit === true) throw new Error("PTY automatic prompt notifications are disabled in this profile");
      if (args.notifyOnExit !== undefined && args.notifyOnExit !== false) throw new Error("Invalid PTY notifyOnExit");
      const requested = args.workdir === undefined ? root : args.workdir;
      if (typeof requested !== "string") throw new Error("Invalid PTY workspace directory");
      const target = await realpath(resolve(root, requested));
      const path = relative(root, target);
      if (path === ".." || path.startsWith(`..${sep}`) || isAbsolute(path)) throw new Error("PTY workdir escapes the workspace");
      args.notifyOnExit = false;
      args.workdir = target;
    },
  };
}
```

```typescript
// src/pty-profile.ts
import { PTYPlugin } from "opencode-pty";
import { withPtyPolicy } from "./pty-policy.ts";
import type { Plugin } from "@opencode-ai/plugin";

const plugin: Plugin = async (input) => withPtyPolicy(input.directory, await PTYPlugin(input));
export default plugin;
```

- [ ] **Step 5: Verify and commit this unit.**

```bash
bun test tests/pty-policy.test.ts
bun run typecheck
git add package.json bun.lock .gitignore tsconfig.json src/pty-policy.ts src/pty-profile.ts tests/pty-policy.test.ts
git commit -m "feat: add pinned global PTY profile policy"
```

Expected: both policy tests pass; no existing hook or command arguments lost.

## Task 2: Real terminal and durable-log checks

**Files:** Create `tests/support/pty-fixture.ts`, `tests/pty-real.test.ts`, and `docs/pty-usage.md`.

**Interfaces:** Consumes the default factory from Task 1. Calls upstream
`pty_spawn`, `pty_read`, `pty_list`, `pty_write`, and `pty_kill` through the
before-hook. Produces real execution evidence with no model/provider calls.

- [ ] **Step 1: Add the shared actual-plugin fixture.**

```typescript
// tests/support/pty-fixture.ts
import { mkdtemp, rm } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { randomUUID } from "node:crypto";
import plugin from "../../src/pty-profile.ts";

export async function createPtyFixture() {
  const root = await mkdtemp(join(tmpdir(), "pty-real-"));
  const prompts: unknown[] = [];
  const hooks = await plugin({
    directory: root, worktree: root,
    client: {
      config: { get: async () => ({ data: { permission: { bash: "allow", external_directory: "deny" } } }) },
      tui: { showToast: async () => ({}) },
      session: { get: async () => ({ data: {} }), promptAsync: async (value: unknown) => { prompts.push(value); } },
    },
  } as unknown as Parameters<typeof plugin>[0]); // Partial SDK test double; no network transport.
  const context = {
    sessionID: randomUUID(), messageID: "fixture", agent: "build",
    directory: root, worktree: root, abort: new AbortController().signal,
    metadata: () => {}, ask: async () => {},
  };
  const ids = new Set<string>();
  const call = async (name: string, args: Record<string, unknown>) => {
    const definition = hooks.tool?.[name];
    if (!definition) throw new Error(`Missing tool ${name}`);
    const output = { args };
    await hooks["tool.execute.before"]?.({ tool: name, sessionID: context.sessionID, callID: randomUUID() }, output);
    const result = await definition.execute(output.args, context);
    if (name === "pty_spawn") {
      const id = /^ID: (\S+)$/m.exec(String(result))?.[1];
      if (!id) throw new Error(String(result));
      ids.add(id);
    }
    return String(result);
  };
  const waitStopped = async (id: string) => {
    const deadline = Date.now() + 5000;
    while (Date.now() < deadline) {
      const listed = await call("pty_list", {});
      const start = listed.indexOf(`[${id}]`);
      const block = start < 0 ? "" : listed.slice(start).split("\n[")[0];
      if (/Status: (?:exited|killed)/.test(block)) return block;
      await Bun.sleep(25); // Bounded test wait, not an agent polling workflow.
    }
    throw new Error(`Owned PTY ${id} did not stop`);
  };
  const close = async () => {
    try {
      for (const id of ids) await call("pty_kill", { id, cleanup: true });
      await hooks.event?.({ event: { type: "session.deleted", properties: { info: { id: context.sessionID } } } });
    } finally { await rm(root, { recursive: true, force: true }); }
  };
  return { root, prompts, call, waitStopped, close };
}
```

Create `tests/pty-real.test.ts` with the first actual execution test:

```typescript
import { test, expect } from "bun:test";
import { writeFile, readFile, stat } from "node:fs/promises";
import { join } from "node:path";
import { createPtyFixture } from "./support/pty-fixture.ts";

test("real PTY preserves exit, searchable output, and script log", async () => {
  const fixture = await createPtyFixture();
  try {
    const log = join(fixture.root, "gradle.typescript");
    await writeFile(log, "", { mode: 0o600, flag: "wx" });
    const program = join(fixture.root, "fixture.py");
    await writeFile(program, 'print("Could not load GradleWorkerMain", flush=True)\nraise SystemExit(7)\n');
    const command = `python3 '${program.replaceAll("'", "'\\''")}'`;
    const started = await fixture.call("pty_spawn", {
      command: "script", args: ["--quiet", "--return", "--log-out", log, "--command", command],
      workdir: fixture.root, description: "Check fixture Gradle failure and log", timeoutSeconds: 10,
    });
    const id = /^ID: (\S+)$/m.exec(started)?.[1];
    if (!id) throw new Error(String(started));
    expect(await fixture.waitStopped(id)).toContain("exit: 7");
    const output = await fixture.call("pty_read", { id, pattern: "GradleWorkerMain", limit: 10 });
    expect(output).toContain("GradleWorkerMain");
    expect(await readFile(log, "utf8")).toContain("GradleWorkerMain");
    expect((await stat(log)).mode & 0o777).toBe(0o600);
    expect(fixture.prompts).toEqual([]);
  } finally { await fixture.close(); }
});
```

- [ ] **Step 2: Run and inspect the real terminal check.**

```bash
bun test tests/pty-real.test.ts
```

Expected: exit 7 is reported, diagnostic is searchable, complete log is present,
and no `promptAsync` invocation occurs. A native FFI error is a failed dependency
gate, not a reason to enable unverified tooling.

- [ ] **Step 3: Add the interruption/timeout regression using the same fixture interface.**

Append this complete regression test to `tests/pty-real.test.ts`:

```typescript
test("owned timeout and Ctrl-C executions stop without prompt notifications", async () => {
  for (const mode of ["timeout", "interrupt"] as const) {
    const fixture = await createPtyFixture();
    let pid: number | undefined;
    try {
      const program = join(fixture.root, "wait.py");
      await writeFile(program, 'import os,time\nprint("OWNED_PID="+str(os.getpid()),flush=True)\nwhile True: time.sleep(0.1)\n');
      const started = await fixture.call("pty_spawn", {
        command: "python3", args: ["-u", program], workdir: fixture.root,
        description: "Check isolated terminal timeout behavior",
        timeoutSeconds: mode === "timeout" ? 1 : 10,
      });
      const id = /^ID: (\S+)$/m.exec(started)?.[1];
      if (!id) throw new Error(started);
      const deadline = Date.now() + 5000;
      while (Date.now() < deadline && pid === undefined) {
        const output = await fixture.call("pty_read", { id, pattern: "OWNED_PID", limit: 10 });
        const match = /OWNED_PID=(\d+)/.exec(output);
        if (match) pid = Number(match[1]); else await Bun.sleep(25);
      }
      expect(pid).toBeDefined();
      if (mode === "interrupt") await fixture.call("pty_write", { id, data: "\u0003" });
      const stopped = await fixture.waitStopped(id);
      if (mode === "timeout") expect(stopped).toContain("timed out");
      expect(stopped).not.toContain("Status: running");
      expect(fixture.prompts).toEqual([]);
    } finally { await fixture.close(); }
    if (pid === undefined) throw new Error("Fixture PID was not observed");
    try {
      process.kill(pid, 0);
      throw new Error(`Owned fixture PID ${pid} survived cleanup`);
    } catch (error) {
      if ((error as NodeJS.ErrnoException).code !== "ESRCH") throw error;
    }
  }
});
```

This probes only the PID reported by the owned fixture. It never scans or signals
unrelated host processes. Run `bun test tests/pty-real.test.ts` and require both tests.

- [ ] **Step 4: Write the actual global usage guide.**

Create `docs/pty-usage.md`:

```markdown
# Global PTY profile

Use PTY for an explicitly authorized interactive or background command.
Supply the current workspace, the original argv, a description, and a finite
timeoutSeconds. Use pty_list for terminal state and pty_read with offset/limit
or pattern for relevant output. A spawn receipt is not success.

Automatic prompt notifications are disabled. Do not use sleep/read polling loops
in agent work. Native Bash remains the ordinary blocking build path and handles
commands that require its permission UI. No browser/TUI observer starts automatically.

For durable Gradle output, pre-create a mode-0600 file in
~/.local/state/opencode/gradle-logs. In an approved PTY invoke util-linux script
with --quiet --return --log-out, and the exact authorized command supplied to
--command. Do not use input logging. Read the file after terminal buffer cleanup.
Use the original exit status and timeout information; never infer success from a
spawned or merely non-running session.
```

- [ ] **Step 5: Commit the independently verified terminal unit.**

```bash
bun test tests/pty-policy.test.ts tests/pty-real.test.ts
bun run typecheck
git add tests/support/pty-fixture.ts tests/pty-real.test.ts docs/pty-usage.md
git commit -m "test: verify global PTY lifecycle and durable output"
```

## Task 3: Global deployment and live configuration proof

**Files:** Modify `/home/leanid/.config/opencode/opencode.jsonc`; create
`docs/pty-deployment.md` in the tooling repository with sanitized validation results.

**Interfaces:** Consumes the tested `src/pty-profile.ts`. Produces a single global
plugin registration and denies PTY mutators for existing read-only/gated roles.

- [ ] **Step 1: Back up only the files being changed.**

Use a new mode-0700 directory under
`/home/leanid/.local/state/opencode/setup-backups`; copy the current JSONC with
mode 0600. Record the backup path. Do not print or commit its contents.

```bash
python3 - <<'PY'
from pathlib import Path
import tempfile
base = Path.home() / '.local/state/opencode/setup-backups'
base.mkdir(parents=True, exist_ok=True)
backup = Path(tempfile.mkdtemp(prefix='pty-deployment-', dir=base))
backup.chmod(0o700)
target = backup / 'opencode.jsonc'
target.write_bytes((Path.home() / '.config/opencode/opencode.jsonc').read_bytes())
target.chmod(0o600)
print(backup)
PY
```

- [ ] **Step 2: Append the adapter and role overrides with apply_patch.**

Append this exact entry once to the existing `plugin` array:

```json
"file:///home/leanid/.local/share/opencode/build-tooling/src/pty-profile.ts"
```

Merge these fields into existing agent entries; do not replace their other fields:

```json
{
  "agent": {
    "plan": { "permission": { "pty_spawn": "deny", "pty_write": "deny", "pty_kill": "deny" } },
    "explore": { "permission": { "pty_spawn": "deny", "pty_write": "deny", "pty_kill": "deny" } },
    "fleet-tests": { "permission": { "pty_spawn": "deny", "pty_write": "deny", "pty_kill": "deny" } }
  }
}
```

Keep RTK, notifier, Ponytail, AgentMemory, jbcontext, AST MCP, and model/provider
settings. Do not register `opencode-pty` separately: that would duplicate the adapter.

- [ ] **Step 3: Validate effective role permissions.**

```bash
opencode debug agent plan
opencode debug agent explore
opencode debug agent fleet-tests
```

Inspect only role/model/permission fields. Require the three PTY mutator denials;
`fleet-tests` must retain `edit: deny`, `task: deny`, and `bash: ask` plus its
existing model. If file/JSON merge precedence drops these fields, restore the
backup and stop; do not compensate by weakening Bash permissions.

- [ ] **Step 4: Run first-party isolated startup proof.**

Run `opencode serve` in temporary HOME, OPENCODE_TEST_HOME, and all four XDG roots,
with a minimal temporary config referencing only the absolute adapter URI.
Disable auto-update/model fetching and external skill discovery for that fixture.
No model request is needed: check `/global/health`, `/config`, `/agent`, and
`/experimental/tool/ids`. Require healthy version 1.18.35, native build/plan roles,
and the five PTY tool IDs. A 404 is a failed compatibility gate. Bind localhost only;
terminate only the fixture server in finally. Verify real configuration and session
storage were unchanged. Record exact commands, HTTP results, and cleanup outcome
under the ignored `.evidence/` directory.

Create and run this concrete first-party fixture:

```javascript
// tests/pty-startup.test.mjs
import { test } from "node:test";
import assert from "node:assert/strict";
import { mkdtemp, mkdir, writeFile, readFile, rm } from "node:fs/promises";
import { tmpdir, homedir } from "node:os";
import { join, resolve } from "node:path";
import { pathToFileURL } from "node:url";
import { createServer } from "node:net";
import { spawn } from "node:child_process";
import { once } from "node:events";

test("isolated OpenCode registers PTY without touching real settings", { timeout: 45000 }, async () => {
  const realConfig = join(homedir(), ".config/opencode/opencode.jsonc");
  const before = await readFile(realConfig);
  const root = await mkdtemp(join(tmpdir(), "pty-startup-"));
  const home = join(root, "home"), work = join(root, "work");
  await mkdir(home); await mkdir(work);
  const config = {
    "$schema": "https://opencode.ai/config.json",
    plugin: [pathToFileURL(resolve("src/pty-profile.ts")).href],
    model: "fixture/mock", small_model: "fixture/mock", enabled_providers: ["fixture"],
    provider: { fixture: { npm: "@ai-sdk/openai-compatible", options: { baseURL: "http://127.0.0.1:9/v1", apiKey: "fixture" }, models: { mock: { name: "mock", tool_call: true, limit: { context: 64000, output: 4096 } } } } },
  };
  const file = join(root, "opencode.json"); await writeFile(file, JSON.stringify(config));
  const socket = createServer(); socket.listen(0, "127.0.0.1"); await once(socket, "listening");
  const address = socket.address(); assert(address && typeof address !== "string");
  const port = address.port; await new Promise((done) => socket.close(done));
  const env = {
    ...process.env, HOME: home, OPENCODE_TEST_HOME: home,
    XDG_CONFIG_HOME: join(root, "config"), XDG_DATA_HOME: join(root, "data"),
    XDG_STATE_HOME: join(root, "state"), XDG_CACHE_HOME: join(root, "cache"),
    OPENCODE_CONFIG: file, OPENCODE_CONFIG_DIR: join(root, "empty-config"),
    OPENCODE_CONFIG_CONTENT: JSON.stringify(config),
    OPENCODE_DISABLE_AUTOUPDATE: "1", OPENCODE_DISABLE_MODELS_FETCH: "1",
    OPENCODE_DISABLE_EXTERNAL_SKILLS: "1", OPENCODE_DISABLE_CLAUDE_CODE: "1",
    OPENCODE_SERVER_PASSWORD: "fixture-password",
  };
  delete env.OPENCODE_PURE;
  const child = spawn("opencode", ["serve", "--hostname", "127.0.0.1", "--port", String(port)], { cwd: work, env });
  let output = "";
  child.stdout.on("data", (chunk) => { output += chunk; });
  child.stderr.on("data", (chunk) => { output += chunk; });
  const headers = { Authorization: "Basic " + Buffer.from("opencode:fixture-password").toString("base64") };
  const get = async (path) => {
    const response = await fetch(`http://127.0.0.1:${port}${path}`, { headers, signal: AbortSignal.timeout(2000) });
    assert.equal(response.status, 200, `${path}: ${response.status}`);
    return response.json();
  };
  try {
    const deadline = Date.now() + 25000;
    let health;
    while (Date.now() < deadline) {
      try { health = await get("/global/health"); break; } catch {
        if (child.exitCode !== null) throw new Error(output);
        await new Promise((done) => setTimeout(done, 50));
      }
    }
    assert.equal(health?.healthy, true, output); assert.equal(health.version, "1.18.35");
    const ids = await get("/experimental/tool/ids");
    for (const name of ["pty_spawn", "pty_write", "pty_read", "pty_list", "pty_kill"]) assert(ids.includes(name), name);
    const agents = await get("/agent");
    assert(agents.some((agent) => agent.name === "build"));
    assert(agents.some((agent) => agent.name === "plan"));
  } finally {
    child.kill("SIGTERM");
    await Promise.race([once(child, "exit"), new Promise((done) => setTimeout(done, 5000))]);
    if (child.exitCode === null && child.signalCode === null) { child.kill("SIGKILL"); await once(child, "exit"); }
    await mkdir(".evidence", { recursive: true });
    await writeFile(".evidence/pty-startup.log", output);
    assert.deepEqual(await readFile(realConfig), before);
    await rm(root, { recursive: true, force: true });
  }
});
```

```bash
node --test tests/pty-startup.test.mjs
```

Expected: first-party startup succeeds, all five tools exist, and real settings
are byte-identical. The dummy provider endpoint cannot generate a model response.

- [ ] **Step 5: Record and commit source-side deployment evidence.**

Write `docs/pty-deployment.md` with package versions, test counts, first-party
route results, effective permission results, backup path, and residual limits.
No raw private settings or logs go into Git.

```bash
git add tests/pty-startup.test.mjs docs/pty-deployment.md
git commit -m "docs: record global PTY deployment checks"
```

Tell the operator to quit and restart OpenCode. Do not run mazewall's Gradle build.

## Plan-author self-review

- The repository-required execution-plan frontmatter records the observed base revision.
- The Markdown body follows the requested writing-plans header and checkbox task format.
- All TypeScript/JavaScript example blocks parse; JSON fragments parse.
- Extracted policy/formatter/archive examples from both plans passed 10 isolated Bun tests.
- Real PTY/native-library tests, strict type checking, and first-party deployment QA
  remain implementation gates. No runtime configuration was changed while authoring.
