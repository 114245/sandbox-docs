import assert from "node:assert/strict";
import test from "node:test";
import * as launcher from "../scripts/start-taiga-mcp.mjs";

test("parseEnv handles the file formats used on Windows and Linux", () => {
  const parsed = launcher.parseEnv(
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
  const env = launcher.resolveTaigaEnv(
    {
      TAIGA_URL: "file-url",
      TAIGA_USERNAME: "file-user",
      TAIGA_PASSWORD: "file-pass",
    },
    { TAIGA_USERNAME: "shell-user" },
  );

  assert.equal(env.TAIGA_URL, "file-url");
  assert.equal(env.TAIGA_USERNAME, "shell-user");
  assert.equal(env.TAIGA_PASSWORD, "file-pass");
});

test("missing credentials are reported by name without printing values", () => {
  assert.throws(
    () => launcher.resolveTaigaEnv({ TAIGA_URL: "https://api.taiga.io" }, {}),
    {
      message:
        "Missing required Taiga variables: TAIGA_USERNAME, TAIGA_PASSWORD",
    },
  );
});

test("npxInvocation executes the Windows command shim through cmd.exe", () => {
  assert.deepEqual(
    launcher.npxInvocation("win32", {
      ComSpec: "C:\\Windows\\System32\\cmd.exe",
    }),
    {
      command: "C:\\Windows\\System32\\cmd.exe",
      args: [
        "/d",
        "/s",
        "/c",
        "npx.cmd",
        "--yes",
        "@illodev/taiga-mcp@1.0.0",
      ],
    },
  );

  assert.deepEqual(launcher.npxInvocation("linux", {}), {
    command: "npx",
    args: ["--yes", "@illodev/taiga-mcp@1.0.0"],
  });
});
