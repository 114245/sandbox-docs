#!/usr/bin/env node

import { readFile } from "node:fs/promises";
import { basename, join } from "node:path";
import { pathToFileURL } from "node:url";
import {
  createTaigaReadClient,
  parseSourceEnv,
} from "./taiga-wiki-api.mjs";
import {
  analyzeSnapshot,
  buildManifest,
  writeAtomicSnapshot,
} from "./taiga-wiki-snapshot.mjs";

const ENV_FILE = ".env.taiga.source.local";
const HU_SEED = join(
  "docs",
  "taiga-source",
  "seeds",
  "hu-template.raw.md",
);

function parseArguments(args) {
  const allowed = new Set(["--dry-run", "--download-attachments"]);
  const unknown = args.filter((argument) => !allowed.has(argument));
  if (unknown.length > 0) {
    throw new Error(`Unknown argument: ${unknown.join(", ")}`);
  }
  return {
    dryRun: args.includes("--dry-run"),
    downloadAttachments: args.includes("--download-attachments"),
  };
}

function requireArray(value, label) {
  if (!Array.isArray(value)) {
    throw new Error(`Taiga returned an invalid ${label} collection`);
  }
  return value;
}

function validateProject(project, config) {
  if (!project || typeof project !== "object") {
    throw new Error("Taiga returned an invalid project");
  }
  if (project.slug !== config.projectSlug) {
    throw new Error(
      `Taiga project slug mismatch: expected ${config.projectSlug}`,
    );
  }
  if (
    config.expectedProjectId !== null &&
    project.id !== config.expectedProjectId
  ) {
    throw new Error(
      `Taiga project ID mismatch: expected ${config.expectedProjectId}, received ${project.id}`,
    );
  }
  if (project.is_wiki_activated === false) {
    throw new Error("The Taiga wiki module is disabled for the source project");
  }
  if (!Number.isInteger(project.id) || project.id <= 0) {
    throw new Error("Taiga returned an invalid project ID");
  }
}

function validatePageCollection(summaries, pages, projectId) {
  const summaryIds = new Set();
  for (const summary of summaries) {
    if (!Number.isInteger(summary.id) || summaryIds.has(summary.id)) {
      throw new Error("Taiga returned duplicate or invalid wiki page IDs");
    }
    summaryIds.add(summary.id);
  }
  if (pages.length !== summaries.length) {
    throw new Error("Taiga wiki page retrieval is incomplete");
  }
  for (const page of pages) {
    if (!summaryIds.has(page.id)) {
      throw new Error("Taiga returned an unexpected wiki page");
    }
    if (page.project !== undefined && page.project !== projectId) {
      throw new Error(`Wiki page ${page.id} belongs to another project`);
    }
    if (typeof page.slug !== "string" || typeof page.content !== "string") {
      throw new Error(`Taiga returned an incomplete wiki page (${page.id})`);
    }
  }
}

function selectHuPageSlug(pages) {
  const preferred = [
    "historias-de-usuario",
    "historia-de-usuario",
    "template-historias-de-usuario",
    "template-historia-de-usuario",
  ];
  for (const slug of preferred) {
    if (pages.some((page) => page.slug === slug)) return slug;
  }
  return (
    pages.find((page) => /hist(?:oria)?s?.*usuario/iu.test(page.slug))?.slug ??
    null
  );
}

function attachmentFilename(attachment) {
  if (typeof attachment.name === "string" && attachment.name) {
    return attachment.name;
  }
  try {
    return decodeURIComponent(basename(new URL(attachment.url).pathname));
  } catch {
    throw new Error(`Attachment ${attachment.id ?? "unknown"} has no safe filename`);
  }
}

async function readJsonIfPresent(path) {
  try {
    return JSON.parse(await readFile(path, "utf8"));
  } catch (error) {
    if (error.code === "ENOENT" || error instanceof SyntaxError) return null;
    throw error;
  }
}

async function snapshotHasChanged(root, snapshot) {
  const currentManifest = await readJsonIfPresent(
    join(root, "docs", "taiga-source", "manifest.json"),
  );
  const currentDiagnostics = await readJsonIfPresent(
    join(root, "docs", "taiga-source", "metadata", "diagnostics.json"),
  );
  return (
    JSON.stringify(currentManifest) !== JSON.stringify(snapshot.manifest) ||
    JSON.stringify(currentDiagnostics) !== JSON.stringify(snapshot.diagnostics)
  );
}

export async function main({
  root = process.cwd(),
  args = process.argv.slice(2),
  processValues = process.env,
  fetchImpl = fetch,
  stdout = process.stdout,
} = {}) {
  const options = parseArguments(args);
  const envText = await readFile(join(root, ENV_FILE), "utf8");
  const config = parseSourceEnv(envText, processValues);
  const huSeed = await readFile(join(root, HU_SEED), "utf8");
  const client = createTaigaReadClient(config, fetchImpl);

  await client.authenticate();
  const project = await client.getProjectBySlug(config.projectSlug);
  validateProject(project, config);

  const summaries = requireArray(await client.listWiki(project.id), "wiki page");
  const pages = await Promise.all(
    summaries.map((summary) => client.getWikiPage(summary.id)),
  );
  validatePageCollection(summaries, pages, project.id);

  const links = requireArray(await client.listWikiLinks(project.id), "wiki link");
  const attachmentGroups = await Promise.all(
    pages.map(async (page) => ({
      page,
      attachments: requireArray(
        await client.listWikiAttachments(project.id, page.id),
        `attachment for wiki page ${page.id}`,
      ),
    })),
  );
  const attachments = attachmentGroups.flatMap(({ attachments: items }) => items);
  const attachmentFiles = [];

  if (options.downloadAttachments) {
    for (const { page, attachments: items } of attachmentGroups) {
      for (const attachment of items) {
        if (typeof attachment.url !== "string" || !attachment.url) {
          throw new Error(`Attachment ${attachment.id ?? "unknown"} has no URL`);
        }
        attachmentFiles.push({
          wikiSlug: page.slug,
          filename: attachmentFilename(attachment),
          bytes: await client.downloadAttachment(attachment.url),
        });
      }
    }
  }

  const manifest = buildManifest({
    project,
    pages,
    links,
    attachments,
    attachmentFiles,
  });
  const diagnostics = analyzeSnapshot({
    pages,
    links,
    huSeed,
    huPageSlug: selectHuPageSlug(pages),
  });
  const snapshot = {
    project,
    pages,
    links,
    attachments,
    attachmentFiles,
    manifest,
    diagnostics,
  };

  stdout.write(
    `Proyecto: ${project.name} (${project.slug}, ID ${project.id})\n` +
      `Extracción: ${pages.length} páginas, ${links.length} enlaces, ${attachments.length} adjuntos\n` +
      `Diagnósticos: ${diagnostics.length}\n`,
  );

  if (options.dryRun) {
    stdout.write("Dry-run: no se modificaron archivos.\n");
    return { snapshot, written: false };
  }

  const changed = await snapshotHasChanged(root, snapshot);
  if (!changed) {
    stdout.write("Snapshot sin cambios: 0 archivos actualizados.\n");
    return { snapshot, written: false };
  }

  await writeAtomicSnapshot(root, snapshot);
  stdout.write("Snapshot actualizado: docs/taiga-source/\n");
  return { snapshot, written: true };
}

const isDirect =
  process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href;

if (isDirect) {
  main().catch((error) => {
    process.stderr.write(`Error: ${error.message}\n`);
    process.exitCode = 1;
  });
}
