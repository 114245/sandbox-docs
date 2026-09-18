#!/usr/bin/env node

import { createHash } from "node:crypto";
import { readdir, readFile, stat } from "node:fs/promises";
import { join, resolve } from "node:path";
import { pathToFileURL } from "node:url";

function sha256(value) {
  return createHash("sha256").update(value).digest("hex");
}

async function listFiles(directory) {
  const files = [];
  for (const entry of await readdir(directory, { withFileTypes: true })) {
    const path = join(directory, entry.name);
    if (entry.isDirectory()) files.push(...(await listFiles(path)));
    if (entry.isFile()) files.push(path);
  }
  return files.sort();
}

async function assertFile(path, label) {
  try {
    const details = await stat(path);
    if (!details.isFile()) throw new Error(`${label} is not a file: ${path}`);
  } catch (error) {
    if (error.code === "ENOENT") {
      throw new Error(`${label} does not exist: ${path}`);
    }
    throw error;
  }
}

export async function validateTaigaDocs(root = process.cwd()) {
  const sourceRoot = join(root, "docs", "taiga-source");
  const pagesRoot = join(sourceRoot, "pages");
  const manifest = JSON.parse(
    await readFile(join(sourceRoot, "manifest.json"), "utf8"),
  );
  const pageFiles = await listFiles(pagesRoot);

  if (pageFiles.length !== manifest.pages.length) {
    throw new Error(
      `Manifest/page count mismatch: ${manifest.pages.length} vs ${pageFiles.length}`,
    );
  }

  for (const page of manifest.pages) {
    const path = join(pagesRoot, `${page.slug}.md`);
    await assertFile(path, `Manifest page ${page.slug}`);
    const actualHash = sha256(await readFile(path));
    if (actualHash !== page.sha256) {
      throw new Error(`Hash mismatch for wiki page: ${page.slug}`);
    }
  }

  for (const attachment of manifest.attachment_files ?? []) {
    const path = join(
      sourceRoot,
      "attachments",
      attachment.wiki_slug,
      attachment.filename,
    );
    await assertFile(path, `Manifest attachment ${attachment.filename}`);
    const body = await readFile(path);
    if (body.byteLength !== attachment.size || sha256(body) !== attachment.sha256) {
      throw new Error(`Attachment integrity mismatch: ${attachment.filename}`);
    }
  }

  const guidePath = join(root, "docs", "convenciones-taiga.md");
  const guide = await readFile(guidePath, "utf8");
  const references = [
    ...guide.matchAll(
      /\]\((taiga-source\/(?:pages|seeds|metadata)\/[^)#]+)(?:#[^)]+)?\)/gu,
    ),
  ].map((match) => match[1]);
  if (references.length === 0) {
    throw new Error("The conventions guide has no local source references");
  }
  for (const reference of new Set(references)) {
    const path = resolve(join(root, "docs"), reference);
    if (!path.startsWith(resolve(join(root, "docs", "taiga-source")))) {
      throw new Error(`Unsafe source reference: ${reference}`);
    }
    await assertFile(path, `Guide source reference ${reference}`);
  }

  const generatedFiles = [...(await listFiles(sourceRoot)), guidePath];
  const secretPatterns = [
    /Authorization\s*:\s*Bearer/giu,
    /Bearer\s+[A-Za-z0-9._~-]{12,}/gu,
    /[?&]token=(?!\[REDACTED\])[^&#)\s]+/giu,
    /"auth_token"\s*:\s*"(?!\[REDACTED\])[^"\n]+"/giu,
    /TAIGA_(?:SOURCE_)?PASSWORD\s*=\s*\S+/gu,
  ];
  const findings = [];
  for (const path of generatedFiles) {
    const body = await readFile(path);
    if (body.includes(0)) continue;
    const text = body.toString("utf8");
    for (const pattern of secretPatterns) {
      pattern.lastIndex = 0;
      if (pattern.test(text)) findings.push(path);
    }
  }
  if (findings.length > 0) {
    throw new Error(
      `Potential secret found in generated documentation: ${[
        ...new Set(findings),
      ].join(", ")}`,
    );
  }

  return {
    pages: manifest.pages.length,
    attachments: (manifest.attachment_files ?? []).length,
    references: references.length,
    secretFindings: findings.length,
  };
}

const isDirect =
  process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href;

if (isDirect) {
  validateTaigaDocs()
    .then((result) => {
      process.stdout.write(
        `Validación OK: ${result.pages} páginas, ${result.attachments} adjuntos, ${result.references} referencias, 0 secretos.\n`,
      );
    })
    .catch((error) => {
      process.stderr.write(`Error: ${error.message}\n`);
      process.exitCode = 1;
    });
}
