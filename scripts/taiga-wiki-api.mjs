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

  if (missing.length > 0) {
    throw new Error(`Missing source variables: ${missing.join(", ")}`);
  }

  const apiUrl = new URL(values.TAIGA_SOURCE_URL);
  const isLocalHttp =
    apiUrl.protocol === "http:" &&
    ["localhost", "127.0.0.1"].includes(apiUrl.hostname);
  if (apiUrl.protocol !== "https:" && !isLocalHttp) {
    throw new Error("TAIGA_SOURCE_URL must use HTTPS");
  }

  const expectedProjectId = values.TAIGA_SOURCE_PROJECT_ID
    ? Number(values.TAIGA_SOURCE_PROJECT_ID)
    : null;
  if (
    expectedProjectId !== null &&
    (!Number.isInteger(expectedProjectId) || expectedProjectId <= 0)
  ) {
    throw new Error("TAIGA_SOURCE_PROJECT_ID must be a positive integer");
  }

  return {
    apiOrigin: apiUrl.origin,
    projectSlug: values.TAIGA_SOURCE_PROJECT_SLUG,
    expectedProjectId,
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

    if (!response.ok) {
      throw new Error(`Taiga auth failed (${response.status})`);
    }

    const data = await response.json();
    if (!data.auth_token) {
      throw new Error("Taiga auth response did not include a token");
    }
    token = data.auth_token;
  }

  async function request(method, path, params = {}) {
    if (method !== "GET") {
      throw new Error("Project data client is GET-only");
    }
    if (!allowedPaths.some((pattern) => pattern.test(path))) {
      throw new Error(`Path is not allowlisted: ${path}`);
    }
    if (!token) {
      throw new Error("Authenticate before reading project data");
    }

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
