# Logseq DB Sync (deps/db-sync)

This package contains the DB sync server code and tests used by Logseq.
It includes the Cloudflare Worker implementation and a Node.js adapter for self-hosting.

## Requirements
- Node.js (see repo root for required version)
- Clojure (for shadow-cljs builds)

## Build and Test

### Cloudflare Worker

```bash
cd deps/db-sync
pnpm watch

# open another terminal
cd deps/db-sync/worker
wrangler dev
```

### D1 Schema (Worker)

The worker no longer initializes schema at request time. Apply the D1 schema
via migrations during deployment/CI.

```bash
cd deps/db-sync/worker
wrangler d1 migrations apply logseq-sync-graph-meta-staging --env staging
wrangler d1 migrations apply logseq-sync-graphs-prod --env prod
```

For local development, run `wrangler d1 migrations apply DB --local`.

### Production Graph Lookup

Show the graphs available to a production user by `username` or `user id`:

```bash
cd deps/db-sync
pnpm show-graphs-for-user --username alice
pnpm show-graphs-for-user --user-id us-east-1:example-user-id
```

The script uses `worker/wrangler.toml`, runs against the remote D1 binding `DB`,
defaults to `--env prod`, and prints JSON when `--json` is added.

Show usage totals (total users, total graphs, users created today, graphs created today):

```bash
cd deps/db-sync
pnpm show-usage-stats
pnpm show-usage-stats --days 7
pnpm show-usage-stats --json
```

`created today` uses UTC day boundaries from D1 (`date('now')`).
`active_*_last_n_days` uses deduplicated UTC-day activity rows from
`daily_active_entities` with the provided `--days` window.

Download a graph snapshot into a local sqlite debug file matching local graph DB schema (`kvs` table only):

```bash
cd deps/db-sync
pnpm run download-graph-db -- --graph-id 6f2d7f6f-xxxx-xxxx-xxxx-xxxxxxxxxxxx --admin-token <admin-token>
```

You can also pass `--admin-token <token>` or set `DB_SYNC_ADMIN_TOKEN`. The output defaults to
`tmp/graph-<graph-id>.snapshot.sqlite` and can be changed with `--output`.

Show stored and recomputed checksum for a local sqlite graph db:

```bash
cd deps/db-sync
pnpm run show-sqlite-checksum -- --db ~/Downloads/test.sqlite
```

Delete the graphs owned by a production user after an explicit confirmation:

```bash
cd deps/db-sync
pnpm delete-graphs-for-user --username alice
pnpm delete-graphs-for-user --user-id us-east-1:example-user-id
```

The delete script shows the owned graphs first and requires typing `DELETE`
before it calls the worker delete endpoint for each graph. Set
`DB_SYNC_BASE_URL` and `DB_SYNC_ADMIN_TOKEN` or pass `--base-url` and
`--admin-token` when running it.

Delete a user completely (owned graphs, memberships, keys, and user row):

```bash
cd deps/db-sync
pnpm run delete-user-totally -- --username alice
pnpm run delete-user-totally -- --user-id us-east-1:example-user-id
```

The script prints all linked graphs first, deletes owned graphs through the
admin graph delete endpoint, then removes the user's remaining D1 references.
It requires typing `DELETE` as confirmation.

### Node.js Adapter (self-hosted)

Build the adapter:

```bash
cd deps/db-sync
pnpm build:node-adapter
```

Run the adapter with Cognito auth:

```bash
DB_SYNC_PORT=8787 \
COGNITO_ISSUER=https://cognito-idp.us-east-1.amazonaws.com/us-east-1_dtagLnju8 \
COGNITO_CLIENT_ID=69cs1lgme7p8kbgld8n5kseii6 \
COGNITO_JWKS_URL=https://cognito-idp.us-east-1.amazonaws.com/us-east-1_dtagLnju8/.well-known/jwks.json \
node worker/dist/node-adapter.js
```

### Tests

Run db-sync tests (includes Node adapter tests):

```bash
cd deps/db-sync
pnpm test:node-adapter
```

### Self-hosting with Docker (domain + TLS + volume)

`docker/` contains a ready-to-use deployment: a multi-stage image that compiles
the node adapter, a Caddy reverse proxy that serves it on your domain with
automatic Let's Encrypt TLS, and a named volume for the sqlite data.

```bash
cd deps/db-sync/docker
cp .env.example .env
# edit .env: SYNC_DOMAIN (DNS A/AAAA -> this host, ports 80/443 open),
# DB_SYNC_STATIC_TOKEN (see below), optionally DB_SYNC_READONLY=1
node ../worker/scripts/mint_static_token.mjs --random   # paste into .env
docker compose up -d --build
```

Endpoints once up:

| Endpoint | Purpose |
| --- | --- |
| `https://$SYNC_DOMAIN/health` | liveness |
| `https://$SYNC_DOMAIN/sync/<graph-id>/...` | Logseq sync (HTTP + WebSocket RTC) |
| `https://$SYNC_DOMAIN/api/v1/...` | semantic REST API (OpenAPI at `/openapi.json`) |
| `https://$SYNC_DOMAIN/mcp` | MCP endpoint, `POST` JSON-RPC (tools/list, tools/call) |

Build the image manually (context must be the repo root):

```bash
docker build -f deps/db-sync/docker/Dockerfile -t logseq-db-sync-node:latest .
docker run --rm -p 127.0.0.1:8787:8787 -v logseq-data:/data \
  -e DB_SYNC_STATIC_TOKEN=... logseq-db-sync-node:latest
```

### Local static-token auth (no Cognito)

For self-hosted servers you can skip Cognito/JWKS entirely. Set
`DB_SYNC_STATIC_TOKEN` to a unique JWT-shaped string (mint one with
`node worker/scripts/mint_static_token.mjs --random [user-id]`) and optionally
`DB_SYNC_STATIC_USER_ID` (default `local-user`). A request whose bearer token
exactly matches is authenticated as that user with full `logseq/read
logseq/write` scope; graphs and personal access tokens created with it are
owned by it. The hosted worker is unaffected (the env var is simply unset).

Create a graph-scoped MCP credential (works with or without the static token):

```bash
curl -X POST https://$SYNC_DOMAIN/api/v1/personal-access-tokens \
  -H "Authorization: Bearer $DB_SYNC_STATIC_TOKEN" -H "content-type: application/json" \
  -d '{"graph-id": "<graph-uuid>", "permission": "read"}'
# -> {"token": "logseq_pat_...", ...}; use it as Bearer for /api/v1 and /mcp
```

### Readonly mode

Set `DB_SYNC_READONLY=1` to run the server as a read-only mirror: sync
`pull`/`snapshot/download`/`snapshot/stream` and all GET `/api/v1` (and the
corresponding MCP read tools) keep working, while `snapshot/upload`,
`tx/batch` (HTTP and WebSocket) and all write-scoped `/api/v1` operations are
rejected with `403 {"error":"server is read-only"}`.

### MCP endpoint on the node adapter

`POST /mcp` (Streamable HTTP, stateless JSON-RPC 2.0) exposes one tool per
semantic API operation (`listGraphs`, `listPages`, `searchGraph`, ...), named
after the operationId in `/openapi.json`. Auth is the same as the REST API:
`Authorization: Bearer logseq_pat_...` (graph-scoped) or the static token.
Example:

```bash
curl -X POST https://$SYNC_DOMAIN/mcp \
  -H "Authorization: Bearer logseq_pat_..." \
  -H "content-type: application/json" -H "accept: application/json, text/event-stream" \
  -d '{"jsonrpc":"2.0","id":1,"method":"tools/call",
       "params":{"name":"searchGraph","arguments":{"graph-id":"<uuid>","q":"project"}}}'
```

### Pointing a local vault at the server

On the machine with the vault (e.g. macOS, DB graphs in `~/logseq/graphs`):

```bash
# 1. credentials: create the CLI auth file (default path ~/logseq/auth.json)
#    with these fields: provider "static", the static token as the id-token
#    field, sub "local-user", and expires-at 4102444800000 (epoch ms, year
#    2100, so the CLI never tries to refresh it). For example with jq:
jq -n --arg t "$DB_SYNC_STATIC_TOKEN" '{provider:"static","id-token":$t,sub:"local-user","expires-at":4102444800000}' > ~/logseq/auth.json

# 2. server endpoints (persisted in ~/logseq/cli.edn)
logseq sync config set http-base https://$SYNC_DOMAIN
logseq sync config set ws-url 'wss://$SYNC_DOMAIN/sync/%s'

# 3. push the vault, then keep it in two-way sync
logseq sync upload --graph <vault-name>
logseq sync start  --graph <vault-name>   # add --e2ee-password <pw> for E2EE graphs
logseq sync status --graph <vault-name>
```

The desktop app can use the same server via Settings -> Logseq Sync -> custom
sync server URL (`https://$SYNC_DOMAIN`).

## Environment Variables

| Variable | Purpose |
| --- | --- |
| DB_SYNC_PORT | HTTP server port |
| DB_SYNC_BASE_URL | External base URL for asset links |
| DB_SYNC_ADMIN_TOKEN | Admin-only token (header `x-db-sync-admin-token`): passes the graph access check on sync and asset routes, and the operator graph deletion endpoints |
| DB_SYNC_DATA_DIR | Data directory for sqlite + assets |
| DB_SYNC_STORAGE_DRIVER | Storage backend selection (sqlite) |
| DB_SYNC_ASSETS_DRIVER | Assets backend selection (filesystem) |
| SENTRY_DSN | Sentry DSN |
| SENTRY_RELEASE | Release identifier for Sentry events and sourcemaps |
| SENTRY_ENVIRONMENT | Sentry environment name (prod, staging, etc.) |
| SENTRY_TRACES_SAMPLE_RATE | Traces sample rate (0.0 - 1.0) |
| COGNITO_ISSUER | Cognito issuer URL |
| COGNITO_CLIENT_ID | Cognito client id |
| COGNITO_JWKS_URL | Cognito JWKS URL |
| DB_SYNC_STATIC_TOKEN | Self-hosted local auth: exact bearer-token match authenticates as DB_SYNC_STATIC_USER_ID (default `local-user`) with full read+write scope; unset disables |
| DB_SYNC_STATIC_USER_ID | User id for the static token (default `local-user`) |
| DB_SYNC_READONLY | `1`/`true` to reject all writes: sync uploads/tx batches (HTTP+WS) and write-scoped `/api/v1` ops return `403 server is read-only`; reads and MCP read tools keep working |

## Notes
- Runtime and db-sync engineering guidance is consolidated in `../../docs/agent-guide/implemented/architecture/2026-08-24-logseq-runtime-and-engineering-guide.md`; current protocol and route definitions live under `src/logseq/db_sync/worker/`.
