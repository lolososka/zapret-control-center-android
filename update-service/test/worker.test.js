import assert from "node:assert/strict";
import { execFileSync } from "node:child_process";
import { readFileSync } from "node:fs";
import { DatabaseSync } from "node:sqlite";
import test from "node:test";
import { fileURLToPath } from "node:url";
import { createWorker } from "../src/worker.js";

const NONCE = Buffer.alloc(32, 7).toString("base64url");
const WEBHOOK_SECRET = "a".repeat(43);
const USER = 123456789;

function fixture() {
  const sqlite = new DatabaseSync(":memory:");
  sqlite.exec(readFileSync(new URL("../migrations/0001_sessions.sql", import.meta.url), "utf8"));
  const DB = {
    prepare(sql) {
      const statement = sqlite.prepare(sql);
      return { bind(...values) {
        return {
          async first() { return statement.get(...values) ?? null; },
          async run() { return { success: true, meta: { changes: Number(statement.run(...values).changes) } }; },
        };
      } };
    },
  };
  let time = 1000;
  let memberStatus = "member";
  let telegramError = false;
  const telegramFailures = [];
  let restrictedMember = false;
  let memberGate;
  const calls = [];
  const env = { DB, BOT_USERNAME: "ZapretExampleBot", CHANNEL_ID: "@Slag0dworld",
    TELEGRAM_BOT_TOKEN: "123456789:" + "x".repeat(35), TELEGRAM_WEBHOOK_SECRET: WEBHOOK_SECRET };
  const worker = createWorker({ now: () => time, fetchImpl: async (url, options) => {
    const method = url.split("/").at(-1);
    const body = JSON.parse(options.body);
    calls.push({ method, body, redirect: options.redirect });
    // Node accepts redirect:error; workerd does not. Keep the mock faithful to
    // this edge-runtime restriction so a Node-only test cannot hide it again.
    assert.equal(options.redirect, "manual");
    if (method === "getChatMember") {
      if (memberGate) await memberGate;
      const failure = telegramFailures.shift();
      if (failure === "network") throw new TypeError("connection lost");
      if (failure === "invalid-json") return new Response("not json", { status: 200, headers: { "Content-Type": "application/json" } });
      if (Number.isInteger(failure)) return Response.json({ ok: false, error_code: failure }, {
        status: failure, headers: failure === 429 ? { "Retry-After": "2" } :
          failure >= 300 && failure < 400 ? { Location: "https://redirect.invalid/never-follow" } : {},
      });
      if (telegramError) return Response.json({ ok: false }, { status: 500 });
      return Response.json({ ok: true, result: { status: memberStatus, user: { id: body.user_id }, is_member: restrictedMember } });
    }
    return Response.json({ ok: true, result: true });
  } });
  const request = (path, { method = "GET", body, token, secret, origin } = {}) => {
    const headers = { "CF-Connecting-IP": "192.0.2.10" };
    if (body !== undefined) headers["Content-Type"] = "application/json";
    if (token) headers.Authorization = `Bearer ${token}`;
    if (secret) headers["X-Telegram-Bot-Api-Secret-Token"] = secret;
    if (origin) headers.Origin = origin;
    return worker.fetch(new Request(`https://membership.example${path}`, {
      method, headers, ...(body === undefined ? {} : { body: JSON.stringify(body) }),
    }), env);
  };
  const create = async (body = { version: "0.3.0", nonce: NONCE }) => {
    const response = await request("/v1/sessions", { method: "POST", body });
    assert.equal(response.status, 201);
    return response.json();
  };
  const start = (id, user = USER, extra = {}) => request("/telegram/webhook", {
    method: "POST", secret: WEBHOOK_SECRET,
    body: { update_id: 1, message: { text: `/start ${id}`, from: { id: user, is_bot: false },
      chat: { id: user, type: "private" }, ...extra } },
  });
  const callback = (id, user = USER) => request("/telegram/webhook", {
    method: "POST", secret: WEBHOOK_SECRET,
    body: { update_id: 2, callback_query: { id: "callback-proof", data: `check:${id}`,
      from: { id: user, is_bot: false }, message: { chat: { id: user, type: "private" } } } },
  });
  return { sqlite, env, worker, request, create, start, callback, calls,
    advance(seconds) { time += seconds; }, setStatus(value, isMember = false) { memberStatus = value; restrictedMember = isMember; },
    failTelegram() { telegramError = true; }, failTelegramOnce(value) { telegramFailures.push(value); },
    blockMembership(promise) { memberGate = promise; } };
}

test("new sessions contain random secrets, fixed bot URL, no raw bearer in D1, no cache/CORS", async () => {
  const f = fixture();
  const s = await f.create();
  assert.match(s.id, /^[\w-]{43}$/);
  assert.match(s.token, /^[\w-]{43}$/);
  assert.notEqual(s.id, s.token);
  assert.equal(s.expiresAt, 1600);
  assert.equal(s.botUrl, `https://t.me/ZapretExampleBot?start=${s.id}`);
  const row = f.sqlite.prepare("SELECT * FROM sessions").get();
  assert.match(row.token_hash, /^[a-f0-9]{64}$/);
  assert.notEqual(row.token_hash, s.token);
  assert.equal(row.telegram_user_id, null);
  const response = await f.request(`/v1/sessions/${s.id}`, { token: s.token });
  assert.equal(response.headers.get("cache-control"), "no-store, private");
  assert.equal(response.headers.get("access-control-allow-origin"), null);
  assert.deepEqual(await response.json(), { status: "pending", version: "0.3.0", nonce: NONCE, expiresAt: 1600 });
  f.sqlite.close();
});

test("malformed versions/nonces and client-supplied identity are rejected", async () => {
  const f = fixture();
  for (const body of [
    { version: "0.3.0-debug", nonce: NONCE }, { version: "00.3.0", nonce: NONCE },
    { version: "0.3.0\n", nonce: NONCE }, { version: "0.3.0\r\n", nonce: NONCE },
    { version: ["0.3.0"], nonce: NONCE }, { version: "0.3.0", nonce: "bad" },
    { version: "0.3.0", nonce: NONCE.slice(0, -1) + "B" },
    { version: "0.3.0", nonce: NONCE, userId: USER },
  ]) assert.equal((await f.request("/v1/sessions", { method: "POST", body })).status, 400);
  const s = await f.create();
  const response = await f.request(`/v1/sessions/${s.id}/authorize`, { method: "POST", token: s.token, body: { userId: USER } });
  assert.equal(response.status, 400);
  for (const body of [{ version: "0.3.1" }, { nonce: Buffer.alloc(32, 2).toString("base64url") }]) {
    assert.equal((await f.request(`/v1/sessions/${s.id}/authorize`, { method: "POST", token: s.token, body })).status, 400);
  }
  assert.equal(f.calls.length, 0);
  f.sqlite.close();
});

test("forged webhook cannot bind, and bots/groups/mismatched sender IDs are ignored", async () => {
  const f = fixture();
  const s = await f.create();
  assert.equal((await f.request("/telegram/webhook", { method: "POST", secret: "wrong", body: { update_id: 1 } })).status, 401);
  assert.equal((await f.request("/telegram/webhook", { method: "POST", body: { update_id: 1 } })).status, 401);
  await f.start(s.id, USER, { chat: { type: "group", id: USER } });
  await f.start(s.id, USER, { chat: { type: "private", id: USER + 1 } });
  await f.start(s.id, USER, { from: { id: USER, is_bot: true } });
  assert.equal(f.sqlite.prepare("SELECT telegram_user_id FROM sessions").get().telegram_user_id, null);
  assert.equal(f.calls.length, 0);
  f.sqlite.close();
});

test("payload start only binds first account and authorization checks the bound ID", async () => {
  const f = fixture();
  const s = await f.create();
  const startResponse = await f.start(s.id);
  assert.equal(startResponse.status, 200);
  assert.equal((await startResponse.json()).method, "sendMessage");
  await f.start(s.id, USER + 1);
  assert.equal(f.calls.filter((call) => call.method === "getChatMember").length, 0);
  assert.equal(f.sqlite.prepare("SELECT telegram_user_id FROM sessions").get().telegram_user_id, String(USER));
  const response = await f.request(`/v1/sessions/${s.id}/authorize`, { method: "POST", token: s.token });
  assert.deepEqual(await response.json(), { status: "verified", version: "0.3.0", nonce: NONCE, expiresAt: 1600 });
  const checkCalls = f.calls.filter((call) => call.method === "getChatMember");
  assert.equal(checkCalls.length, 1);
  assert.deepEqual(checkCalls[0].body, { chat_id: "@Slag0dworld", user_id: USER });
  f.sqlite.close();
});

test("missing token, wrong token, and another session's token cannot poll/authorize", async () => {
  const f = fixture();
  const s = await f.create();
  const other = await f.create({ version: "0.3.1", nonce: Buffer.alloc(32, 8).toString("base64url") });
  for (const token of [undefined, other.token, Buffer.alloc(32, 1).toString("base64url")]) {
    assert.equal((await f.request(`/v1/sessions/${s.id}`, { token })).status, 401);
    assert.equal((await f.request(`/v1/sessions/${s.id}/authorize`, { method: "POST", token })).status, 401);
  }
  assert.equal(f.calls.length, 0);
  const response = await f.request(`/v1/sessions/${other.id}`, { token: other.token });
  assert.equal((await response.json()).version, "0.3.1");
  f.sqlite.close();
});

test("unsubscribed/restricted-not-member deny; only authorize can confirm a new subscription", async () => {
  const f = fixture();
  const s = await f.create();
  f.setStatus("left");
  await f.start(s.id);
  let response = await f.request(`/v1/sessions/${s.id}/authorize`, { method: "POST", token: s.token });
  assert.equal((await response.json()).status, "not_member");
  f.setStatus("restricted", false);
  await f.callback(s.id); // legacy buttons are acknowledged without an outbound check
  response = await f.request(`/v1/sessions/${s.id}/authorize`, { method: "POST", token: s.token });
  assert.equal((await response.json()).status, "not_member");
  response = await f.request(`/v1/sessions/${s.id}`, { token: s.token });
  assert.equal((await response.json()).status, "not_member");
  f.setStatus("restricted", true);
  await f.callback(s.id);
  response = await f.request(`/v1/sessions/${s.id}/authorize`, { method: "POST", token: s.token });
  assert.equal((await response.json()).status, "verified");
  f.sqlite.close();
});

test("expired sessions cannot rebind, check, or authorize and scheduled cleanup deletes data", async () => {
  const f = fixture();
  const s = await f.create();
  await f.start(s.id);
  f.advance(600);
  const before = f.calls.filter((call) => call.method === "getChatMember").length;
  await f.start(s.id);
  await f.callback(s.id);
  const response = await f.request(`/v1/sessions/${s.id}/authorize`, { method: "POST", token: s.token });
  assert.deepEqual(await response.json(), { status: "expired", version: "0.3.0", nonce: NONCE, expiresAt: 1600 });
  assert.equal(f.calls.filter((call) => call.method === "getChatMember").length, before);
  await f.worker.scheduled({}, f.env);
  assert.equal(f.sqlite.prepare("SELECT COUNT(*) AS count FROM sessions").get().count, 0);
  assert.equal(f.sqlite.prepare("SELECT COUNT(*) AS count FROM rate_limits").get().count, 2); // latest authorize + expired-session bot-click buckets
  f.sqlite.close();
});

test("fresh recheck failure invalidates previously verified entitlement; leaving channel also denies", async () => {
  const f = fixture();
  const s = await f.create();
  await f.start(s.id);
  let response = await f.request(`/v1/sessions/${s.id}/authorize`, { method: "POST", token: s.token });
  assert.equal((await response.json()).status, "verified");
  assert.equal(f.sqlite.prepare("SELECT verified_until FROM sessions").get().verified_until, 1600);
  f.setStatus("left");
  response = await f.request(`/v1/sessions/${s.id}/authorize`, { method: "POST", token: s.token });
  assert.equal((await response.json()).status, "not_member");
  assert.equal(f.sqlite.prepare("SELECT verified_until FROM sessions").get().verified_until, null);
  f.setStatus("member");
  response = await f.request(`/v1/sessions/${s.id}/authorize`, { method: "POST", token: s.token });
  assert.equal((await response.json()).status, "verified");
  assert.equal(f.sqlite.prepare("SELECT verified_until FROM sessions").get().verified_until, 1600);
  f.failTelegram();
  response = await f.request(`/v1/sessions/${s.id}/authorize`, { method: "POST", token: s.token });
  assert.equal(response.status, 503);
  assert.deepEqual(await response.json(), { error: "telegram_unavailable" });
  response = await f.request(`/v1/sessions/${s.id}`, { token: s.token });
  assert.equal((await response.json()).status, "pending");
  assert.equal(f.sqlite.prepare("SELECT verified_until FROM sessions").get().verified_until, null);
  f.sqlite.close();
});

test("authorize retries one network, malformed JSON, or 5xx failure but never retries Telegram 4xx", async () => {
  for (const failure of ["network", "invalid-json", 500, 503]) {
    const transient = fixture();
    const session = await transient.create();
    await transient.start(session.id);
    transient.failTelegramOnce(failure);
    const response = await transient.request(`/v1/sessions/${session.id}/authorize`, { method: "POST", token: session.token });
    assert.equal(response.status, 200, String(failure));
    assert.equal((await response.json()).status, "verified");
    assert.equal(transient.calls.filter((call) => call.method === "getChatMember").length, 2);
    transient.sqlite.close();
  }

  const rejected = fixture();
  const rejectedSession = await rejected.create();
  await rejected.start(rejectedSession.id);
  rejected.failTelegramOnce(401);
  let response = await rejected.request(`/v1/sessions/${rejectedSession.id}/authorize`, {
    method: "POST", token: rejectedSession.token,
  });
  assert.equal(response.status, 503);
  assert.deepEqual(await response.json(), { error: "telegram_unavailable" });
  assert.equal(rejected.calls.filter((call) => call.method === "getChatMember").length, 1);
  rejected.sqlite.close();

  const limited = fixture();
  const limitedSession = await limited.create();
  await limited.start(limitedSession.id);
  limited.failTelegramOnce(429);
  response = await limited.request(`/v1/sessions/${limitedSession.id}/authorize`, {
    method: "POST", token: limitedSession.token,
  });
  assert.equal(response.status, 429);
  assert.equal(response.headers.get("Retry-After"), "2");
  assert.deepEqual(await response.json(), { error: "rate_limited" });
  assert.equal(limited.calls.filter((call) => call.method === "getChatMember").length, 1);
  limited.sqlite.close();
});

test("Telegram redirects are rejected without following or retrying and invalidate a previous grant", async () => {
  const f = fixture();
  const s = await f.create();
  await f.start(s.id);
  let response = await f.request(`/v1/sessions/${s.id}/authorize`, { method: "POST", token: s.token });
  assert.equal((await response.json()).status, "verified");
  const callsBefore = f.calls.length;
  f.failTelegramOnce(302);
  response = await f.request(`/v1/sessions/${s.id}/authorize`, { method: "POST", token: s.token });
  assert.equal(response.status, 503);
  assert.deepEqual(await response.json(), { error: "telegram_unavailable" });
  assert.equal(f.calls.length, callsBefore + 1);
  assert.equal(f.calls.at(-1).redirect, "manual");
  assert.equal(f.calls.at(-1).method, "getChatMember");
  response = await f.request(`/v1/sessions/${s.id}`, { token: s.token });
  assert.equal((await response.json()).status, "pending");
  assert.equal(f.sqlite.prepare("SELECT verified_until FROM sessions").get().verified_until, null);
  f.sqlite.close();
});

test("legacy callback replay is acknowledged without membership checks or grants", async () => {
  const f = fixture();
  const s = await f.create();
  await f.start(s.id);
  const foreign = await f.callback(s.id, USER + 1);
  assert.equal((await foreign.json()).method, "answerCallbackQuery");
  const own = await f.callback(s.id);
  assert.equal((await own.json()).method, "answerCallbackQuery");
  assert.equal(f.calls.filter((call) => call.method === "getChatMember").length, 0);
  const response = await f.request(`/v1/sessions/${s.id}`, { token: s.token });
  assert.equal((await response.json()).status, "pending");
  f.sqlite.close();
});

test("concurrent authorization uses a lease and payload starts cannot rebind", async () => {
  const f = fixture();
  const s = await f.create();
  await f.start(s.id);
  let release;
  f.blockMembership(new Promise((resolve) => { release = resolve; }));
  const pending = f.request(`/v1/sessions/${s.id}/authorize`, { method: "POST", token: s.token });
  while (!f.calls.some((call) => call.method === "getChatMember")) await new Promise((resolve) => setImmediate(resolve));
  await f.start(s.id, USER + 1);
  const response = await f.request(`/v1/sessions/${s.id}/authorize`, { method: "POST", token: s.token });
  assert.equal(response.status, 429);
  assert.equal(response.headers.get("retry-after"), "2");
  assert.equal(f.calls.filter((call) => call.method === "getChatMember").length, 1);
  release();
  assert.equal((await pending).status, 200);
  assert.equal(f.sqlite.prepare("SELECT telegram_user_id FROM sessions").get().telegram_user_id, String(USER));
  f.sqlite.close();
});

test("routes/methods/origins/queries/body size are rejected and rate-limit stores only short-lived digests", async () => {
  const f = fixture();
  assert.equal((await f.request("/unknown")).status, 404);
  assert.equal((await f.request("/v1/sessions")).status, 405);
  assert.equal((await f.request("/telegram/webhook")).status, 405);
  assert.equal((await f.request("/v1/sessions?token=secret", { method: "POST" })).status, 400);
  assert.equal((await f.request("/v1/sessions", { method: "POST", body: {}, origin: "https://evil.example" })).status, 403);
  assert.equal((await f.request("/v1/sessions", { method: "POST", body: { filler: "x".repeat(1024) } })).status, 413);
  for (let i = 0; i < 5; i++) await f.create();
  const limited = await f.request("/v1/sessions", { method: "POST", body: { version: "0.3.0", nonce: NONCE } });
  assert.equal(limited.status, 429);
  const row = f.sqlite.prepare("SELECT * FROM rate_limits").get();
  assert.match(row.bucket_key, /^[a-f0-9]{64}$/);
  assert.equal(row.expires_at, 1080);
  assert.equal(JSON.stringify(row).includes("192.0.2.10"), false);
  f.sqlite.close();
});

test("wrong channel/missing secrets fail closed without outbound requests", async () => {
  const f = fixture();
  f.env.CHANNEL_ID = "@other";
  assert.equal((await f.request("/v1/sessions", { method: "POST", body: { version: "0.3.0", nonce: NONCE } })).status, 503);
  assert.equal(f.calls.length, 0);
  f.sqlite.close();
});

test("bot usernames must be exactly 5..32 ASCII characters and end in bot", async () => {
  const f = fixture();
  for (const username of ["Abot", "A".repeat(30) + "bot", "1ValidBot", "ValidBot\n", "Valid", "БотBot"]) {
    f.env.BOT_USERNAME = username;
    assert.equal((await f.request("/v1/sessions", { method: "POST", body: { version: "0.3.0", nonce: NONCE } })).status, 503);
  }
  for (const username of ["ABbot", "A".repeat(29) + "bot", "ZapretLoLBot"]) {
    f.env.BOT_USERNAME = username;
    await f.create();
  }
  f.sqlite.close();
});

test("webhook /start and callbacks share per-user quota, acknowledge spam and resume next minute", async () => {
  const f = fixture();
  const s = await f.create();
  await f.start(s.id);
  for (let i = 0; i < 9; i++) assert.equal((await f.callback(s.id)).status, 200);
  const callsAtLimit = f.calls.length;
  const checksAtLimit = f.calls.filter((call) => call.method === "getChatMember").length;
  assert.equal(checksAtLimit, 0);
  for (let i = 0; i < 20; i++) {
    assert.equal((await f.start(s.id)).status, 200);
    assert.equal((await f.callback(s.id)).status, 200);
  }
  assert.equal(f.calls.length, callsAtLimit); // webhook replies need no outbound Bot API request
  f.advance(60);
  const resumed = await f.callback(s.id);
  assert.equal((await resumed.json()).method, "answerCallbackQuery");
  assert.equal(f.calls.filter((call) => call.method === "getChatMember").length, checksAtLimit);
  for (const row of f.sqlite.prepare("SELECT * FROM rate_limits").all()) {
    assert.match(row.bucket_key, /^[a-f0-9]{64}$/);
    assert.equal(JSON.stringify(row).includes(String(USER)), false);
  }
  f.sqlite.close();
});

test("/start without argument and /privacy are throttled by the same account budget", async () => {
  const f = fixture();
  const send = (text) => f.request("/telegram/webhook", { method: "POST", secret: WEBHOOK_SECRET,
    body: { update_id: 3, message: { text, from: { id: USER, is_bot: false }, chat: { id: USER, type: "private" } } } });
  let directReplies = 0;
  for (let i = 0; i < 15; i++) {
    const response = await send(i % 2 ? "/start" : "/privacy");
    assert.equal(response.status, 200);
    if ((await response.json()).method === "sendMessage") directReplies++;
  }
  assert.equal(directReplies, 10);
  assert.equal(f.calls.filter((call) => call.method === "getChatMember").length, 0);
  f.advance(60);
  assert.equal((await (await send("/privacy")).json()).method, "sendMessage");
  assert.equal(f.calls.length, 0);
  f.sqlite.close();
});

test("webhook throttling cannot grant after fresh authorization fails or steal callback ownership", async () => {
  const f = fixture();
  const s = await f.create();
  await f.start(s.id);
  const verified = await f.request(`/v1/sessions/${s.id}/authorize`, { method: "POST", token: s.token });
  assert.equal((await verified.json()).status, "verified");
  assert.equal(f.sqlite.prepare("SELECT verified_until FROM sessions").get().verified_until, 1600);
  for (let i = 0; i < 9; i++) await f.callback(s.id);
  const checks = f.calls.filter((call) => call.method === "getChatMember").length;
  await f.callback(s.id, USER + 1); // distinct authenticated account, no ownership
  assert.equal(f.calls.filter((call) => call.method === "getChatMember").length, checks);
  f.failTelegram();
  assert.equal((await f.request(`/v1/sessions/${s.id}/authorize`, { method: "POST", token: s.token })).status, 503);
  const callsAfterFailure = f.calls.length;
  await f.callback(s.id); // throttled: must not resurrect the invalidated result
  assert.equal(f.calls.length, callsAfterFailure);
  const response = await f.request(`/v1/sessions/${s.id}`, { token: s.token });
  assert.equal((await response.json()).status, "pending");
  assert.equal(f.sqlite.prepare("SELECT verified_until FROM sessions").get().verified_until, null);
  f.sqlite.close();
});

test("authorize accepts an empty ReadableStream without Content-Type as supplied by workerd", async () => {
  const f = fixture();
  const s = await f.create();
  const response = await f.worker.fetch(new Request(`https://membership.example/v1/sessions/${s.id}/authorize`, {
    method: "POST", headers: { Authorization: `Bearer ${s.token}`, "CF-Connecting-IP": "192.0.2.10" },
    body: new ReadableStream({ start(controller) { controller.close(); } }), duplex: "half",
  }), f.env);
  assert.equal(response.status, 200);
  assert.deepEqual(await response.json(), { status: "pending", version: "0.3.0", nonce: NONCE, expiresAt: 1600 });
  assert.equal(f.calls.length, 0);
  f.sqlite.close();
});

test("nonempty authorize body still requires JSON Content-Type, even when body is a stream", async () => {
  const f = fixture();
  const s = await f.create();
  for (const contentType of [undefined, "text/plain"]) {
    const headers = { Authorization: `Bearer ${s.token}`, "CF-Connecting-IP": "192.0.2.10" };
    if (contentType) headers["Content-Type"] = contentType;
    const response = await f.worker.fetch(new Request(`https://membership.example/v1/sessions/${s.id}/authorize`, {
      method: "POST", headers,
      body: new ReadableStream({ start(controller) { controller.enqueue(new TextEncoder().encode("{}")); controller.close(); } }),
      duplex: "half",
    }), f.env);
    assert.equal(response.status, 415);
    assert.deepEqual(await response.json(), { error: "json_required" });
  }
  assert.equal(f.calls.length, 0);
  f.sqlite.close();
});

test("setup diagnostics require exact update types and report only errors from the last ten minutes", () => {
  const timestamp = 1_700_000_000;
  const endpoint = "https://membership.example/";
  const run = (allowedUpdates, errorDate) => {
    // A preload replaces all fetches with fixed Telegram responses. No live bot,
    // credentials, webhook, or network connection is used by this CLI test.
    const webhook = { url: `${endpoint}telegram/webhook`, allowed_updates: allowedUpdates,
      pending_update_count: 7, last_error_message: "Wrong response: 503", last_error_date: errorDate };
    const preload = `Date.now = () => ${timestamp * 1000};
      globalThis.fetch = async (url) => {
        const method = url.split('/').at(-1);
        const result = method === 'getMe' ? { is_bot: true, id: ${USER}, username: 'ZapretExampleBot' } :
          method === 'getChatMember' ? { user: { id: ${USER} }, status: 'administrator' } :
          method === 'getWebhookInfo' ? ${JSON.stringify(webhook)} : null;
        if (result === null) throw new Error('Unexpected API request');
        return Response.json({ ok: true, result });
      };`;
    const output = execFileSync(process.execPath, ["--import",
      `data:text/javascript,${encodeURIComponent(preload)}`,
      fileURLToPath(new URL("../scripts/register-webhook.mjs", import.meta.url)), endpoint, "--diagnose"], {
      encoding: "utf8", input: JSON.stringify({ token: "123456789:" + "x".repeat(35),
        secret: WEBHOOK_SECRET, botUsername: "ZapretExampleBot" }), timeout: 5000,
    });
    return JSON.parse(output);
  };
  assert.deepEqual(run(["callback_query", "message"], timestamp - 60), {
    urlMatches: true, allowedUpdatesMatch: true, pendingUpdates: 7,
    lastErrorKind: "http_503", hasRecentError: true,
  });
  for (const updates of [["message"], ["message", "callback_query", "channel_post"],
    ["message", "message"], undefined]) {
    assert.equal(run(updates, timestamp - 60).allowedUpdatesMatch, false);
  }
  for (const errorDate of [undefined, 0, timestamp - 601, timestamp + 1, "1700000000"]) {
    assert.equal(run(["message", "callback_query"], errorDate).hasRecentError, false);
  }
  assert.equal(run(["message", "callback_query"], timestamp - 600).hasRecentError, true);
});
