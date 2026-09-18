# Taiga Wiki Sync Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a repeatable, read-only synchronizer that exports the Taiga project wiki and produces a traceable team conventions guide.

**Architecture:** A Node.js CLI authenticates against Taiga, resolves one allowlisted project, performs only GET requests after authentication, and writes an atomic local snapshot. Pure transformation functions create stable Markdown, metadata, hashes, diagnostics, and a convention source index.

**Tech Stack:** Node.js 20+, built-in `fetch`, `node:test`, Taiga REST API v1, Markdown, JSON, SHA-256.

**Spec:** `docs/superpowers/specs/2026-09-17-taiga-wiki-sync-design.md`

## Global Constraints

- Source slug: `tup_santoro_exequiel-plataforma-de-aprendizaje-gamificado-de-programacion`.
- API origin: `https://api.taiga.io`.
- Allow `POST` only for `/api/v1/auth`; use `GET` for all project data.
- Never call project mutation endpoints.
- Never persist passwords, access tokens, authorization headers, or full user records.
- Keep `.env.taiga.source.local` outside version control.
- Preserve wiki Markdown exactly; report normalization proposals separately.
- Validate project slug and optional expected project ID before writing output.
- Write to a temporary directory and replace the snapshot only after successful validation.
- Do not execute `git add`, `git commit`, `git push`, or branch-changing commands.

---

### Task 1: Credential template and HU seed

**Files:**
- Create: `.env.taiga.source.example`
- Modify: `.gitignore`
- Existing input: `docs/taiga-source/seeds/hu-template.raw.md`
- Test: `tests/taiga-wiki-sync.test.mjs`

**Interfaces:**
- Consumes: the HU template supplied in the conversation.
- Produces: source configuration keys and an immutable reference fixture.

- [ ] **Step 1: Add the source environment template**

```dotenv
TAIGA_SOURCE_URL=https://api.taiga.io
TAIGA_SOURCE_PROJECT_SLUG=tup_santoro_exequiel-plataforma-de-aprendizaje-gamificado-de-programacion
TAIGA_SOURCE_PROJECT_ID=
TAIGA_SOURCE_USERNAME=your_taiga_username
TAIGA_SOURCE_PASSWORD=
TAIGA_SOURCE_ATTACHMENT_HOSTS=tree.taiga.io
```

- [ ] **Step 2: Ignore the local credential file and temporary snapshot**

Add these exact entries:

```gitignore
.env.taiga.source.local
.cache/taiga-wiki/
```

- [x] **Step 3: Save the supplied HU template literally**

Create `docs/taiga-source/seeds/hu-template.raw.md` with the exact text provided by the user, including the malformed Figma and Storybook links. Do not normalize the seed.

- [ ] **Step 4: Add a fixture-integrity test**

Test that the seed contains these headings and constraints:

```js
assert.match(seed, /^Descripción \(Como \/ Quiero \/ Para\)$/m);
assert.match(seed, /^Criterios de Aceptación \(CA\)$/m);
assert.match(seed, /^BDD \(mínimo 3 escenarios\)$/m);
assert.match(seed, /Puntos \(Fibonacci\): \[1\/2\/3\/5\/8\/13\]/);
assert.equal((seed.match(/^Escenario \d/mg) ?? []).length, 3);
```

- [ ] **Step 5: Run the fixture test**

Run: `node --test tests/taiga-wiki-sync.test.mjs`

Expected: PASS with no credential values in output.

### Task 2: Read-only Taiga API client

**Files:**
- Create: `scripts/taiga-wiki-api.mjs`
- Modify: `tests/taiga-wiki-sync.test.mjs`

**Interfaces:**
- Consumes: `TAIGA_SOURCE_URL`, `TAIGA_SOURCE_USERNAME`, `TAIGA_SOURCE_PASSWORD`, and optional `TAIGA_SOURCE_ATTACHMENT_HOSTS`.
- Produces: `createTaigaReadClient(config, fetchImpl)` with `authenticate()`, `getProjectBySlug(slug)`, `listWiki(projectId)`, `getWikiPage(id)`, `listWikiLinks(projectId)`, `listWikiAttachments(projectId, pageId)`, and `downloadAttachment(url)`.

- [ ] **Step 1: Write tests for authentication and method restrictions**

Use an injected fake `fetch` and assert:

```js
assert.deepEqual(calls[0], {
  method: "POST",
  pathname: "/api/v1/auth",
});
assert.ok(calls.slice(1).every((call) => call.method === "GET"));
await assert.rejects(
  client.request("PATCH", "/projects/1", {}),
  /GET-only/,
);
```

Also assert that thrown errors contain status, method and path, but never the password or bearer token.

- [ ] **Step 2: Run tests and verify failure**

Run: `node --test tests/taiga-wiki-sync.test.mjs`

Expected: FAIL because `scripts/taiga-wiki-api.mjs` does not exist.

- [ ] **Step 3: Implement configuration parsing and authentication**

Implement these exports:

```js
import { parseEnv } from "./start-taiga-mcp.mjs";

const SOURCE_KEYS = [
  "TAIGA_SOURCE_URL",
  "TAIGA_SOURCE_PROJECT_SLUG",
  "TAIGA_SOURCE_USERNAME",
  "TAIGA_SOURCE_PASSWORD",
];

export function parseSourceEnv(text, processValues = {}) {
  const fileValues = parseEnv(text);
  const values = Object.fromEntries(
    [
      ...SOURCE_KEYS,
      "TAIGA_SOURCE_PROJECT_ID",
      "TAIGA_SOURCE_ATTACHMENT_HOSTS",
    ].map((key) => [
      key,
      processValues[key] ?? fileValues[key] ?? "",
    ]),
  );
  const missing = SOURCE_KEYS.filter((key) => !values[key].trim());
  if (missing.length) {
    throw new Error(`Missing source variables: ${missing.join(", ")}`);
  }
  return {
    apiOrigin: values.TAIGA_SOURCE_URL.replace(/\/+$/u, ""),
    projectSlug: values.TAIGA_SOURCE_PROJECT_SLUG,
    expectedProjectId: values.TAIGA_SOURCE_PROJECT_ID
      ? Number(values.TAIGA_SOURCE_PROJECT_ID)
      : null,
    username: values.TAIGA_SOURCE_USERNAME,
    password: values.TAIGA_SOURCE_PASSWORD,
    attachmentHosts: new Set(
      (values.TAIGA_SOURCE_ATTACHMENT_HOSTS || "tree.taiga.io")
        .split(",")
        .map((host) => host.trim())
        .filter(Boolean),
    ),
  };
}

export function createTaigaReadClient(config, fetchImpl = fetch) {
  let token = null;
  const allowedPaths = [
    /^\/projects\/by_slug$/u,
    /^\/wiki$/u,
    /^\/wiki\/\d+$/u,
    /^\/wiki\/attachments$/u,
    /^\/wiki-links$/u,
  ];

  async function authenticate() {
    const response = await fetchImpl(`${config.apiOrigin}/api/v1/auth`, {
      method: "POST",
      redirect: "error",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({
        type: "normal",
        username: config.username,
        password: config.password,
      }),
    });
    if (!response.ok) throw new Error(`Taiga auth failed (${response.status})`);
    token = (await response.json()).auth_token;
  }

  async function request(method, path, params = {}) {
    if (method !== "GET") throw new Error("Project data client is GET-only");
    if (!allowedPaths.some((pattern) => pattern.test(path))) {
      throw new Error(`Path is not allowlisted: ${path}`);
    }
    if (!token) throw new Error("Authenticate before reading project data");
    const url = new URL(`${config.apiOrigin}/api/v1${path}`);
    for (const [key, value] of Object.entries(params)) {
      url.searchParams.set(key, String(value));
    }
    const response = await fetchImpl(url, {
      method,
      redirect: "error",
      headers: {
        Authorization: `Bearer ${token}`,
        "x-disable-pagination": "True",
      },
    });
    if (!response.ok) {
      throw new Error(`Taiga read failed (${response.status}) GET ${path}`);
    }
    return response.json();
  }

  async function downloadAttachment(value) {
    const url = new URL(value);
    if (url.protocol !== "https:" || !config.attachmentHosts.has(url.host)) {
      throw new Error(`Attachment host is not allowlisted: ${url.host}`);
    }
    const response = await fetchImpl(url, {
      method: "GET",
      redirect: "error",
    });
    if (!response.ok) {
      throw new Error(`Attachment download failed (${response.status})`);
    }
    return new Uint8Array(await response.arrayBuffer());
  }

  return {
    authenticate,
    request,
    getProjectBySlug: (slug) => request("GET", "/projects/by_slug", { slug }),
    listWiki: (project) => request("GET", "/wiki", { project }),
    getWikiPage: (id) => request("GET", `/wiki/${id}`),
    listWikiLinks: (project) => request("GET", "/wiki-links", { project }),
    listWikiAttachments: (project, object_id) =>
      request("GET", "/wiki/attachments", { project, object_id }),
    downloadAttachment,
  };
}
```

`parseSourceEnv` must reject missing URL, slug, username or password by variable name only. `authenticate()` sends `{type: "normal", username, password}` to `/api/v1/auth` and retains `auth_token` only in the returned client closure.

- [ ] **Step 4: Implement allowlisted GET operations**

Use these exact routes:

```text
/api/v1/projects/by_slug?slug=<slug>
/api/v1/wiki?project=<project-id>
/api/v1/wiki/<wiki-id>
/api/v1/wiki-links?project=<project-id>
/api/v1/wiki/attachments?project=<project-id>&object_id=<wiki-id>
```

Reject non-HTTPS source URLs except `http://localhost` for tests. Use `redirect: "error"` for API calls.

Set `x-disable-pagination: True` on list reads so page and link counts are complete. Download attachments only from exact hosts in `attachmentHosts`, without forwarding the Taiga bearer token to the media host.

- [ ] **Step 5: Run API client tests**

Run: `node --test tests/taiga-wiki-sync.test.mjs`

Expected: PASS.

### Task 3: Deterministic snapshot builder

**Files:**
- Create: `scripts/taiga-wiki-snapshot.mjs`
- Modify: `tests/taiga-wiki-sync.test.mjs`

**Interfaces:**
- Consumes: project, wiki pages, wiki links, attachment metadata and the HU seed.
- Produces: `safeWikiFilename(slug)`, `renderWikiPage(page, project)`, `buildManifest(input)`, `analyzeSnapshot(input)`, and `writeAtomicSnapshot(root, snapshot)`.

- [ ] **Step 1: Write path-safety and rendering tests**

Test that valid slugs become `<slug>.md`, and reject `../secret`, `/absolute`, `a\\b`, empty slugs and duplicate case-insensitive filenames. Assert that rendered pages have this front matter order:

```yaml
---
taiga_id: 123
taiga_slug: historias-de-usuario
taiga_version: 7
taiga_modified_date: 2026-09-15T12:30:00Z
project_id: 456
project_slug: project-slug
source_url: https://tree.taiga.io/project/project-slug/wiki/historias-de-usuario
---
```

Assert that the body after front matter is byte-for-byte equal to the API `content` string after normalizing only the final newline.

- [ ] **Step 2: Write deterministic-manifest tests**

Provide pages in two different orders and assert equal JSON output. The manifest entry must contain:

```json
{
  "id": 123,
  "slug": "historias-de-usuario",
  "version": 7,
  "modified_date": "2026-09-15T12:30:00Z",
  "sha256": "<64 lowercase hex characters>"
}
```

- [ ] **Step 3: Write diagnostics tests**

Assert reporting of malformed Markdown links, duplicate slugs, wiki links without pages, pages absent from navigation and HU seed headings absent from the remote HU page. Diagnostics must not rewrite content.

- [ ] **Step 4: Run tests and verify failure**

Run: `node --test tests/taiga-wiki-sync.test.mjs`

Expected: FAIL because snapshot exports are missing.

- [ ] **Step 5: Implement pure snapshot functions**

Use `node:crypto` SHA-256, stable sorting by `slug`, and JSON formatted with two spaces plus a final newline. Store only project `id`, `name`, `slug`, module flags and modified date; omit member and owner objects.

- [ ] **Step 6: Implement atomic output**

Write the complete snapshot under `.cache/taiga-wiki/staging-<pid>`, validate it, then replace only `docs/taiga-source/pages`, `metadata`, `attachments` and `manifest.json`. Preserve `docs/taiga-source/seeds` across synchronization.

- [ ] **Step 7: Run snapshot tests**

Run: `node --test tests/taiga-wiki-sync.test.mjs`

Expected: PASS.

### Task 4: Synchronization CLI

**Files:**
- Create: `scripts/sync-taiga-wiki.mjs`
- Modify: `tests/taiga-wiki-sync.test.mjs`

**Interfaces:**
- Consumes: `.env.taiga.source.local` and Tasks 2–3.
- Produces: `main({root, fetchImpl, stdout})` and CLI flags `--dry-run` and `--download-attachments`.

- [ ] **Step 1: Write orchestration tests**

With a fake API, assert this order:

```text
authenticate
getProjectBySlug
validate project
listWiki
getWikiPage for every ID
listWikiLinks
listWikiAttachments for every page
downloadAttachment only when --download-attachments is present
build and validate snapshot
write snapshot unless --dry-run
```

Assert that project mismatch, HTTP failure or incomplete page retrieval leaves the previous snapshot untouched.

- [ ] **Step 2: Run tests and verify failure**

Run: `node --test tests/taiga-wiki-sync.test.mjs`

Expected: FAIL because the CLI does not exist.

- [ ] **Step 3: Implement the CLI**

Print only project identity, page/link/attachment counts, changed file paths and diagnostics. Never print environment values, tokens, response headers or full API errors containing bodies.

- [ ] **Step 4: Implement dry-run behavior**

`node scripts/sync-taiga-wiki.mjs --dry-run` performs authenticated reads and validation but does not alter `docs/taiga-source`. Exit `0` on a valid snapshot, `1` on access, identity, completeness or safety errors.

- [ ] **Step 5: Run the complete unit suite**

Run: `node --test tests/taiga-wiki-sync.test.mjs`

Expected: PASS.

### Task 5: Authenticated read-only extraction

**Files:**
- Create locally: `.env.taiga.source.local`
- Produce: `docs/taiga-source/manifest.json`
- Produce: `docs/taiga-source/pages/*.md`
- Produce: `docs/taiga-source/metadata/*.json`
- Produce only when explicitly requested: `docs/taiga-source/attachments/**`

**Interfaces:**
- Consumes: credentials for an account with `view_project` and `view_wiki_pages` on the source project.
- Produces: the first verified wiki snapshot.

- [ ] **Step 1: Configure credentials locally**

Copy `.env.taiga.source.example` to `.env.taiga.source.local`, set the username and password locally, and keep `TAIGA_SOURCE_PROJECT_ID` empty until discovery. Never paste the password into chat.

- [ ] **Step 2: Run discovery in dry-run mode**

Run: `node scripts/sync-taiga-wiki.mjs --dry-run`

Expected: project name, slug, discovered ID and counts; no changed files. A 404 stops the task and indicates that access or the slug must be corrected.

- [ ] **Step 3: Pin the discovered project ID**

Set `TAIGA_SOURCE_PROJECT_ID` locally to the ID returned in Step 2, rerun dry-run, and require an exact ID and slug match.

- [ ] **Step 4: Generate the snapshot**

Run: `node scripts/sync-taiga-wiki.mjs`

Expected: pages, metadata and manifest written; Taiga receives only authentication and GET requests. Review `metadata/attachments.json` before any binary download.

- [ ] **Step 5: Optionally download allowlisted attachments**

If the attachment inventory contains documents needed for the conventions, run `node scripts/sync-taiga-wiki.mjs --download-attachments`. Expected: files from `TAIGA_SOURCE_ATTACHMENT_HOSTS` only, with filename, size and SHA-256 recorded in the manifest. A rejected host or download error must not alter the prior snapshot.

- [ ] **Step 6: Verify idempotence**

Run the same command again.

Expected: zero changed output files and identical hashes.

### Task 6: Conventions guide and team workflow

**Files:**
- Create: `docs/convenciones-taiga.md`
- Modify: `docs/taiga-mcp.md`

**Interfaces:**
- Consumes: synchronized Markdown pages, diagnostics and HU seed comparison.
- Produces: a human-reviewed operational guide for Claude, Antigravity, Codex and the team.

- [ ] **Step 1: Build a source index**

For every convention, record the local page path and heading. Use these categories: epics, HU, tasks, sprints, statuses, estimates, priorities, dependencies, prototypes, API evidence, Definition of Ready, Definition of Done and documentation.

- [ ] **Step 2: Draft confirmed rules only**

Copy or summarize rules supported by the synchronized source. Put contradictions, malformed links and missing definitions under `## Pendientes de validación`; do not invent resolutions.

- [ ] **Step 3: Normalize the HU template for team use**

Create a clean template in the guide using the supplied section order, three required BDD scenarios and Fibonacci values `1, 2, 3, 5, 8, 13`. Preserve the raw seed link and flag Figma/Storybook URL corrections for human review.

- [ ] **Step 4: Connect conventions to the production safety protocol**

Update `docs/taiga-mcp.md` so every AI must read `docs/convenciones-taiga.md` before proposing a production write, cite the applicable convention, show the exact payload and request explicit authorization.

- [ ] **Step 5: Validate traceability and secrets**

Run a script that verifies every source reference exists, every manifest hash matches, and neither `TAIGA_SOURCE_PASSWORD` nor bearer-token patterns appear in generated files.

- [ ] **Step 6: Perform human review**

Review `docs/convenciones-taiga.md` against the Taiga wiki, resolve only explicitly confirmed diagnostics, and rerun validation.

### Task 7: Final verification and handoff

**Files:**
- Modify only if verification finds defects: sync scripts, tests or documentation.

**Interfaces:**
- Consumes: all prior tasks.
- Produces: a repeatable synchronization command and reviewed team documentation.

- [ ] **Step 1: Run all tests**

Run: `node --test tests/start-taiga-mcp.test.mjs tests/taiga-wiki-sync.test.mjs`

Expected: PASS with zero failures.

- [ ] **Step 2: Run final dry-run**

Run: `node scripts/sync-taiga-wiki.mjs --dry-run`

Expected: exact project match, complete counts and no safety diagnostics that block publication.

- [ ] **Step 3: Verify credential exclusions**

Confirm `.env.taiga.local` and `.env.taiga.source.local` are ignored, have restrictive permissions on Linux, and are absent from the staging list.

- [ ] **Step 4: Review generated changes**

Review the manifest, page count, diagnostic report, conventions guide and all changed paths. Do not stage or commit from the agent.

- [ ] **Step 5: Provide user-run Git commands**

Provide exact `git add` paths that exclude local environment files, followed by:

```bash
git commit -m "feat: add read-only Taiga wiki synchronization"
```
