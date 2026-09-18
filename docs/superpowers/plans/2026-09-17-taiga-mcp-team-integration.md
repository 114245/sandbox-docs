# Taiga MCP Team Integration Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Provide one repository-level Taiga MCP setup that works from Claude Code, Google Antigravity, and Codex on Windows and Linux without committing credentials.

**Architecture:** All three clients launch the same Node.js wrapper over MCP STDIO. The wrapper loads per-user credentials from `.env.taiga.local`, validates only the required variable names, and starts the pinned `@illodev/taiga-mcp@1.0.0` package with platform-appropriate `npx`; the package then calls Taiga's HTTPS API.

**Tech Stack:** Node.js 20+, Node built-in test runner, JSON client configuration, TOML Codex configuration, `@illodev/taiga-mcp@1.0.0`, Taiga REST API v1.

**Spec:** `docs/superpowers/specs/2026-09-17-taiga-mcp-team-integration-design.md`

## Global Constraints

- Support Windows and Linux with Node.js 20 or later.
- Pin the MCP package to `@illodev/taiga-mcp@1.0.0`.
- Use `https://api.taiga.io` as `TAIGA_URL` and `jcarreggio1-test-mcp` as the test project slug.
- Keep `.env.taiga.local` and all passwords outside version control.
- Do not delete Taiga data or modify memberships, roles, imports, exports, or webhooks during validation.
- Prefix created validation artifacts with `[MCP TEST]`.
- Do not execute `git add`, `git commit`, `git push`, or branch-changing commands; provide exact optional commands to the user at handoff.

---

### Task 1: Cross-platform MCP launcher

**Files:**
- Create: `scripts/start-taiga-mcp.mjs`
- Create: `tests/start-taiga-mcp.test.mjs`

**Interfaces:**
- Consumes: `.env.taiga.local` at the repository root and optional process-level `TAIGA_URL`, `TAIGA_USERNAME`, and `TAIGA_PASSWORD` values.
- Produces: `parseEnv(text): Record<string, string>`, `resolveTaigaEnv(fileValues, processValues): Record<string, string>`, `npxInvocation(platform, environment): { command: string, args: string[] }`, and `main(): Promise<void>`.

- [x] **Step 1: Write parser and environment resolution tests**

```js
import assert from "node:assert/strict";
import test from "node:test";
import {
  npxInvocation,
  parseEnv,
  resolveTaigaEnv,
} from "../scripts/start-taiga-mcp.mjs";

test("parseEnv supports comments, CRLF, export, quotes, and equals in values", () => {
  const parsed = parseEnv(
    '# comment\r\nexport TAIGA_URL="https://api.taiga.io"\r\n' +
      "TAIGA_USERNAME='junior'\r\nTAIGA_PASSWORD=a=b=c\r\n",
  );
  assert.deepEqual(parsed, {
    TAIGA_URL: "https://api.taiga.io",
    TAIGA_USERNAME: "junior",
    TAIGA_PASSWORD: "a=b=c",
  });
});

test("process variables override local file values", () => {
  const env = resolveTaigaEnv(
    { TAIGA_URL: "file-url", TAIGA_USERNAME: "file-user", TAIGA_PASSWORD: "file-pass" },
    { TAIGA_USERNAME: "shell-user" },
  );
  assert.equal(env.TAIGA_URL, "file-url");
  assert.equal(env.TAIGA_USERNAME, "shell-user");
  assert.equal(env.TAIGA_PASSWORD, "file-pass");
});

test("resolveTaigaEnv reports missing names without values", () => {
  assert.throws(
    () => resolveTaigaEnv({ TAIGA_URL: "https://api.taiga.io" }, {}),
    /TAIGA_USERNAME, TAIGA_PASSWORD/,
  );
});

test("npxInvocation executes the Windows command shim through cmd.exe", () => {
  assert.deepEqual(
    npxInvocation("win32", { ComSpec: "C:\\Windows\\System32\\cmd.exe" }),
    {
      command: "C:\\Windows\\System32\\cmd.exe",
      args: ["/d", "/s", "/c", "npx.cmd", "--yes", "@illodev/taiga-mcp@1.0.0"],
    },
  );
  assert.deepEqual(npxInvocation("linux", {}), {
    command: "npx",
    args: ["--yes", "@illodev/taiga-mcp@1.0.0"],
  });
});
```

- [x] **Step 2: Run the test and verify the expected import failure**

Run: `node --test tests/start-taiga-mcp.test.mjs`

Expected: FAIL because `scripts/start-taiga-mcp.mjs` does not exist or does not export the named functions.

- [x] **Step 3: Implement the launcher**

```js
#!/usr/bin/env node
import { spawn } from "node:child_process";
import { existsSync, readFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";

const REQUIRED_KEYS = ["TAIGA_URL", "TAIGA_USERNAME", "TAIGA_PASSWORD"];
const PACKAGE = "@illodev/taiga-mcp@1.0.0";

export function parseEnv(text) {
  const values = {};
  for (const rawLine of text.split(/\r?\n/u)) {
    const line = rawLine.trim();
    if (!line || line.startsWith("#")) continue;
    const normalized = line.startsWith("export ") ? line.slice(7).trim() : line;
    const separator = normalized.indexOf("=");
    if (separator < 1) continue;
    const key = normalized.slice(0, separator).trim();
    let value = normalized.slice(separator + 1).trim();
    if (
      value.length >= 2 &&
      ((value.startsWith('"') && value.endsWith('"')) ||
        (value.startsWith("'") && value.endsWith("'")))
    ) {
      value = value.slice(1, -1);
    }
    values[key] = value;
  }
  return values;
}

export function resolveTaigaEnv(fileValues, processValues) {
  const merged = { ...fileValues, ...processValues };
  const missing = REQUIRED_KEYS.filter((key) => !merged[key]?.trim());
  if (missing.length > 0) {
    throw new Error(`Missing required Taiga variables: ${missing.join(", ")}`);
  }
  return merged;
}

export function npxInvocation(platform, environment) {
  const packageArgs = ["--yes", PACKAGE];
  if (platform === "win32") {
    return {
      command: environment.ComSpec ?? environment.COMSPEC ?? "cmd.exe",
      args: ["/d", "/s", "/c", "npx.cmd", ...packageArgs],
    };
  }
  return { command: "npx", args: packageArgs };
}

export async function main() {
  const scriptDirectory = dirname(fileURLToPath(import.meta.url));
  const repositoryRoot = dirname(scriptDirectory);
  const envPath = join(repositoryRoot, ".env.taiga.local");
  const fileValues = existsSync(envPath) ? parseEnv(readFileSync(envPath, "utf8")) : {};
  const taigaEnv = resolveTaigaEnv(fileValues, process.env);
  const invocation = npxInvocation(process.platform, process.env);
  const child = spawn(invocation.command, invocation.args, {
    cwd: repositoryRoot,
    env: { ...process.env, ...taigaEnv },
    shell: false,
    stdio: "inherit",
  });
  child.on("error", (error) => {
    console.error(`Unable to start Taiga MCP: ${error.message}`);
    process.exitCode = 1;
  });
  child.on("exit", (code, signal) => {
    process.exitCode = code ?? (signal ? 1 : 0);
  });
}

const isMain = process.argv[1] && pathToFileURL(process.argv[1]).href === import.meta.url;
if (isMain) {
  main().catch((error) => {
    console.error(error.message);
    process.exitCode = 1;
  });
}
```

- [x] **Step 4: Run launcher unit tests**

Run: `node --test tests/start-taiga-mcp.test.mjs`

Expected: four passing tests with no credential values in output.

- [x] **Step 5: Verify missing-secret behavior**

Run in an environment without Taiga variables: `env -u TAIGA_URL -u TAIGA_USERNAME -u TAIGA_PASSWORD node scripts/start-taiga-mcp.mjs`

Expected: exit code `1` and an error listing only `TAIGA_URL`, `TAIGA_USERNAME`, and `TAIGA_PASSWORD`.

### Task 2: Shared client configurations and secret template

**Files:**
- Create: `.mcp.json`
- Create: `.agents/mcp_config.json`
- Create: `.codex/config.toml`
- Create: `.env.taiga.example`
- Create or modify: `.gitignore`
- Create locally and leave untracked: `.env.taiga.local`

**Interfaces:**
- Consumes: `node scripts/start-taiga-mcp.mjs` from Task 1.
- Produces: one MCP server named `taiga-deyappa-test` in each supported client and a local credential file excluded from Git.

- [x] **Step 1: Create the Claude Code project configuration**

```json
{
  "mcpServers": {
    "taiga-deyappa-test": {
      "command": "node",
      "args": ["scripts/start-taiga-mcp.mjs"]
    }
  }
}
```

- [x] **Step 2: Create the Antigravity workspace configuration**

Use the same JSON content in `.agents/mcp_config.json` so Antigravity starts the common wrapper.

- [x] **Step 3: Create the Codex project configuration**

```toml
[mcp_servers.taiga-deyappa-test]
command = "node"
args = ["scripts/start-taiga-mcp.mjs"]
startup_timeout_sec = 30
tool_timeout_sec = 60
enabled = true
required = false
default_tools_approval_mode = "writes"
```

- [x] **Step 4: Create the public environment template and local file**

`.env.taiga.example`:

```dotenv
TAIGA_URL=https://api.taiga.io
TAIGA_USERNAME=your_taiga_username
TAIGA_PASSWORD=
```

`.env.taiga.local`:

```dotenv
TAIGA_URL=https://api.taiga.io
TAIGA_USERNAME=jcarreggio1
TAIGA_PASSWORD=
```

- [x] **Step 5: Exclude local credentials**

Add this exact line to the root `.gitignore`:

```gitignore
.env.taiga.local
```

- [x] **Step 6: Validate JSON, TOML, and ignore behavior**

Run:

```bash
node -e 'for (const file of [".mcp.json", ".agents/mcp_config.json"]) JSON.parse(require("node:fs").readFileSync(file, "utf8"))'
python3 -c 'import pathlib,tomllib; tomllib.loads(pathlib.Path(".codex/config.toml").read_text())'
git check-ignore .env.taiga.local
```

Expected: JSON and TOML commands exit `0`; `git check-ignore` prints `.env.taiga.local` when executed in a complete Git checkout. If the workspace is not a functional Git checkout, verify the exact entry with `rg -n '^\.env\.taiga\.local$' .gitignore`.

### Task 3: Team documentation

**Files:**
- Create: `docs/taiga-mcp.md`

**Interfaces:**
- Consumes: the launcher and configuration files from Tasks 1 and 2.
- Produces: a Spanish onboarding and operations guide usable without oral assistance.

- [x] **Step 1: Document the MCP mental model**

Explain the host/client/server relationship, STDIO lifecycle, automatic startup, one process per open client, and HTTPS calls from the MCP server to Taiga.

- [x] **Step 2: Document Windows and Linux setup**

Include these exact credential-copy commands:

```powershell
Copy-Item .env.taiga.example .env.taiga.local
```

```bash
cp .env.taiga.example .env.taiga.local
```

Tell each person to use their own Taiga username and password and never commit the local file.

- [x] **Step 3: Document each client**

State that Claude Code reads `.mcp.json`, Antigravity reads `.agents/mcp_config.json`, and Codex reads `.codex/config.toml`. Include `/mcp` as the primary status check and explain that Claude asks once before trusting a project MCP configuration.

- [x] **Step 4: Document safe prompts and troubleshooting**

Include read, create, and update examples that name `jcarreggio1-test-mcp`; warn that the account can reach every Taiga project it is allowed to access; exclude delete, membership, role, import/export, and webhook testing. Cover missing Node.js, npm download failures, missing variables, invalid credentials, insufficient Taiga permissions, and client restart/reload.

- [x] **Step 5: Verify required documentation content**

Run:

```bash
rg -n 'STDIO|Windows|Linux|Claude|Antigravity|Codex|\.env\.taiga\.local|jcarreggio1-test-mcp|no.*manual|contraseña|elimin' docs/taiga-mcp.md
```

Expected: each required topic appears in at least one matching line.

### Task 4: Local and authenticated validation

**Files:**
- Modify only if defects are found: `scripts/start-taiga-mcp.mjs`, client configurations, or `docs/taiga-mcp.md`.
- Keep untracked: `.env.taiga.local`.

**Interfaces:**
- Consumes: valid credentials entered locally by `jcarreggio1` and the three configured MCP clients.
- Produces: verified MCP discovery plus reviewable `[MCP TEST]` artifacts in Taiga project `1806761`.

- [x] **Step 1: Run all offline verification**

Run launcher tests, parse both JSON files and the TOML file, scan for accidentally committed credential values, and verify `.env.taiga.local` is ignored.

- [x] **Step 2: Have the user enter the password locally**

The user edits only `.env.taiga.local` and sets `TAIGA_PASSWORD`. Do not ask them to paste the password into chat or a command argument.

- [x] **Step 3: Verify MCP initialization and project resolution**

Start a fresh client session, open `/mcp`, confirm `taiga-deyappa-test` is connected, and resolve `jcarreggio1-test-mcp`. Confirm the returned project has ID `1806761` and name `Test-mcp`.

- [x] **Step 4: Execute controlled writes**

Enable epics if needed, then create `[MCP TEST] Integración compartida`, create `[MCP TEST] Historia de validación`, link the story to the epic, update the story description/status, create `[MCP TEST] Tarea de validación`, and update the task. Do not call delete tools.

- [x] **Step 5: Read back and compare**

Fetch the epic, story, and task and verify subjects, relationships, updated fields, project ID `1806761`, and returned Taiga references.

- [x] **Step 6: Cross-client smoke test**

From each installed client, list or fetch the artifacts created in Step 4. A missing client installation on the current machine is documented as an external validation step for the teammate who uses it.

- [x] **Step 7: Provide user-run Git commands**

After reviewing `git status --short` and `git diff`, provide these commands for the user to adjust to the actual repository state and run themselves:

```bash
git add .gitignore .env.taiga.example .mcp.json .agents/mcp_config.json .codex/config.toml scripts/start-taiga-mcp.mjs tests/start-taiga-mcp.test.mjs docs/taiga-mcp.md docs/superpowers/specs/2026-09-17-taiga-mcp-team-integration-design.md docs/superpowers/plans/2026-09-17-taiga-mcp-team-integration.md
git commit -m "feat: add shared Taiga MCP integration"
```
