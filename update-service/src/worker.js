const CHANNEL_ID = "@Slag0dworld";
const SESSION_TTL = 600;
const MAX_ACTIVE_SESSIONS = 5000;
const TELEGRAM_ATTEMPTS = 2;
const TELEGRAM_ATTEMPT_TIMEOUT_MS = 3500;
const TELEGRAM_RETRY_DELAY_MS = 100;
const OPAQUE_VALUE = /^[A-Za-z0-9_-]{43}$/;
const STABLE_VERSION = /^(0|[1-9]\d{0,4})\.(0|[1-9]\d{0,4})\.(0|[1-9]\d{0,4})$/;
const encoder = new TextEncoder();

class HttpError extends Error {
  constructor(status, code, retryAfter) {
    super(code);
    this.status = status;
    this.code = code;
    this.retryAfter = retryAfter;
  }
}

function json(value, status = 200, extra = {}) {
  return Response.json(value, {
    status,
    headers: {
      "Cache-Control": "no-store, private",
      "X-Content-Type-Options": "nosniff",
      "Referrer-Policy": "no-referrer",
      ...extra,
    },
  });
}

function telegramWebhookReply(method, body) {
  // Telegram supports invoking one Bot API method directly from a successful
  // webhook response. Unlike retrying sendMessage, this cannot create duplicates.
  return json({ ...body, method });
}

function telegramRetryAfter(headerValue, bodyValue) {
  const value = /^\d+$/.test(headerValue ?? "") ? Number(headerValue) : bodyValue;
  return Number.isInteger(value) && value > 0 ? Math.min(value, 60) : 3;
}

function opaqueValue() {
  return btoa(String.fromCharCode(...crypto.getRandomValues(new Uint8Array(32))))
    .replaceAll("+", "-").replaceAll("/", "_").replaceAll("=", "");
}

function isOpaque(value) {
  if (typeof value !== "string" || !OPAQUE_VALUE.test(value)) return false;
  // A canonical encoding of 32 bytes: the final two unused bits must be zero.
  return "AEIMQUYcgkosw048".includes(value.at(-1));
}

async function sha256(value) {
  const digest = new Uint8Array(await crypto.subtle.digest("SHA-256", encoder.encode(value)));
  return [...digest].map((byte) => byte.toString(16).padStart(2, "0")).join("");
}

async function constantTimeEqual(left, right) {
  const [a, b] = await Promise.all([sha256(left), sha256(right)]);
  let different = 0;
  for (let i = 0; i < a.length; i++) different |= a.charCodeAt(i) ^ b.charCodeAt(i);
  return different === 0;
}

async function readBoundedJson(request, maxBytes, allowEmpty = false) {
  const declared = request.headers.get("Content-Length");
  if (declared && (!/^\d+$/.test(declared) || Number(declared) > maxBytes)) {
    throw new HttpError(413, "body_too_large");
  }
  if (!request.body) {
    if (allowEmpty) return {};
    throw new HttpError(400, "invalid_body");
  }
  const hasJsonType = request.headers.get("Content-Type")?.split(";")[0].trim().toLowerCase() === "application/json";
  if (!allowEmpty && !hasJsonType) {
    throw new HttpError(415, "json_required");
  }
  const reader = request.body.getReader();
  const chunks = [];
  let length = 0;
  const deadline = Date.now() + 4000;
  try {
    while (true) {
      let timer;
      const part = await Promise.race([
        reader.read(),
        new Promise((_, reject) => {
          timer = setTimeout(() => reject(new HttpError(408, "body_timeout")), Math.max(1, deadline - Date.now()));
        }),
      ]).finally(() => clearTimeout(timer));
      if (part.done) break;
      length += part.value.byteLength;
      if (length > maxBytes) throw new HttpError(413, "body_too_large");
      chunks.push(part.value);
    }
  } catch (error) {
    await reader.cancel().catch(() => {});
    throw error;
  } finally {
    reader.releaseLock();
  }
  if (length === 0 && allowEmpty) return {};
  // workerd exposes a readable body even for an empty HTTP POST. Validate
  // content type only after determining whether its optional body is empty.
  if (!hasJsonType) throw new HttpError(415, "json_required");
  const bytes = new Uint8Array(length);
  let offset = 0;
  for (const chunk of chunks) { bytes.set(chunk, offset); offset += chunk.byteLength; }
  try {
    const value = JSON.parse(new TextDecoder("utf-8", { fatal: true }).decode(bytes));
    if (!value || typeof value !== "object" || Array.isArray(value)) throw new Error();
    return value;
  } catch {
    throw new HttpError(400, "invalid_body");
  }
}

function requireConfiguration(env) {
  if (!env.DB?.prepare || env.CHANNEL_ID !== CHANNEL_ID ||
      !/^[A-Za-z][A-Za-z0-9_]{4,31}$/.test(env.BOT_USERNAME ?? "") ||
      !env.BOT_USERNAME.toLowerCase().endsWith("bot") ||
      !/^\d{5,16}:[A-Za-z0-9_-]{20,}$/.test(env.TELEGRAM_BOT_TOKEN ?? "") ||
      !/^[A-Za-z0-9_-]{32,256}$/.test(env.TELEGRAM_WEBHOOK_SECRET ?? "")) {
    throw new HttpError(503, "service_not_configured");
  }
}

async function cleanup(db, now) {
  await db.prepare("DELETE FROM sessions WHERE expires_at <= ?1").bind(now).run();
  await db.prepare("DELETE FROM rate_limits WHERE expires_at <= ?1").bind(now).run();
}

async function rateLimit(request, env, now, action, maximum) {
  // CF-Connecting-IP is supplied by the edge, not a user-provided X-Forwarded-For.
  // There is no raw IP or long-lived identifier in the database or application logs.
  const ip = request.headers.get("CF-Connecting-IP") ?? "local-development";
  await rateLimitKey(env, now, action, ip, maximum);
}

async function rateLimitKey(env, now, action, source, maximum) {
  const minute = Math.floor(now / 60);
  const key = await crypto.subtle.importKey("raw", encoder.encode(env.TELEGRAM_WEBHOOK_SECRET),
    { name: "HMAC", hash: "SHA-256" }, false, ["sign"]);
  const digest = new Uint8Array(await crypto.subtle.sign("HMAC", key, encoder.encode(`${action}:${minute}:${source}`)));
  const bucket = [...digest].map((byte) => byte.toString(16).padStart(2, "0")).join("");
  const row = await env.DB.prepare(`INSERT INTO rate_limits(bucket_key, count, expires_at)
    VALUES (?1, 1, ?2) ON CONFLICT(bucket_key) DO UPDATE SET count = count + 1
    RETURNING count`).bind(bucket, (minute + 2) * 60).first();
  if (!row || row.count > maximum) throw new HttpError(429, "rate_limited", 60 - now % 60);
}

async function allowTelegramUser(env, now, userId) {
  try {
    // Messages and callbacks share one authenticated-account budget. Telegram's
    // identity comes only from the secret-authenticated webhook, never the APK.
    await rateLimitKey(env, now, "telegram-user", String(userId), 10);
    return true;
  } catch (error) {
    if (error instanceof HttpError && error.status === 429) return false;
    throw error;
  }
}

function payload(session, now) {
  const expired = session.expires_at <= now;
  const verified = session.status === "verified" && session.verified_until > now;
  return {
    status: expired ? "expired" : verified ? "verified" : session.status === "not_member" ? "not_member" : "pending",
    version: session.version,
    nonce: session.nonce,
    expiresAt: verified && !expired ? Math.min(session.expires_at, session.verified_until) : session.expires_at,
  };
}

async function authenticatedSession(request, env, id) {
  const authorization = request.headers.get("Authorization") ?? "";
  const match = /^Bearer ([A-Za-z0-9_-]{43})$/.exec(authorization);
  if (!match || !isOpaque(match[1])) throw new HttpError(401, "unauthorized");
  const session = await env.DB.prepare("SELECT * FROM sessions WHERE id = ?1").bind(id).first();
  if (!session || !await constantTimeEqual(await sha256(match[1]), session.token_hash)) {
    throw new HttpError(401, "unauthorized");
  }
  return session;
}

function realPrivateUser(message) {
  const id = message?.from?.id;
  return message?.chat?.type === "private" && message.chat.id === id &&
    Number.isSafeInteger(id) && id > 0 && message.from.is_bot === false;
}

export function createWorker({ fetchImpl = (...args) => fetch(...args), now = () => Math.floor(Date.now() / 1000) } = {}) {
  async function telegram(env, method, body) {
    // Retrying is safe only because this helper is restricted to the read-only
    // membership lookup. Message and callback replies use the webhook response.
    if (method !== "getChatMember") throw new HttpError(503, "telegram_unsafe_method");
    const endpoint = `https://api.telegram.org/bot${env.TELEGRAM_BOT_TOKEN}/${method}`;
    const requestBody = JSON.stringify(body);
    let lastError = new HttpError(503, "telegram_unavailable");
    for (let attempt = 0; attempt < TELEGRAM_ATTEMPTS; attempt++) {
      const controller = new AbortController();
      const timer = setTimeout(() => controller.abort(), TELEGRAM_ATTEMPT_TIMEOUT_MS);
      let retryable = true;
      try {
        const response = await fetchImpl(endpoint, {
          // workerd supports manual/follow only. Manual exposes redirects so
          // non-2xx handling rejects them without forwarding the bot token.
          method: "POST", redirect: "manual", signal: controller.signal,
          headers: { "Accept": "application/json", "Content-Type": "application/json" }, body: requestBody,
        });
        if (!response.ok) {
          if (response.status === 429) {
            let data;
            try { data = await readBoundedJson(response, 16384); } catch { data = null; }
            retryable = false;
            throw new HttpError(429, "rate_limited",
              telegramRetryAfter(response.headers.get("Retry-After"), data?.parameters?.retry_after));
          }
          if (response.body) await response.body.cancel().catch(() => {});
          const safeStatus = response.status >= 400 && response.status <= 599 ? response.status : 0;
          retryable = safeStatus >= 500;
          throw new HttpError(503, `telegram_http_${safeStatus}`);
        }
        let data;
        try { data = await readBoundedJson(response, 16384); }
        catch { throw new HttpError(503, "telegram_invalid_response"); }
        if (data.ok !== true) {
          const apiStatus = Number.isInteger(data.error_code) && data.error_code >= 400 && data.error_code <= 599 ?
            data.error_code : 0;
          if (apiStatus === 429) {
            retryable = false;
            throw new HttpError(429, "rate_limited", telegramRetryAfter(null, data.parameters?.retry_after));
          }
          retryable = apiStatus >= 500;
          throw new HttpError(503, `telegram_api_${apiStatus}`);
        }
        return data.result;
      } catch (error) {
        // Never propagate Telegram's request URL (which includes the bot token).
        lastError = error instanceof HttpError && (error.code.startsWith("telegram_") || error.status === 429) ?
          error : new HttpError(503, "telegram_network_error");
        if (!retryable || attempt + 1 === TELEGRAM_ATTEMPTS) throw lastError;
      } finally {
        clearTimeout(timer);
      }
      await new Promise((resolve) => setTimeout(resolve, TELEGRAM_RETRY_DELAY_MS));
    }
    throw lastError;
  }

  async function checkMembership(env, session, userId) {
    const checkedAt = now();
    const claimed = await env.DB.prepare(`UPDATE sessions SET checking_until = ?1
      WHERE id = ?2 AND telegram_user_id = ?3 AND expires_at > ?4 AND checking_until <= ?4
      RETURNING *`).bind(checkedAt + 10, session.id, userId, checkedAt).first();
    if (!claimed) throw new HttpError(429, "check_in_progress", 2);
    try {
      const member = await telegram(env, "getChatMember", { chat_id: CHANNEL_ID, user_id: Number(userId) });
      if (!member || typeof member.status !== "string" || member.user?.id !== Number(userId)) throw new Error();
      const isMember = ["member", "administrator", "creator"].includes(member.status) ||
        (member.status === "restricted" && member.is_member === true);
      const until = isMember ? Math.min(claimed.expires_at, now() + SESSION_TTL) : null;
      const updated = await env.DB.prepare(`UPDATE sessions
        SET status = ?1, verified_until = ?2, checking_until = 0
        WHERE id = ?3 AND telegram_user_id = ?4 AND checking_until = ?5 RETURNING *`)
        .bind(isMember ? "verified" : "not_member", until, claimed.id, userId, checkedAt + 10).first();
      if (!updated) throw new Error();
      return updated;
    } catch (error) {
      await env.DB.prepare(`UPDATE sessions SET status = 'pending', verified_until = NULL, checking_until = 0
        WHERE id = ?1 AND telegram_user_id = ?2 AND checking_until = ?3`)
        .bind(claimed.id, userId, checkedAt + 10).run();
      // Keep Telegram transport/API details out of the public client protocol.
      throw error instanceof HttpError && error.status === 429 ?
        error : new HttpError(503, "telegram_unavailable");
    }
  }

  function sendResult(userId, status) {
    const text = status === "verified" ? "Подписка подтверждена. Вернитесь в Zapret." :
      status === "not_member" ? "Подпишитесь на @Slag0dworld и нажмите «Проверить»." :
      "Готово. Вернитесь в Zapret и нажмите «Проверить».";
    return telegramWebhookReply("sendMessage", {
      chat_id: Number(userId), text,
      reply_markup: { inline_keyboard: [
        [{ text: "Открыть канал", url: "https://t.me/Slag0dworld" }],
      ] },
    });
  }

  async function webhook(request, env) {
    const supplied = request.headers.get("X-Telegram-Bot-Api-Secret-Token") ?? "";
    if (supplied.length > 256 || !await constantTimeEqual(supplied, env.TELEGRAM_WEBHOOK_SECRET)) {
      throw new HttpError(401, "unauthorized");
    }
    const update = await readBoundedJson(request, 16384);
    if (!Number.isSafeInteger(update.update_id) || update.update_id < 0) throw new HttpError(400, "invalid_update");
    const message = update.message;
    if (realPrivateUser(message)) {
      const start = /^\/start(?:@([A-Za-z0-9_]{5,32}))? ([A-Za-z0-9_-]{43})$/.exec(message.text ?? "");
      if (start && isOpaque(start[2]) && (!start[1] || start[1].toLowerCase() === env.BOT_USERNAME.toLowerCase())) {
        if (!await allowTelegramUser(env, now(), message.from.id)) return json({ ok: true });
        const userId = String(message.from.id);
        // Only the first authenticated sender can bind a session, even across isolates.
        const bound = await env.DB.prepare(`UPDATE sessions SET telegram_user_id = ?1
          WHERE id = ?2 AND expires_at > ?3 AND telegram_user_id IS NULL RETURNING *`)
          .bind(userId, start[2], now()).first();
        const session = bound ?? await env.DB.prepare("SELECT * FROM sessions WHERE id = ?1").bind(start[2]).first();
        if (!session || session.expires_at <= now() || session.telegram_user_id !== userId) {
          return telegramWebhookReply("sendMessage", {
            chat_id: message.from.id, text: "Ссылка устарела. Откройте обновление в Zapret заново.",
          });
        }
        // The webhook only binds Telegram identity. Membership is checked by the
        // authenticated POST /authorize request when the user returns to Zapret.
        return sendResult(userId, "pending");
      } else if (message.text === "/start" || message.text === "/privacy") {
        if (!await allowTelegramUser(env, now(), message.from.id)) return json({ ok: true });
        return telegramWebhookReply("sendMessage", {
          chat_id: message.from.id,
          text: message.text === "/privacy" ?
            "Для обновления проверяем только подписку на @Slag0dworld. Telegram ID и сессия хранятся до 10 минут плюс время очистки. Переписку и трафик Zapret бот не получает." :
            "Откройте «Обновления» в Zapret и нажмите «Проверить подписку».",
        });
      }
      return json({ ok: true });
    }
    const callback = update.callback_query;
    if (callback && typeof callback.id === "string" && callback.id.length <= 256) {
      if (!realPrivateUser({ from: callback.from, chat: callback.message?.chat })) return json({ ok: true });
      // Acknowledge throttled updates with 200 to prevent Telegram redelivery.
      // Do not answerCallbackQuery: that would itself consume the abused quota.
      if (!await allowTelegramUser(env, now(), callback.from.id)) return json({ ok: true });
      return telegramWebhookReply("answerCallbackQuery", {
        callback_query_id: callback.id, text: "Вернитесь в Zapret и нажмите «Проверить».",
      });
    }
    return json({ ok: true });
  }

  return {
    async fetch(request, env) {
      try {
        const url = new URL(request.url);
        if (url.search || url.hash) throw new HttpError(400, "query_not_allowed");
        if (url.pathname === "/health" && request.method === "GET") return json({ status: "ok" });
        requireConfiguration(env);
        if (url.pathname === "/telegram/webhook") {
          if (request.method !== "POST") throw new HttpError(405, "method_not_allowed");
          return await webhook(request, env);
        }
        if (request.headers.has("Origin")) throw new HttpError(403, "browser_request_not_allowed");
        if (url.pathname === "/v1/sessions") {
          if (request.method !== "POST") throw new HttpError(405, "method_not_allowed");
          const body = await readBoundedJson(request, 1024);
          if (Object.keys(body).sort().join(",") !== "nonce,version" ||
              typeof body.version !== "string" || body.version.trim() !== body.version ||
              !STABLE_VERSION.test(body.version) || !isOpaque(body.nonce)) {
            throw new HttpError(400, "invalid_session");
          }
          const createdAt = now();
          await rateLimit(request, env, createdAt, "create", 5);
          await cleanup(env.DB, createdAt);
          const id = opaqueValue();
          const token = opaqueValue();
          const result = await env.DB.prepare(`INSERT INTO sessions(id, token_hash, version, nonce, created_at, expires_at)
            SELECT ?1, ?2, ?3, ?4, ?5, ?6 WHERE (SELECT COUNT(*) FROM sessions) < ?7`)
            .bind(id, await sha256(token), body.version, body.nonce, createdAt, createdAt + SESSION_TTL, MAX_ACTIVE_SESSIONS).run();
          if (result.meta?.changes !== 1) throw new HttpError(503, "session_capacity_reached");
          return json({ id, token, botUrl: `https://t.me/${env.BOT_USERNAME}?start=${id}`, expiresAt: createdAt + SESSION_TTL }, 201);
        }
        const match = /^\/v1\/sessions\/([A-Za-z0-9_-]{43})(\/authorize)?$/.exec(url.pathname);
        if (!match || !isOpaque(match[1])) throw new HttpError(404, "not_found");
        const authorize = Boolean(match[2]);
        if (request.method !== (authorize ? "POST" : "GET")) throw new HttpError(405, "method_not_allowed");
        const session = await authenticatedSession(request, env, match[1]);
        await rateLimit(request, env, now(), authorize ? "authorize" : "poll", authorize ? 6 : 60);
        if (!authorize || session.expires_at <= now()) return json(payload(session, now()));
        const body = await readBoundedJson(request, 128, true);
        if (Object.keys(body).length) throw new HttpError(400, "unexpected_fields");
        if (!session.telegram_user_id) return json(payload(session, now()));
        // GET is only a UI hint. Each download must POST here for a fresh Telegram answer.
        return json(payload(await checkMembership(env, session, session.telegram_user_id), now()));
      } catch (error) {
        const known = error instanceof HttpError;
        return json({ error: known ? error.code : "service_unavailable" }, known ? error.status : 503,
          known && error.retryAfter ? { "Retry-After": String(error.retryAfter) } : {});
      }
    },
    async scheduled(_event, env) {
      requireConfiguration(env);
      await cleanup(env.DB, now());
    },
  };
}

export default createWorker();
