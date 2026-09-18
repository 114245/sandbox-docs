import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { mkdtemp, mkdir, readFile, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import test from "node:test";
import {
  createTaigaReadClient,
  parseSourceEnv,
} from "../scripts/taiga-wiki-api.mjs";
import {
  analyzeSnapshot,
  buildManifest,
  renderWikiPage,
  safeWikiFilename,
  writeAtomicSnapshot,
} from "../scripts/taiga-wiki-snapshot.mjs";
import { main as syncWiki } from "../scripts/sync-taiga-wiki.mjs";
import { validateTaigaDocs } from "../scripts/validate-taiga-docs.mjs";

const repositoryRoot = new URL("../", import.meta.url);

test("the supplied HU template remains a complete control fixture", () => {
  const seed = readFileSync(
    new URL("docs/taiga-source/seeds/hu-template.raw.md", repositoryRoot),
    "utf8",
  );

  assert.match(seed, /^Descripción \(Como \/ Quiero \/ Para\)$/m);
  assert.match(seed, /^Criterios de Aceptación \(CA\)$/m);
  assert.match(seed, /^BDD \(mínimo 3 escenarios\)$/m);
  assert.match(seed, /Puntos \(Fibonacci\): \[1\/2\/3\/5\/8\/13\]/);
  assert.equal((seed.match(/^Escenario \d/mg) ?? []).length, 3);
});

test("source configuration requires named values without exposing secrets", () => {
  assert.throws(
    () =>
      parseSourceEnv(
        "TAIGA_SOURCE_URL=https://api.taiga.io\n" +
          "TAIGA_SOURCE_PROJECT_SLUG=project-slug\n",
      ),
    /TAIGA_SOURCE_USERNAME, TAIGA_SOURCE_PASSWORD/,
  );

  const config = parseSourceEnv(
    "TAIGA_SOURCE_URL=https://api.taiga.io\n" +
      "TAIGA_SOURCE_PROJECT_SLUG=project-slug\n" +
      "TAIGA_SOURCE_PROJECT_ID=123\n" +
      "TAIGA_SOURCE_USERNAME=file-user\n" +
      "TAIGA_SOURCE_PASSWORD=file-secret\n" +
      "TAIGA_SOURCE_ATTACHMENT_HOSTS=tree.taiga.io,cdn.example.test\n",
    { TAIGA_SOURCE_USERNAME: "process-user" },
  );

  assert.equal(config.apiOrigin, "https://api.taiga.io");
  assert.equal(config.projectSlug, "project-slug");
  assert.equal(config.expectedProjectId, 123);
  assert.equal(config.username, "process-user");
  assert.equal(config.password, "file-secret");
  assert.deepEqual([...config.attachmentHosts], [
    "tree.taiga.io",
    "cdn.example.test",
  ]);
});

test("API client authenticates once and restricts project traffic to GET", async () => {
  const calls = [];
  const fakeFetch = async (value, options) => {
    const url = new URL(value);
    calls.push({ url, options });
    if (url.pathname === "/api/v1/auth") {
      return Response.json({ auth_token: "private-token" });
    }
    return Response.json([{ id: 1, slug: "home" }]);
  };
  const client = createTaigaReadClient(
    {
      apiOrigin: "https://api.taiga.io",
      username: "reader",
      password: "private-password",
      attachmentHosts: new Set(["tree.taiga.io"]),
    },
    fakeFetch,
  );

  await client.authenticate();
  await client.listWiki(123);

  assert.equal(calls[0].url.pathname, "/api/v1/auth");
  assert.equal(calls[0].options.method, "POST");
  assert.equal(calls[1].url.pathname, "/api/v1/wiki");
  assert.equal(calls[1].url.searchParams.get("project"), "123");
  assert.equal(calls[1].options.method, "GET");
  assert.equal(calls[1].options.headers["x-disable-pagination"], "True");
  assert.equal(calls[1].options.headers.Authorization, "Bearer private-token");
  await assert.rejects(
    client.request("PATCH", "/projects/1", {}),
    /GET-only/,
  );
  await assert.rejects(client.request("GET", "/users", {}), /allowlisted/);
});

test("attachment downloads require an allowlisted HTTPS host and omit auth", async () => {
  const calls = [];
  const fakeFetch = async (value, options) => {
    calls.push({ url: new URL(value), options });
    return new Response(new Uint8Array([1, 2, 3]));
  };
  const client = createTaigaReadClient(
    {
      apiOrigin: "https://api.taiga.io",
      username: "reader",
      password: "private-password",
      attachmentHosts: new Set(["tree.taiga.io"]),
    },
    fakeFetch,
  );

  const body = await client.downloadAttachment(
    "https://tree.taiga.io/media/document.pdf",
  );
  assert.deepEqual(body, new Uint8Array([1, 2, 3]));
  assert.equal(calls[0].options.headers, undefined);
  await assert.rejects(
    client.downloadAttachment("https://attacker.example/document.pdf"),
    /not allowlisted/,
  );
  await assert.rejects(
    client.downloadAttachment("http://tree.taiga.io/document.pdf"),
    /not allowlisted/,
  );
});

test("wiki filenames reject traversal and unsafe slugs", () => {
  assert.equal(safeWikiFilename("historias-de-usuario"), "historias-de-usuario.md");
  for (const slug of ["", "../secret", "/absolute", "a\\b", ".hidden"]) {
    assert.throws(() => safeWikiFilename(slug), /Unsafe wiki slug/);
  }
});

test("wiki rendering preserves content and adds traceable front matter", () => {
  const rendered = renderWikiPage(
    {
      id: 123,
      slug: "historias-de-usuario",
      version: 7,
      modified_date: "2026-09-15T12:30:00Z",
      content: "# Template\n\nContenido original\n",
    },
    { id: 456, slug: "project-slug" },
  );

  assert.equal(
    rendered,
    "---\n" +
      "taiga_id: 123\n" +
      "taiga_slug: historias-de-usuario\n" +
      "taiga_version: 7\n" +
      "taiga_modified_date: 2026-09-15T12:30:00Z\n" +
      "project_id: 456\n" +
      "project_slug: project-slug\n" +
      "source_url: https://tree.taiga.io/project/project-slug/wiki/historias-de-usuario\n" +
      "---\n\n" +
      "# Template\n\nContenido original\n",
  );
});

test("wiki rendering redacts protected media tokens before persistence", () => {
  const rendered = renderWikiPage(
    {
      id: 123,
      slug: "media",
      version: 1,
      modified_date: "2026-09-17T00:00:00Z",
      content:
        "![](https://media-protected.taiga.io/image.png?token=private-signed-value#refresh)",
    },
    { id: 456, slug: "project-slug" },
  );

  assert.doesNotMatch(rendered, /private-signed-value/u);
  assert.match(rendered, /token=\[REDACTED\]/u);
});

test("manifest output is deterministic and rejects filename collisions", () => {
  const project = {
    id: 456,
    name: "Project",
    slug: "project-slug",
    modified_date: "2026-09-15T12:00:00Z",
    is_wiki_activated: true,
  };
  const pages = [
    {
      id: 2,
      slug: "second",
      version: 1,
      modified_date: "2026-09-15T12:30:00Z",
      content: "Second",
    },
    {
      id: 1,
      slug: "first",
      version: 2,
      modified_date: "2026-09-15T12:20:00Z",
      content: "First",
    },
  ];
  const first = buildManifest({ project, pages, links: [], attachments: [] });
  const second = buildManifest({
    project,
    pages: [...pages].reverse(),
    links: [],
    attachments: [],
  });

  assert.equal(JSON.stringify(first), JSON.stringify(second));
  assert.deepEqual(
    first.pages.map(({ slug }) => slug),
    ["first", "second"],
  );
  assert.match(first.pages[0].sha256, /^[a-f0-9]{64}$/u);
  assert.throws(
    () =>
      buildManifest({
        project,
        pages: [
          { ...pages[0], slug: "Duplicate" },
          { ...pages[1], slug: "duplicate" },
        ],
        links: [],
        attachments: [],
      }),
    /collision/,
  );
});

test("snapshot diagnostics report navigation, links, and seed differences", () => {
  const diagnostics = analyzeSnapshot({
    pages: [
      {
        id: 1,
        slug: "home",
        content: "Broken [link](page)(extra)",
      },
      {
        id: 2,
        slug: "historias-de-usuario",
        content: "Descripción (Como / Quiero / Para)",
      },
      { id: 3, slug: "orphan", content: "No navigation link" },
      {
        id: 4,
        slug: "media",
        content: "https://media.example/image?token=signed-value",
      },
    ],
    links: [
      { title: "Home", href: "home", order: 1 },
      { title: "Missing", href: "missing", order: 2 },
      { title: "HU", href: "historias-de-usuario", order: 3 },
    ],
    huSeed:
      "Descripción (Como / Quiero / Para)\nCriterios de Aceptación (CA)\nBDD (mínimo 3 escenarios)\n",
    huPageSlug: "historias-de-usuario",
  });

  assert.ok(diagnostics.some(({ code }) => code === "MALFORMED_MARKDOWN_LINK"));
  assert.ok(diagnostics.some(({ code }) => code === "LINK_WITHOUT_PAGE"));
  assert.ok(diagnostics.some(({ code }) => code === "PAGE_WITHOUT_LINK"));
  assert.ok(diagnostics.some(({ code }) => code === "HU_SEED_HEADING_MISSING"));
  assert.ok(
    diagnostics.some(({ code }) => code === "PROTECTED_MEDIA_TOKEN_REDACTED"),
  );
});

test("atomic snapshot writing preserves seeds and replaces validated output", async () => {
  const root = await mkdtemp(join(tmpdir(), "taiga-snapshot-"));
  const seedDirectory = join(root, "docs", "taiga-source", "seeds");
  await mkdir(seedDirectory, { recursive: true });
  await writeFile(join(seedDirectory, "hu-template.raw.md"), "seed\n");
  await writeFile(
    join(root, "docs", "taiga-source", "manifest.json"),
    "old manifest\n",
  );
  const project = { id: 10, slug: "project", name: "Project" };
  const pages = [
    {
      id: 20,
      slug: "home",
      version: 1,
      modified_date: "2026-09-17T00:00:00Z",
      content: "# Home",
    },
  ];
  const manifest = buildManifest({ project, pages });

  await writeAtomicSnapshot(root, {
    project,
    pages,
    manifest,
    diagnostics: [],
    attachmentFiles: [],
  });

  assert.equal(
    await readFile(join(seedDirectory, "hu-template.raw.md"), "utf8"),
    "seed\n",
  );
  assert.match(
    await readFile(join(root, "docs", "taiga-source", "pages", "home.md"), "utf8"),
    /taiga_slug: home/u,
  );
  assert.deepEqual(
    JSON.parse(
      await readFile(join(root, "docs", "taiga-source", "manifest.json"), "utf8"),
    ),
    manifest,
  );
});

function createFakeTaigaFetch(calls) {
  return async (value, options) => {
    const url = new URL(value);
    calls.push({ method: options.method, pathname: url.pathname, url });
    if (url.pathname === "/api/v1/auth") {
      return Response.json({ auth_token: "secret-token" });
    }
    if (url.pathname === "/api/v1/projects/by_slug") {
      return Response.json({
        id: 42,
        name: "Source Project",
        slug: "source-project",
        modified_date: "2026-09-17T10:00:00Z",
        is_wiki_activated: true,
      });
    }
    if (url.pathname === "/api/v1/wiki" && !url.searchParams.has("object_id")) {
      return Response.json([
        { id: 100, slug: "home" },
        { id: 101, slug: "historias-de-usuario" },
      ]);
    }
    if (url.pathname === "/api/v1/wiki/100") {
      return Response.json({
        id: 100,
        project: 42,
        slug: "home",
        version: 1,
        modified_date: "2026-09-17T10:01:00Z",
        content: "# Home\n",
      });
    }
    if (url.pathname === "/api/v1/wiki/101") {
      return Response.json({
        id: 101,
        project: 42,
        slug: "historias-de-usuario",
        version: 2,
        modified_date: "2026-09-17T10:02:00Z",
        content:
          "Descripción (Como / Quiero / Para)\nCriterios de Aceptación (CA)\nBDD (mínimo 3 escenarios)\n",
      });
    }
    if (url.pathname === "/api/v1/wiki-links") {
      return Response.json([
        { id: 1, title: "Home", href: "home", order: 1 },
        {
          id: 2,
          title: "HU",
          href: "historias-de-usuario",
          order: 2,
        },
      ]);
    }
    if (url.pathname === "/api/v1/wiki/attachments") {
      return Response.json([]);
    }
    return new Response(null, { status: 404 });
  };
}

async function createSyncRoot(expectedProjectId = "42") {
  const root = await mkdtemp(join(tmpdir(), "taiga-sync-"));
  await mkdir(join(root, "docs", "taiga-source", "seeds"), {
    recursive: true,
  });
  await writeFile(
    join(root, "docs", "taiga-source", "seeds", "hu-template.raw.md"),
    "Descripción (Como / Quiero / Para)\nCriterios de Aceptación (CA)\nBDD (mínimo 3 escenarios)\n",
  );
  await writeFile(
    join(root, ".env.taiga.source.local"),
    "TAIGA_SOURCE_URL=https://api.taiga.io\n" +
      "TAIGA_SOURCE_PROJECT_SLUG=source-project\n" +
      `TAIGA_SOURCE_PROJECT_ID=${expectedProjectId}\n` +
      "TAIGA_SOURCE_USERNAME=reader\n" +
      "TAIGA_SOURCE_PASSWORD=private-password\n",
  );
  return root;
}

test("sync CLI performs the complete read-only sequence and honors dry-run", async () => {
  const root = await createSyncRoot();
  const calls = [];
  let output = "";
  const result = await syncWiki({
    root,
    args: ["--dry-run"],
    processValues: {},
    fetchImpl: createFakeTaigaFetch(calls),
    stdout: { write: (value) => (output += value) },
  });

  assert.equal(result.written, false);
  assert.deepEqual(
    calls.map(({ method, pathname }) => `${method} ${pathname}`),
    [
      "POST /api/v1/auth",
      "GET /api/v1/projects/by_slug",
      "GET /api/v1/wiki",
      "GET /api/v1/wiki/100",
      "GET /api/v1/wiki/101",
      "GET /api/v1/wiki-links",
      "GET /api/v1/wiki/attachments",
      "GET /api/v1/wiki/attachments",
    ],
  );
  assert.match(output, /2 páginas, 2 enlaces, 0 adjuntos/u);
  await assert.rejects(
    readFile(join(root, "docs", "taiga-source", "manifest.json"), "utf8"),
    /ENOENT/u,
  );
});

test("sync project mismatch leaves the previous snapshot untouched", async () => {
  const root = await createSyncRoot("999");
  const manifestPath = join(root, "docs", "taiga-source", "manifest.json");
  await writeFile(manifestPath, "previous snapshot\n");

  await assert.rejects(
    syncWiki({
      root,
      args: [],
      processValues: {},
      fetchImpl: createFakeTaigaFetch([]),
      stdout: { write() {} },
    }),
    /project ID mismatch/u,
  );
  assert.equal(await readFile(manifestPath, "utf8"), "previous snapshot\n");
});

test("generated Taiga documentation remains traceable and secret-free", async () => {
  const result = await validateTaigaDocs(repositoryRoot.pathname);

  assert.equal(result.pages, 7);
  assert.ok(result.references >= 7);
  assert.equal(result.secretFindings, 0);
});
