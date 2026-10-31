#!/usr/bin/env node
// Mint a JWT-shaped static token for self-hosted (DB_SYNC_STATIC_TOKEN) auth.
//
// The node adapter compares the bearer token verbatim against
// DB_SYNC_STATIC_TOKEN, so any unique string works; the JWT shape keeps
// client-side JWT parsing happy (CLI reads sub/exp from the payload).
//
// Usage:
//   node worker/scripts/mint_static_token.mjs [user-id] [--random]
//
//   --random   generate a random 32-byte secret as the signature part
//              (default: fixed placeholder; always use --random for real use)

import { randomBytes } from "node:crypto";

const args = process.argv.slice(2);
const random = args.includes("--random");
const userId = args.find((a) => !a.startsWith("--")) || "local-user";

const b64u = (buf) => Buffer.from(buf).toString("base64url");
const header = b64u(JSON.stringify({ alg: "none", typ: "JWT" }));
// exp far in the future (2100-01-01) so client/server expiry checks pass.
const payload = b64u(JSON.stringify({ sub: userId, iat: Math.floor(Date.now() / 1000), exp: 4102444800 }));
const signature = random ? randomBytes(32).toString("base64url") : "static-token-signature";

const token = `${header}.${payload}.${signature}`;
console.log(token);
if (!random) {
  console.error("warning: fixed signature; rerun with --random for a real deployment");
}
