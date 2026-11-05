// logseq-master-node: push pages from this vault to a self-hosted
// Logseq master node (deps/db-sync node adapter) over its semantic REST API.
//
// Requires the remote node from deps/db-sync/docker (or any db-sync node
// adapter deployment) and either the static token (DB_SYNC_STATIC_TOKEN) or a
// graph-scoped personal access token created via
// POST /api/v1/personal-access-tokens.
//
// The remote graph itself is created by the initial full sync
// (`logseq sync upload --graph <name>`); this plugin then keeps individual
// pages in sync with idempotent pushes (BlockTree UUIDs are reused on the
// server, so re-pushing the same page does not duplicate blocks).

import "@logseq/libs";

const REQUIRED_SETTINGS = ["remoteUrl", "token", "graphName"];

function settings() {
  return {
    remoteUrl: (logseq.settings.remoteUrl || "").replace(/\/+$/, ""),
    token: logseq.settings.token || "",
    graphName: logseq.settings.graphName || "",
  };
}

function missingSettings() {
  const s = settings();
  return REQUIRED_SETTINGS.filter((k) => !s[k]);
}

async function apiFetch(path, opts = {}) {
  const { remoteUrl, token } = settings();
  const res = await fetch(`${remoteUrl}${path}`, {
    ...opts,
    headers: {
      authorization: `Bearer ${token}`,
      "content-type": "application/json",
      ...(opts.headers || {}),
    },
  });
  if (!res.ok) {
    let detail = `${res.status}`;
    try {
      const body = await res.json();
      detail = body.error || JSON.stringify(body);
    } catch (_e) {}
    throw new Error(`master node ${res.status}: ${detail}`);
  }
  return res.status === 204 ? null : res.json();
}

// Resolve the remote graph id by exact name using the listGraphs `name` filter.
// listGraphs requires a limit (max 200).
async function resolveGraphId() {
  const { graphName } = settings();
  const data = await apiFetch(
    `/api/v1/graphs?limit=200&name=${encodeURIComponent(graphName)}`
  );
  const graphs = data.graphs || [];
  const match =
    graphs.find(
      (g) => (g["graph-name"] || "").toLowerCase() === graphName.toLowerCase()
    ) || graphs[0];
  if (!match) {
    throw new Error(
      `graph "${graphName}" not found on the master node; run \`logseq sync upload --graph ${graphName}\` once to create it`
    );
  }
  return match["graph-id"];
}

// Find a remote page by exact title via the search API
// (case-insensitive substring search over block titles; we filter exact).
async function findRemotePage(graphId, title) {
  const data = await apiFetch(
    `/api/v1/graphs/${encodeURIComponent(graphId)}/search?q=${encodeURIComponent(
      title
    )}&types=blocks,tags`
  );
  return (data.results || []).find(
    (p) => (p.title || "").toLowerCase() === title.toLowerCase()
  );
}

// Map an editor block tree to the server's BlockTree shape
// ({uuid, title, children}); UUIDs are preserved so pushes are idempotent.
function toBlockTree(blocks) {
  return (blocks || []).map((b) => ({
    uuid: b.uuid,
    title: b.content || "",
    children: toBlockTree(b.children),
  }));
}

async function currentPage() {
  const page = await logseq.Editor.getCurrentPage();
  if (page && (page.originalName || page.name)) {
    return { name: page.originalName || page.name, uuid: page.uuid };
  }
  const block = await logseq.Editor.getCurrentBlock();
  if (block && block.page && block.page.id) {
    const p = await logseq.Editor.getPage(block.page.id);
    if (p) return { name: p.originalName || p.name, uuid: p.uuid };
  }
  return null;
}

async function pushCurrentPage() {
  const missing = missingSettings();
  if (missing.length) {
    logseq.UI.showMsg(
      `Master node: configure ${missing.join(", ")} in plugin settings`,
      "warning"
    );
    return;
  }

  const page = await currentPage();
  if (!page) {
    logseq.UI.showMsg("Master node: open a page first", "warning");
    return;
  }

  try {
    const graphId = await resolveGraphId();
    const blocks = await logseq.Editor.getPageBlocksTree(page.name);
    const tree = toBlockTree(blocks);

    let remotePage = await findRemotePage(graphId, page.name);
    if (!remotePage) {
      remotePage = await apiFetch(
        `/api/v1/graphs/${encodeURIComponent(graphId)}/pages`,
        { method: "POST", body: JSON.stringify({ title: page.name }) }
      );
    }
    if (!tree.length) {
      logseq.UI.showMsg(
        `Master node: "${page.name}" has no blocks to push`,
        "success"
      );
      return;
    }
    await apiFetch(
      `/api/v1/graphs/${encodeURIComponent(graphId)}/block-trees`,
      {
        method: "POST",
        body: JSON.stringify({
          "target-id": remotePage.uuid,
          position: "append",
          blocks: tree,
        }),
      }
    );
    logseq.UI.showMsg(
      `Master node: pushed ${tree.length} top-level block(s) of "${page.name}"`,
      "success"
    );
  } catch (e) {
    logseq.UI.showMsg(`Master node push failed: ${e.message}`, "error");
  }
}

async function showStatus() {
  const missing = missingSettings();
  if (missing.length) {
    logseq.UI.showMsg(
      `Master node: configure ${missing.join(", ")} in plugin settings`,
      "warning"
    );
    return;
  }
  try {
    const { remoteUrl } = settings();
    const health = await fetch(`${remoteUrl}/health`).then((r) => r.json());
    if (!health.ok) throw new Error("health check failed");
    const data = await apiFetch("/api/v1/graphs?limit=200");
    const graphs = data.graphs || [];
    logseq.UI.showMsg(
      `Master node up: ${graphs.length} graph(s) (${graphs
        .map((g) => g["graph-name"] || g["graph-id"])
        .join(", ")})`,
      "success"
    );
  } catch (e) {
    logseq.UI.showMsg(`Master node unreachable: ${e.message}`, "error");
  }
}

async function showAgentConfig() {
  const { remoteUrl, graphName } = settings();
  const info = remoteUrl
    ? `Remote: ${remoteUrl}
MCP endpoint: ${remoteUrl}/mcp
Graph: ${graphName || "(set graphName in settings)"}`
    : "Configure remoteUrl in plugin settings first.";
  logseq.UI.showMsg(info, "info", { timeout: 15000 });
}

function main() {
  logseq.Editor.registerSlashCommand("Push page to master node", async () => {
    await pushCurrentPage();
  });
  logseq.Editor.registerSlashCommand("Master node status", async () => {
    await showStatus();
  });
  logseq.Editor.registerSlashCommand("Master node agent info", async () => {
    await showAgentConfig();
  });

  if (logseq.App && typeof logseq.App.registerCommandPalette === "function") {
    logseq.App.registerCommandPalette(
      {
        key: "master-node-push-page",
        label: "Push current page to master node",
      },
      pushCurrentPage
    );
    logseq.App.registerCommandPalette(
      { key: "master-node-status", label: "Master node status" },
      showStatus
    );
  }

  logseq.onSettingsChanged(() => {
    const missing = missingSettings();
    if (!missing.length) showStatus();
  });

  console.info("logseq-master-node loaded");
}

logseq.ready(main).catch(console.error);
