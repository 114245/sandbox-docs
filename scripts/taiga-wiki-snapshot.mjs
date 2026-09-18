import { createHash } from "node:crypto";
import {
  cp,
  mkdir,
  readFile,
  rename,
  rm,
  stat,
  writeFile,
} from "node:fs/promises";
import { basename, dirname, join } from "node:path";

const REQUIRED_HU_HEADINGS = [
  "Descripción (Como / Quiero / Para)",
  "Notas / Observaciones",
  "Criterios de Aceptación (CA)",
  "BDD (mínimo 3 escenarios)",
  "Prototipo",
  "Estimación / Prioridad",
  "Dependencias / Impactos",
];

const PROJECT_FIELDS = [
  "id",
  "name",
  "slug",
  "modified_date",
  "is_backlog_activated",
  "is_issues_activated",
  "is_kanban_activated",
  "is_wiki_activated",
];

const LINK_FIELDS = ["id", "title", "href", "order"];
const ATTACHMENT_FIELDS = [
  "id",
  "object_id",
  "name",
  "description",
  "url",
  "size",
  "created_date",
  "modified_date",
];

function compareText(left, right) {
  return String(left).localeCompare(String(right), "en", {
    sensitivity: "variant",
  });
}

function pickDefined(value, fields) {
  return Object.fromEntries(
    fields
      .filter((field) => value[field] !== undefined)
      .map((field) => [field, value[field]]),
  );
}

function sha256(value) {
  return createHash("sha256").update(value).digest("hex");
}

function json(value) {
  return `${JSON.stringify(value, null, 2)}\n`;
}

function normalizeFinalNewline(value) {
  const content = String(value ?? "");
  return content.endsWith("\n") ? content : `${content}\n`;
}

function redactProtectedMediaTokens(value) {
  return String(value ?? "").replace(
    /([?&]token=)[^&#)\s]+/giu,
    "$1[REDACTED]",
  );
}

export function safeWikiFilename(slug) {
  if (
    typeof slug !== "string" ||
    slug.length === 0 ||
    slug.startsWith(".") ||
    slug.includes("..") ||
    slug.includes("/") ||
    slug.includes("\\") ||
    slug.includes("\0") ||
    !/^[A-Za-z0-9][A-Za-z0-9._-]*$/u.test(slug)
  ) {
    throw new Error(`Unsafe wiki slug: ${String(slug)}`);
  }
  return `${slug}.md`;
}

export function renderWikiPage(page, project) {
  safeWikiFilename(page.slug);
  const header = [
    "---",
    `taiga_id: ${page.id}`,
    `taiga_slug: ${page.slug}`,
    `taiga_version: ${page.version}`,
    `taiga_modified_date: ${page.modified_date}`,
    `project_id: ${project.id}`,
    `project_slug: ${project.slug}`,
    `source_url: https://tree.taiga.io/project/${project.slug}/wiki/${page.slug}`,
    "---",
    "",
  ].join("\n");

  return `${header}\n${normalizeFinalNewline(redactProtectedMediaTokens(page.content))}`;
}

function assertNoFilenameCollisions(pages) {
  const filenames = new Map();
  for (const page of pages) {
    const filename = safeWikiFilename(page.slug);
    const key = filename.toLocaleLowerCase("en-US");
    if (filenames.has(key)) {
      throw new Error(
        `Wiki filename collision: ${filenames.get(key)} and ${filename}`,
      );
    }
    filenames.set(key, filename);
  }
}

export function buildManifest({
  project,
  pages,
  links = [],
  attachments = [],
  attachmentFiles = [],
}) {
  assertNoFilenameCollisions(pages);

  const manifestPages = [...pages]
    .sort((left, right) => compareText(left.slug, right.slug))
    .map((page) => ({
      id: page.id,
      slug: page.slug,
      version: page.version,
      modified_date: page.modified_date,
      sha256: sha256(renderWikiPage(page, project)),
    }));

  const manifestFiles = [...attachmentFiles]
    .map((file) => ({
      wiki_slug: file.wikiSlug,
      filename: file.filename,
      size: file.bytes.byteLength,
      sha256: sha256(file.bytes),
    }))
    .sort((left, right) =>
      compareText(
        `${left.wiki_slug}/${left.filename}`,
        `${right.wiki_slug}/${right.filename}`,
      ),
    );

  return {
    project: pickDefined(project, PROJECT_FIELDS),
    pages: manifestPages,
    wiki_links: [...links]
      .map((link) => pickDefined(link, LINK_FIELDS))
      .sort(
        (left, right) =>
          (Number(left.order) || 0) - (Number(right.order) || 0) ||
          compareText(left.href, right.href),
      ),
    attachments: [...attachments]
      .map((attachment) => pickDefined(attachment, ATTACHMENT_FIELDS))
      .sort(
        (left, right) =>
          compareText(left.object_id, right.object_id) ||
          compareText(left.name, right.name) ||
          compareText(left.id, right.id),
      ),
    attachment_files: manifestFiles,
  };
}

function normalizeWikiHref(value) {
  const raw = String(value ?? "").trim();
  if (!raw || raw.startsWith("#")) return null;
  if (/^https?:\/\//iu.test(raw)) {
    try {
      const url = new URL(raw);
      const match = url.pathname.match(/\/wiki\/([^/?#]+)/u);
      return match ? decodeURIComponent(match[1]) : null;
    } catch {
      return null;
    }
  }
  return raw.replace(/^\/+|\/+$/gu, "").replace(/^wiki\//u, "");
}

export function analyzeSnapshot({ pages, links, huSeed, huPageSlug }) {
  const diagnostics = [];
  const slugCounts = new Map();

  for (const page of pages) {
    const key = String(page.slug).toLocaleLowerCase("en-US");
    slugCounts.set(key, (slugCounts.get(key) ?? 0) + 1);
    if (/\]\([^\n)]*\)\([^\n)]*\)/u.test(String(page.content ?? ""))) {
      diagnostics.push({
        code: "MALFORMED_MARKDOWN_LINK",
        page_slug: page.slug,
        message: "La página contiene un enlace Markdown con grupos repetidos.",
      });
    }
    if (/[?&]token=[^&#)\s]+/iu.test(String(page.content ?? ""))) {
      diagnostics.push({
        code: "PROTECTED_MEDIA_TOKEN_REDACTED",
        page_slug: page.slug,
        message:
          "Se redactó un token de una URL de medios protegidos antes de guardar la página.",
      });
    }
  }

  for (const [slug, count] of slugCounts) {
    if (count > 1) {
      diagnostics.push({
        code: "DUPLICATE_PAGE_SLUG",
        page_slug: slug,
        message: `El slug aparece ${count} veces.`,
      });
    }
  }

  const pageSlugs = new Set(pages.map((page) => page.slug));
  const linkedSlugs = new Set();
  for (const link of links) {
    const slug = normalizeWikiHref(link.href);
    if (!slug) continue;
    linkedSlugs.add(slug);
    if (!pageSlugs.has(slug)) {
      diagnostics.push({
        code: "LINK_WITHOUT_PAGE",
        href: link.href,
        message: "La navegación referencia una página que no fue extraída.",
      });
    }
  }

  for (const page of pages) {
    if (!linkedSlugs.has(page.slug)) {
      diagnostics.push({
        code: "PAGE_WITHOUT_LINK",
        page_slug: page.slug,
        message: "La página no aparece en la navegación de la wiki.",
      });
    }
  }

  const remoteHuPage = pages.find((page) => page.slug === huPageSlug);
  if (remoteHuPage) {
    const seedHeadings = REQUIRED_HU_HEADINGS.filter((heading) =>
      String(huSeed ?? "").split(/\r?\n/u).includes(heading),
    );
    for (const heading of seedHeadings) {
      if (!String(remoteHuPage.content ?? "").includes(heading)) {
        diagnostics.push({
          code: "HU_SEED_HEADING_MISSING",
          page_slug: huPageSlug,
          heading,
          message: "La página remota no contiene un encabezado de la semilla HU.",
        });
      }
    }
  } else if (huPageSlug) {
    diagnostics.push({
      code: "HU_PAGE_NOT_FOUND",
      page_slug: huPageSlug,
      message: "No se encontró la página remota usada para comparar la semilla HU.",
    });
  }

  return diagnostics.sort((left, right) =>
    compareText(
      `${left.code}:${left.page_slug ?? ""}:${left.href ?? ""}:${left.heading ?? ""}`,
      `${right.code}:${right.page_slug ?? ""}:${right.href ?? ""}:${right.heading ?? ""}`,
    ),
  );
}

function safeAttachmentFilename(value) {
  if (
    typeof value !== "string" ||
    value.length === 0 ||
    value === "." ||
    value === ".." ||
    basename(value) !== value ||
    value.includes("\\") ||
    value.includes("\0")
  ) {
    throw new Error(`Unsafe attachment filename: ${String(value)}`);
  }
  return value;
}

async function pathExists(value) {
  try {
    await stat(value);
    return true;
  } catch (error) {
    if (error.code === "ENOENT") return false;
    throw error;
  }
}

export async function writeAtomicSnapshot(root, snapshot) {
  const cacheRoot = join(root, ".cache", "taiga-wiki");
  const target = join(root, "docs", "taiga-source");
  const staging = join(cacheRoot, `staging-${process.pid}`);
  const backup = join(cacheRoot, `backup-${process.pid}`);

  await mkdir(cacheRoot, { recursive: true });
  await rm(staging, { recursive: true, force: true });
  await rm(backup, { recursive: true, force: true });
  await mkdir(join(staging, "pages"), { recursive: true });
  await mkdir(join(staging, "metadata"), { recursive: true });
  await mkdir(join(staging, "attachments"), { recursive: true });

  const seeds = join(target, "seeds");
  if (await pathExists(seeds)) {
    await cp(seeds, join(staging, "seeds"), { recursive: true });
  }

  for (const page of snapshot.pages) {
    await writeFile(
      join(staging, "pages", safeWikiFilename(page.slug)),
      renderWikiPage(page, snapshot.project),
      "utf8",
    );
  }

  await writeFile(
    join(staging, "metadata", "project.json"),
    json(pickDefined(snapshot.project, PROJECT_FIELDS)),
    "utf8",
  );
  await writeFile(
    join(staging, "metadata", "wiki-links.json"),
    json(snapshot.manifest.wiki_links),
    "utf8",
  );
  await writeFile(
    join(staging, "metadata", "attachments.json"),
    json(snapshot.manifest.attachments),
    "utf8",
  );
  await writeFile(
    join(staging, "metadata", "diagnostics.json"),
    json(snapshot.diagnostics),
    "utf8",
  );
  await writeFile(join(staging, "manifest.json"), json(snapshot.manifest), "utf8");

  for (const file of snapshot.attachmentFiles ?? []) {
    const wikiDirectory = safeWikiFilename(file.wikiSlug).replace(/\.md$/u, "");
    const filename = safeAttachmentFilename(file.filename);
    const outputDirectory = join(staging, "attachments", wikiDirectory);
    await mkdir(outputDirectory, { recursive: true });
    await writeFile(join(outputDirectory, filename), file.bytes);
  }

  const writtenManifest = JSON.parse(
    await readFile(join(staging, "manifest.json"), "utf8"),
  );
  if (writtenManifest.pages.length !== snapshot.pages.length) {
    throw new Error("Staged snapshot validation failed: page count mismatch");
  }

  await mkdir(dirname(target), { recursive: true });
  const hadTarget = await pathExists(target);
  try {
    if (hadTarget) await rename(target, backup);
    await rename(staging, target);
    if (hadTarget) await rm(backup, { recursive: true, force: true });
  } catch (error) {
    if (!(await pathExists(target)) && (await pathExists(backup))) {
      await rename(backup, target);
    }
    throw error;
  } finally {
    await rm(staging, { recursive: true, force: true });
  }
}
