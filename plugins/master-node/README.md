# Master Node plugin

Pushes pages from your local vault to a self-hosted Logseq **master node**
(the `deps/db-sync` node adapter) and checks its status from inside the app.

## Architecture

```
Laptop (Logseq app + this plugin)
   │  native sync (Settings -> custom sync server URL)  ── full graph sync
   │  plugin "push page"  ── REST /api/v1  ── per-page idempotent push
   ▼
Remote master node  (deps/db-sync/docker: node adapter + Caddy, TLS, volume)
   │
   ├── /sync/<graph-id>/...   Logseq sync protocol (HTTP + WebSocket)
   ├── /api/v1/...            semantic REST API
   └── /mcp                   MCP endpoint (JSON-RPC 2.0)
   ▲
   │
AI agent (MCP client, e.g. an assistant with tool use)  ── converse with your graph
```

## Setup

1. **Deploy the remote node** — see `deps/db-sync/docker/README` section in
   `deps/db-sync/README.md`. Note the `SYNC_DOMAIN` and the
   `DB_SYNC_STATIC_TOKEN` (mint with
   `node deps/db-sync/worker/scripts/mint_static_token.mjs --random`).

2. **Create the remote graph once** from the machine with the vault (the
   plugin cannot create graphs):

   ```bash
   jq -n --arg t "$DB_SYNC_STATIC_TOKEN" \
     '{provider:"static",id-token:$t,sub:"local-user",expires-at:4102444800000}' > ~/logseq/auth.json
   logseq sync config set http-base https://$SYNC_DOMAIN
   logseq sync config set ws-url 'wss://$SYNC_DOMAIN/sync/%s'
   logseq sync upload --graph <vault-name>
   ```

3. **Load this plugin** in Logseq: Settings -> Features -> enable Plugin
   system, then Plugins -> Load unpacked plugin -> select this directory.

4. **Configure the plugin** (Settings -> Plugins -> Master Node):
   - `remoteUrl`: `https://$SYNC_DOMAIN`
   - `token`: the static token, or a graph-scoped personal access token
     (`POST /api/v1/personal-access-tokens` with `{"graph-id": "...",
     "permission": "write"}`)
   - `graphName`: the remote graph name (your vault name)

## Usage

- `/Push page to master node` (or command palette: "Push current page to
  master node") — pushes the current page's block tree. Pushes are
  idempotent: block UUIDs are preserved, so the server reuses existing
  blocks instead of duplicating them.
- `/Master node status` — health check + list of remote graphs.
- `/Master node agent info` — shows the remote URL and MCP endpoint to
  configure an AI agent against.

## Agent access (MCP)

Point any MCP client at `https://$SYNC_DOMAIN/mcp` with the same bearer
token. It exposes one tool per semantic API operation (`searchGraph`,
`listPages`, `getPage`, `createPage`, ...) — stateless JSON-RPC 2.0 over
HTTP POST. See the "MCP endpoint on the node adapter" section of
`deps/db-sync/README.md`.

## Notes

- The plugin pushes page *content* (blocks as a tree). Full graph sync
  (two-way, E2EE, assets) stays with the native sync / CLI described in
  `deps/db-sync/README.md`.
- If the remote node runs with `DB_SYNC_READONLY=1`, pushes are rejected
  with `403 server is read-only` (by design for read-only mirrors).
