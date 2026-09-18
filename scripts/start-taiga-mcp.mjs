#!/usr/bin/env node

import { spawn } from "node:child_process";
import { existsSync, readFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";

const REQUIRED_KEYS = ["TAIGA_URL", "TAIGA_USERNAME", "TAIGA_PASSWORD"];
const MCP_PACKAGE = "@illodev/taiga-mcp@1.0.0";

export function parseEnv(text) {
  const values = {};

  for (const rawLine of text.split(/\r?\n/u)) {
    const line = rawLine.trim();
    if (!line || line.startsWith("#")) continue;

    const normalized = line.startsWith("export ")
      ? line.slice(7).trim()
      : line;
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
  const packageArgs = ["--yes", MCP_PACKAGE];

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
  const fileValues = existsSync(envPath)
    ? parseEnv(readFileSync(envPath, "utf8"))
    : {};
  const taigaEnv = resolveTaigaEnv(fileValues, process.env);
  const invocation = npxInvocation(process.platform, process.env);

  await new Promise((resolve, reject) => {
    const child = spawn(invocation.command, invocation.args, {
      cwd: repositoryRoot,
      env: { ...process.env, ...taigaEnv },
      shell: false,
      stdio: "inherit",
    });

    child.once("error", reject);
    child.once("exit", (code, signal) => {
      if (code === 0) {
        resolve();
        return;
      }

      reject(
        new Error(
          signal
            ? `Taiga MCP stopped by signal ${signal}`
            : `Taiga MCP exited with code ${code ?? "unknown"}`,
        ),
      );
    });
  });
}

const isMain =
  process.argv[1] && pathToFileURL(process.argv[1]).href === import.meta.url;

if (isMain) {
  main().catch((error) => {
    console.error(error.message);
    process.exitCode = 1;
  });
}
