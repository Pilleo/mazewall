---
document_type: execution_plan
base_revision: dc6f7bf44b68f494a4cb609702dc97c72fb892c4
---

# Lossless Global Gradle Output Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Reduce routine Gradle output globally while retaining every native diagnostic and the original execution result.

**Architecture:** Native Bash/shell executes the original Gradle command. A global post-execution hook archives complete small results or reuses native large-output archives, then removes only successful task-progress noise. Failure and incomplete-result text stays unfiltered; no command is rerun to recover logs.

**Tech Stack:** Linux, OpenCode 1.18.35, Bun 1.4.2, Node 24.19.0, TypeScript and Node standard-library filesystem/crypto APIs.

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

## Scope and baseline

This plan executes independently of the PTY plan. Its runtime source lives in
`/home/leanid/.local/share/opencode/build-tooling`. The current RTK exclusions
remain: the native command must reach this hook unfiltered. This adds dedicated
Gradle optimization; it does not remove or fork RTK.

OpenCode 1.18.35 passes `tool`, `sessionID`, `callID`, and `args` to
`tool.execute.after`. The result has `title`, `output`, and `metadata`.
Native shell metadata provides `exit`, `truncated`, and, for archived truncation,
`outputPath`. Interruption/timeout may produce a null exit and a native
`<shell_metadata>` explanation. Preserve that explanation; do not guess success.

## File Map

Under the global tooling repository:

- `package.json`, `.gitignore`, `tsconfig.json`: standalone bootstrap, dependencies for type checking, and source-control boundaries.
- `src/gradle-format.ts`: pure recognition, outcome classification, and successful-progress condensation.
- `src/gradle-output.ts`: explicit archival boundary and post-tool hook.
- `src/gradle-output-plugin.ts`: sole exported runtime factory.
- `tests/gradle-format.test.ts`: recognition/outcome/unknown-line contracts.
- `tests/gradle-output.test.ts`: filesystem capture, truncation reuse, and fallback contracts.
- `tests/gradle-live.test.ts`: real isolated OpenCode tool execution against a local scripted model.
- `tests/support/fake-provider.ts`: Chat Completions SSE fixture; local only.
- `docs/gradle-output.md`: operator usage, log location, limits, and deployment evidence.
- Modify `/home/leanid/.config/opencode/opencode.jsonc`: append this global hook only.

## Task 1: Pure Gradle recognition and presentation

**Files:** Create `src/gradle-format.ts` and `tests/gradle-format.test.ts`; bootstrap the global repository only when necessary.

**Interfaces:**
- Produces `isSimpleGradleCommand(command: string): boolean`.
- Produces closed `Outcome` and `classifyExit(exit: unknown): Outcome`.
- Produces `condenseProgress(text: string, outcome: Outcome): { text: string; removed: number }`.
- Does not execute commands, read files, or infer task/test execution from progress text.

- [ ] **Step 1: Check prerequisites and obtain a task-owned source workspace.**

```bash
opencode --version
bun --version
node --version
```

Expected: 1.18.35, 1.4.2, v24.19.0. Inspect an existing tooling repository and
use using-git-worktrees for isolated work. For a new one:

```bash
mkdir -p "$HOME/.local/share/opencode/build-tooling"
git -C "$HOME/.local/share/opencode/build-tooling" init --initial-branch=work/global-build-tooling
```

Create `package.json` only for a new repository:

```json
{
  "name": "user-opencode-build-tooling",
  "private": true,
  "type": "module",
  "scripts": { "test": "bun test", "typecheck": "tsc --noEmit" },
  "devDependencies": { "typescript": "5.9.3", "@types/bun": "1.4.2" }
}
```

Create `.gitignore` with `node_modules/` and `.evidence/`. Existing PTY dependencies
and files stay intact. This component requires no new runtime dependency.

Create `tsconfig.json` if absent, preserving an existing strict configuration:

```json
{
  "compilerOptions": {
    "target": "ES2022", "module": "Preserve", "moduleResolution": "Bundler",
    "strict": true, "noEmit": true, "allowImportingTsExtensions": true,
    "types": ["bun"]
  },
  "include": ["src/**/*.ts", "tests/**/*.ts"]
}
```

Run `bun install --ignore-scripts` in the tooling repository. When bootstrapping
an existing repository, merge the two dev dependencies and typecheck script
without replacing its dependencies or test scripts.

- [ ] **Step 2: Write the failing pure tests.**

```typescript
// tests/gradle-format.test.ts
import { test, expect } from "bun:test";
import { isSimpleGradleCommand, classifyExit, condenseProgress } from "../src/gradle-format.ts";

test("recognizes simple invocations conservatively", () => {
  for (const command of ["./gradlew build", "gradle test", "/tmp/project/gradlew :enforcer:test --tests '*ContractTest'"]) {
    expect(isSimpleGradleCommand(command)).toBe(true);
  }
  for (const command of ["rg gradlew README.md", "cd app && ./gradlew build", "./gradlew build | head", "./gradlew build\necho done"]) {
    expect(isSimpleGradleCommand(command)).toBe(false);
  }
});

test("unknown or interrupted exits never become success", () => {
  expect(classifyExit(0)).toEqual({ kind: "success", code: 0 });
  expect(classifyExit(7)).toEqual({ kind: "failure", code: 7 });
  for (const exit of [null, undefined, -1, 0.5, "0"]) {
    expect(classifyExit(exit)).toEqual({ kind: "incomplete", code: null });
  }
});

test("success removes task progress, not unknown diagnostics or report paths", () => {
  const text = "> Task :compile UP-TO-DATE\n> Task :test FROM-CACHE\nUnusual diagnostic without an error prefix\nwarning: fixture\nfile:///tmp/report.html\nBUILD SUCCESSFUL in 1s\n";
  const result = condenseProgress(text, classifyExit(0));
  expect(result.removed).toBe(2);
  expect(result.text).not.toContain("> Task :compile");
  for (const line of ["Unusual diagnostic", "warning: fixture", "file:///tmp/report.html", "BUILD SUCCESSFUL"]) {
    expect(result.text).toContain(line);
  }
  expect(condenseProgress("> Task :ignoredFailure FAILED\n", classifyExit(0)).removed).toBe(0);
});

test("failure and incomplete text are untouched", () => {
  const text = "> Task :test FAILED\nCould not load GradleWorkerMain\nCaused by: something unusual\n";
  for (const exit of [1, 130, null]) expect(condenseProgress(text, classifyExit(exit))).toEqual({ text, removed: 0 });
});
```

- [ ] **Step 3: Verify the red result.**

```bash
bun test tests/gradle-format.test.ts
```

Expected: missing formatter module, not a dependency or permission failure.

- [ ] **Step 4: Implement the pure functions.**

```typescript
// src/gradle-format.ts
export type Outcome =
  | { readonly kind: "success"; readonly code: 0 }
  | { readonly kind: "failure"; readonly code: number }
  | { readonly kind: "incomplete"; readonly code: null };

export function classifyExit(exit: unknown): Outcome {
  if (typeof exit !== "number" || !Number.isInteger(exit) || exit < 0) return { kind: "incomplete", code: null };
  return exit === 0 ? { kind: "success", code: 0 } : { kind: "failure", code: exit };
}

export function isSimpleGradleCommand(command: string): boolean {
  // shortcut: compound/redirected commands pass unchanged; add shell-aware recognition only if needed.
  if (/[;&|`<>\r\n]/u.test(command)) return false;
  const executable = command.trimStart().split(/\s+/u)[0];
  return /(?:^|\/)gradlew?$/.test(executable);
}

export function condenseProgress(text: string, outcome: Outcome): { text: string; removed: number } {
  switch (outcome.kind) {
    case "failure":
    case "incomplete": return { text, removed: 0 };
    case "success": return condenseSuccess(text);
  }
  const exhaustive: never = outcome;
  return exhaustive;
}

function condenseSuccess(text: string): { text: string; removed: number } {
  let removed = 0;
  const kept = text.split("\n").filter((line) => {
    const progress = /^> Task :\S+(?: (?:UP-TO-DATE|FROM-CACHE|NO-SOURCE|SKIPPED))?$/.test(line.replace(/\r$/, ""));
    if (progress) removed++;
    return !progress;
  });
  return { text: kept.join("\n"), removed };
}
```

- [ ] **Step 5: Verify and commit the pure unit.**

```bash
bun test tests/gradle-format.test.ts
bun run typecheck
git add package.json bun.lock .gitignore tsconfig.json src/gradle-format.ts tests/gradle-format.test.ts
git commit -m "feat: add conservative Gradle output presentation"
```

If bootstrap files already tracked and unchanged, Git stages no changes to them.

## Task 2: Lossless archival and hook

**Files:** Create `src/gradle-output.ts`, `src/gradle-output-plugin.ts`, and `tests/gradle-output.test.ts`.

**Interfaces:**
- Consumes Task 1's functions and native result metadata.
- Produces `createGradleAfterHook(logDirectory?: string)` for tests and runtime.
- Runtime entrypoint exports only a default plugin factory; helper functions are not registered as plugin factories.
- Leaves input arguments, title, and metadata untouched.

- [ ] **Step 1: Add the filesystem regression tests.**

```typescript
// tests/gradle-output.test.ts
import { test, expect } from "bun:test";
import { mkdtemp, readFile, writeFile, readdir, rm, stat } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { createGradleAfterHook } from "../src/gradle-output.ts";

const input = { tool: "bash", sessionID: "fixture", callID: "call", args: { command: "./gradlew build" } };
const logPath = (text: string) => {
  const path = /^Full unfiltered log: (.+)$/m.exec(text)?.[1];
  if (!path) throw new Error(text);
  return path;
};

test("short failure is archived exactly and its original metadata/arguments survive", async () => {
  const root = await mkdtemp(join(tmpdir(), "gradle-hook-"));
  const metadata = { exit: 7, truncated: false, custom: "keep" };
  const raw = "Could not load GradleWorkerMain\n";
  const output = { title: "build", output: raw, metadata };
  const before = JSON.stringify(input);
  try {
    await createGradleAfterHook(root)(input, output);
    const path = logPath(output.output);
    expect(await readFile(path, "utf8")).toBe(raw);
    expect((await stat(path)).mode & 0o777).toBe(0o600);
    expect(output.output).toContain(raw);
    expect(output.output).toContain("Gradle outcome: failure (exit 7)");
    expect(output.metadata).toBe(metadata);
    expect(JSON.stringify(input)).toBe(before);
  } finally { await rm(root, { recursive: true, force: true }); }
});

test("native truncation reuses complete archive; counts are preview-only", async () => {
  const root = await mkdtemp(join(tmpdir(), "gradle-native-"));
  const file = join(root, "native.log");
  const raw = "EARLY_DIAGNOSTIC\n" + "> Task :test FROM-CACHE\n".repeat(5000);
  await writeFile(file, raw);
  const output = { title: "build", output: "> Task :test FROM-CACHE\nBUILD SUCCESSFUL\n", metadata: { exit: 0, truncated: true, outputPath: file } };
  try {
    await createGradleAfterHook(join(root, "new-logs"))(input, output);
    expect(logPath(output.output)).toBe(file);
    expect(await readFile(file, "utf8")).toBe(raw);
    expect(output.output).toContain("visible preview");
    expect(output.output).toContain("BUILD SUCCESSFUL");
    expect(await readdir(root)).toEqual(["native.log"]);
  } finally { await rm(root, { recursive: true, force: true }); }
});

test("null exit preserves native interruption text; empty output still has an archive", async () => {
  const root = await mkdtemp(join(tmpdir(), "gradle-incomplete-"));
  try {
    const output = { title: "build", output: "<shell_metadata>User aborted the command</shell_metadata>", metadata: { exit: null } };
    await createGradleAfterHook(root)(input, output);
    expect(output.output).toContain("User aborted");
    expect(output.output).toContain("Gradle outcome: incomplete");
    const empty = { title: "build", output: "", metadata: { exit: 0 } };
    await createGradleAfterHook(root)(input, empty);
    expect(await readFile(logPath(empty.output), "utf8")).toBe("");
  } finally { await rm(root, { recursive: true, force: true }); }
});

test("capture errors retain the entire visible result; unrelated tools are not captured", async () => {
  const root = await mkdtemp(join(tmpdir(), "gradle-fallback-"));
  const blocked = join(root, "blocked"); await writeFile(blocked, "file, not a directory");
  try {
    const raw = "> Task :compile UP-TO-DATE\nwarning: keep me\nBUILD SUCCESSFUL\n";
    const output = { title: "build", output: raw, metadata: { exit: 0 } };
    await createGradleAfterHook(blocked)(input, output);
    expect(output.output).toContain(raw);
    expect(output.output).toContain("capture failed");
    expect(output.output).not.toContain("Full unfiltered log:");
    expect(output.metadata.exit).toBe(0);
    const untouched = { title: "read", output: raw, metadata: {} };
    await createGradleAfterHook(join(root, "unused"))({ ...input, tool: "pty_read" }, untouched);
    expect(untouched.output).toBe(raw);
    expect((await readdir(root)).sort()).toEqual(["blocked"]);
  } finally { await rm(root, { recursive: true, force: true }); }
});
```

- [ ] **Step 2: Run the red tests.**

```bash
bun test tests/gradle-output.test.ts
```

Expected: missing hook module. An unrelated formatter regression must be fixed
before proceeding, not suppressed.

- [ ] **Step 3: Implement archival before presentation.**

```typescript
// src/gradle-output.ts
import { mkdir, writeFile, stat, access } from "node:fs/promises";
import { constants } from "node:fs";
import { homedir } from "node:os";
import { join, isAbsolute } from "node:path";
import { randomUUID } from "node:crypto";
import { isSimpleGradleCommand, classifyExit, condenseProgress } from "./gradle-format.ts";

type HookInput = { tool: string; sessionID: string; callID: string; args: unknown };
type HookOutput = { title: string; output: string; metadata: unknown };
const record = (value: unknown): value is Record<string, unknown> => value !== null && typeof value === "object" && !Array.isArray(value);

export function createGradleAfterHook(logDirectory = join(homedir(), ".local", "state", "opencode", "gradle-logs")) {
  return async (input: HookInput, output: HookOutput): Promise<void> => {
    if (input.tool !== "bash" && input.tool !== "shell") return;
    if (!record(input.args) || typeof input.args.command !== "string" || !isSimpleGradleCommand(input.args.command)) return;
    const metadata = record(output.metadata) ? output.metadata : {};
    let path: string;
    try {
      if (metadata.truncated === true) {
        if (typeof metadata.outputPath !== "string" || !isAbsolute(metadata.outputPath) || !(await stat(metadata.outputPath)).isFile()) {
          throw new Error("Native truncation has no readable regular-file archive");
        }
        path = metadata.outputPath;
        await access(path, constants.R_OK);
      } else {
        await mkdir(logDirectory, { recursive: true, mode: 0o700 });
        path = join(logDirectory, `${randomUUID()}.log`);
        await writeFile(path, output.output, { flag: "wx", mode: 0o600 });
      }
    } catch (error) {
      const message = error instanceof Error ? error.message : String(error);
      output.output += `\nGradle output capture failed: ${message}. Original output is uncompressed.\n`;
      return;
    }
    const outcome = classifyExit(metadata.exit);
    const condensed = condenseProgress(output.output, outcome);
    const scope = metadata.truncated === true ? "visible preview" : "complete visible output";
    output.output = `${condensed.text}\nGradle outcome: ${outcome.kind}${outcome.code === null ? "" : ` (exit ${outcome.code})`}\nTask progress lines omitted: ${condensed.removed} (${scope}; not a test execution count)\nFull unfiltered log: ${path}\n`;
  };
}
```

```typescript
// src/gradle-output-plugin.ts
import { createGradleAfterHook } from "./gradle-output.ts";

export default async () => ({ "tool.execute.after": createGradleAfterHook() });
```

- [ ] **Step 4: Verify capture/presentation and commit.**

```bash
bun test tests/gradle-format.test.ts tests/gradle-output.test.ts
bun run typecheck
git add src/gradle-output.ts src/gradle-output-plugin.ts tests/gradle-output.test.ts
git commit -m "feat: archive Gradle output before global presentation"
```

Inspect mode-0600 logs in the tests. No archive should contain a synthesized
summary instead of the original text. No title, arguments, or metadata change.

## Task 3: Real OpenCode execution through a local scripted provider

**Files:** Create `tests/support/fake-provider.ts` and `tests/gradle-live.test.ts`.

**Interfaces:** The local provider issues one native Bash tool call, records the
result delivered back to the model, then finishes. It never contacts a remote
provider. The fake `gradlew` writes a marker once, prints known output, and exits
with a known status. Side effects and stored text, not model claims, prove behavior.

- [ ] **Step 1: Add the local SSE provider.**

```typescript
// tests/support/fake-provider.ts
export function fakeProvider(command: string) {
  const requests: Record<string, unknown>[] = [];
  const server = Bun.serve({
    hostname: "127.0.0.1", port: 0,
    async fetch(request) {
      if (!new URL(request.url).pathname.endsWith("/chat/completions")) return new Response("Unexpected endpoint", { status: 400 });
      const body = await request.json() as Record<string, unknown>;
      requests.push(body);
      const messages = Array.isArray(body.messages) ? body.messages : [];
      const tools = Array.isArray(body.tools) ? body.tools : [];
      const available = tools.map((item) => (item as { function?: { name?: string } }).function?.name);
      const hasResult = messages.some((item) => (item as { role?: string }).role === "tool");
      const invoke = !hasResult && available.includes("bash");
      const delta = invoke
        ? { role: "assistant", tool_calls: [{ index: 0, id: "fixture-call", type: "function", function: { name: "bash", arguments: JSON.stringify({ command, description: "Execute isolated fixture once" }) } }] }
        : { role: "assistant", content: "Fixture finished." };
      const chunk = (value: unknown, finish: string | null) => ({ id: "fixture", object: "chat.completion.chunk", created: 1, model: "mock", choices: [{ index: 0, delta: value, finish_reason: finish }] });
      const text = `data: ${JSON.stringify(chunk(delta, null))}\n\ndata: ${JSON.stringify(chunk({}, invoke ? "tool_calls" : "stop"))}\n\ndata: [DONE]\n\n`;
      return new Response(text, { headers: { "Content-Type": "text/event-stream" } });
    },
  });
  return { url: server.url.toString(), requests, close: () => server.stop(true) };
}
```

- [ ] **Step 2: Add the live native-tool test.**

```typescript
// tests/gradle-live.test.ts
import { test, expect } from "bun:test";
import { mkdtemp, mkdir, writeFile, readFile, rm } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join, resolve } from "node:path";
import { pathToFileURL } from "node:url";
import { fakeProvider } from "./support/fake-provider.ts";

test("actual OpenCode executes once and delivers archived failure evidence", async () => {
  const root = await mkdtemp(join(tmpdir(), "gradle-live-"));
  const work = join(root, "work"), home = join(root, "home");
  await mkdir(work); await mkdir(home);
  const marker = join(work, "executions");
  const raw = "Could not load GradleWorkerMain\n";
  await writeFile(join(work, "gradlew"), `#!/bin/sh\nprintf x >> '${marker}'\nprintf '${raw.replaceAll("\n", "\\n")}'\nexit 7\n`, { mode: 0o755 });
  const provider = fakeProvider("./gradlew build");
  const config = {
    "$schema": "https://opencode.ai/config.json",
    plugin: [pathToFileURL(resolve("src/gradle-output-plugin.ts")).href],
    model: "fixture/mock", small_model: "fixture/mock", enabled_providers: ["fixture"],
    permission: { "*": "allow" },
    provider: { fixture: { npm: "@ai-sdk/openai-compatible", options: { baseURL: provider.url + "v1", apiKey: "fixture" }, models: { mock: { name: "mock", tool_call: true, limit: { context: 64000, output: 4096 } } } } },
  };
  const configFile = join(root, "opencode.json"); await writeFile(configFile, JSON.stringify(config));
  const env: Record<string, string | undefined> = {
    ...process.env, HOME: home, OPENCODE_TEST_HOME: home,
    XDG_CONFIG_HOME: join(root, "config"), XDG_DATA_HOME: join(root, "data"),
    XDG_STATE_HOME: join(root, "state"), XDG_CACHE_HOME: join(root, "cache"),
    OPENCODE_CONFIG: configFile, OPENCODE_CONFIG_DIR: join(root, "empty-config"),
    OPENCODE_CONFIG_CONTENT: JSON.stringify(config),
    OPENCODE_DISABLE_AUTOUPDATE: "1", OPENCODE_DISABLE_MODELS_FETCH: "1",
    OPENCODE_DISABLE_EXTERNAL_SKILLS: "1", OPENCODE_DISABLE_CLAUDE_CODE: "1",
  };
  delete env.OPENCODE_PURE; // The fixture must load the one selected plugin.
  try {
    const child = Bun.spawn(["opencode", "run", "--format", "json", "--agent", "build", "--model", "fixture/mock", "Run ./gradlew build once; do not edit files or retry."], {
      cwd: work, env, stdout: "pipe", stderr: "pipe",
    });
    const timeout = setTimeout(() => child.kill(), 45000);
    let code: number;
    try {
      const out = new Response(child.stdout).text(), err = new Response(child.stderr).text();
      code = await child.exited;
      await writeFile(join(root, "cli.stdout"), await out);
      await writeFile(join(root, "cli.stderr"), await err);
    } finally { clearTimeout(timeout); }
    expect(code).toBe(0); // Harness completion; actual command failure is checked below.
    expect(await readFile(marker, "utf8")).toBe("x");
    const toolText = provider.requests.flatMap((body) => Array.isArray(body.messages) ? body.messages : [])
      .filter((message) => (message as { role?: string }).role === "tool")
      .map((message) => JSON.stringify((message as { content?: unknown }).content)).join("\n");
    expect(toolText).toContain("Gradle outcome: failure (exit 7)");
    expect(toolText).toContain("GradleWorkerMain");
    const logRoot = join(home, ".local/state/opencode/gradle-logs");
    const files = await Array.fromAsync(new Bun.Glob("*.log").scan(logRoot));
    expect(files.length).toBe(1);
    expect(await readFile(join(logRoot, files[0]), "utf8")).toContain(raw);
  } finally {
    provider.close();
    await rm(root, { recursive: true, force: true });
  }
}, 60000);
```

The fake provider may receive harmless title/summary requests; it returns text
unless native Bash is offered and no tool result exists. Do not count those as
command execution. If the installed compatible-provider transport differs, retain
the failed capture and report it rather than switching to a real provider.

- [ ] **Step 3: Verify first-party behavior and commit.**

```bash
bun test tests/gradle-live.test.ts
bun test tests/gradle-format.test.ts tests/gradle-output.test.ts tests/gradle-live.test.ts
bun run typecheck
git add tests/support/fake-provider.ts tests/gradle-live.test.ts
git commit -m "test: prove Gradle archival through native OpenCode"
```

Expected: one marker byte, actual tool failure exit 7 in model-visible evidence,
one complete small log, and only local provider traffic.

## Task 4: Global activation and documentation

**Files:** Modify `/home/leanid/.config/opencode/opencode.jsonc`; create `docs/gradle-output.md` in the tooling repository.

**Interfaces:** Consumes the tested runtime entrypoint. Produces one global
after-hook registration, with existing RTK command exclusions unchanged.

- [ ] **Step 1: Back up the current global JSONC privately.**

Create a new mode-0700 directory under
`/home/leanid/.local/state/opencode/setup-backups`; copy the current JSONC as
mode 0600 and record its location. Do not copy it into Git or print private fields.

```bash
python3 - <<'PY'
from pathlib import Path
import tempfile
base = Path.home() / '.local/state/opencode/setup-backups'
base.mkdir(parents=True, exist_ok=True)
backup = Path(tempfile.mkdtemp(prefix='gradle-output-deployment-', dir=base))
backup.chmod(0o700)
target = backup / 'opencode.jsonc'
target.write_bytes((Path.home() / '.config/opencode/opencode.jsonc').read_bytes())
target.chmod(0o600)
print(backup)
PY
```

- [ ] **Step 2: Insert this entry first in the existing plugin array using apply_patch.**

```diff
   "plugin": [
+    "file:///home/leanid/.local/share/opencode/build-tooling/src/gradle-output-plugin.ts",
```

Do not replace the array. Preserve the PTY entry if its separate plan has landed,
as well as RTK, notifier, and inherited Ponytail. Existing MCP/provider/model
configuration remains byte-identical outside this patch. Keep RTK's Gradle
exclusions: pre-filtering defeats archival. Ordering this post-hook first prevents
another future display hook from removing text before capture.

- [ ] **Step 3: Validate the effective configuration without printing credentials.**

```bash
opencode debug config
```

Capture that output privately and inspect only plugin names, MCP names, and
changed-field comparisons. Require exactly one Gradle hook URI, all previous
plugins/MCPs, and unchanged provider/model/permission fields. Do not certify a
successful project build from this configuration check.

- [ ] **Step 4: Write the complete operator guide.**

Create `docs/gradle-output.md`:

```markdown
# Lossless global Gradle output

Run the original authorized Gradle command through native Bash. The global
after-hook captures small results or reuses native large-output archives before
changing presentation. It never executes or retries a command.

Success condenses only ordinary task progress. Failure/incomplete text stays
unfiltered. Full unfiltered log identifies the recoverable source; use existing
Read/Grep tools for needed ranges. Preview counts are not execution evidence.

RTK excludes Gradle intentionally: this after-hook is the Gradle optimization
path. Do not remove those exclusions without proving raw retention before any
pre-filter. Compound/redirected commands pass unchanged.

For PTY builds, use the durable script log documented in pty-usage.md; a rolling
PTY buffer is not permanent evidence. Do not interpret spawn output as success.

Inspect disk use with du -sh ~/.local/state/opencode/gradle-logs. Cleanup is an
explicit operator action after evidence is no longer needed; no deletion job ships.
Global settings/backups and raw logs never go into this source repository.
```

If PTY has not landed, change that paragraph to state the PTY plan is optional and
link the saved plan; do not create a dangling `pty-usage.md` reference.

- [ ] **Step 5: Commit documentation and hand off restart.**

```bash
git add docs/gradle-output.md
git commit -m "docs: describe global lossless Gradle presentation"
```

Tell the operator to quit/restart OpenCode. The first real project build remains
an operator-requested task; do not rerun the blocked mazewall build as part of this
global deployment. If configuration validation fails, restore the recorded backup
and report the failure before any further feature activation.

## Plan-author self-review

- The repository-required execution-plan frontmatter records the observed base revision.
- All implementation/test code blocks and JSON fragments parse.
- The extracted pure policy, formatter, and filesystem archive examples passed
  10 isolated Bun tests with zero failures; no deployed hook or project build ran.
- Strict type checking, the fake-provider live harness test, and global deployment
  comparisons remain implementation gates, not already-certified behavior.
