// Run through configure-telegram.ps1 after deployment. Secrets arrive only on stdin.
// Neither secrets nor Telegram request URLs are printed or passed on the command line.
const endpoint = process.argv[2];
const checkOnly = process.argv[3] === "--check-only";
const diagnoseOnly = process.argv[3] === "--diagnose";
let config;
try {
  const chunks = [];
  let length = 0;
  for await (const chunk of process.stdin) {
    length += chunk.length;
    if (length > 4096) throw new Error();
    chunks.push(chunk);
  }
  config = JSON.parse(Buffer.concat(chunks).toString("utf8"));
} catch {
  console.error("Не удалось безопасно прочитать локальную конфигурацию.");
  process.exit(1);
}
const { token = "", secret = "", botUsername = "" } = config;
let base;
try {
  base = new URL(endpoint);
  if (base.protocol !== "https:" || base.username || base.password || base.search || base.hash ||
      base.pathname !== "/" || base.port) throw new Error();
  if (!/^\d{5,16}:[A-Za-z0-9_-]{20,}$/.test(token) || !/^[A-Za-z0-9_-]{32,256}$/.test(secret) ||
      !/^[A-Za-z][A-Za-z0-9_]{4,31}$/.test(botUsername) || !botUsername.toLowerCase().endsWith("bot")) throw new Error();
} catch {
  console.error("Укажите корневой HTTPS-адрес Worker, правильное имя бота и два секрета.");
  process.exit(1);
}
async function telegram(method, body) {
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), 8000);
  try {
    const response = await fetch(`https://api.telegram.org/bot${token}/${method}`, {
      method: "POST", redirect: "error", signal: controller.signal,
      headers: { "Content-Type": "application/json" }, body: JSON.stringify(body),
    });
    if (!response.ok) throw new Error();
    const reader = response.body.getReader();
    const chunks = [];
    let length = 0;
    try {
      while (true) {
        const part = await reader.read();
        if (part.done) break;
        length += part.value.byteLength;
        if (length > 16384) { await reader.cancel(); throw new Error(); }
        chunks.push(part.value);
      }
    } finally {
      reader.releaseLock();
    }
    const result = JSON.parse(Buffer.concat(chunks).toString("utf8"));
    if (result.ok !== true) throw new Error();
    return result.result;
  } finally {
    clearTimeout(timer);
  }
}

function classifyWebhookError(message) {
  if (typeof message !== "string" || message.length === 0) return "none";
  const status = /\b([45]\d\d)\b/.exec(message)?.[1];
  if (status) return `http_${status}`;
  if (/ssl|tls|certificate/i.test(message)) return "tls";
  if (/timed?\s*out|timeout/i.test(message)) return "timeout";
  if (/host|dns|resolve/i.test(message)) return "dns";
  if (/connect|network/i.test(message)) return "connection";
  return "other";
}

function hasExpectedUpdates(updates) {
  return Array.isArray(updates) && updates.length === 2 &&
    ["message", "callback_query"].every((kind) => updates.includes(kind));
}

try {
  const me = await telegram("getMe", {});
  if (me?.is_bot !== true || !Number.isSafeInteger(me.id) || me.id <= 0 ||
      me.username?.toLowerCase() !== botUsername.toLowerCase()) {
    console.error("Токен принадлежит не тому боту, который указан в wrangler.toml.");
    process.exitCode = 1;
  } else {
    const member = await telegram("getChatMember", { chat_id: "@Slag0dworld", user_id: me.id });
    if (member?.user?.id !== me.id || !["administrator", "creator"].includes(member.status)) {
      console.error("Добавьте этого бота администратором канала @Slag0dworld и повторите настройку.");
      process.exitCode = 1;
    } else if (diagnoseOnly) {
      const expectedUrl = new URL("/telegram/webhook", base).href;
      const webhook = await telegram("getWebhookInfo", {});
      const pending = Number.isSafeInteger(webhook?.pending_update_count)
        ? Math.min(Math.max(webhook.pending_update_count, 0), 1_000_000) : 0;
      const currentTime = Math.floor(Date.now() / 1000);
      const errorDate = webhook?.last_error_date;
      console.log(JSON.stringify({
        urlMatches: webhook?.url === expectedUrl,
        allowedUpdatesMatch: hasExpectedUpdates(webhook?.allowed_updates),
        pendingUpdates: pending,
        lastErrorKind: classifyWebhookError(webhook?.last_error_message),
        hasRecentError: Number.isSafeInteger(errorDate) && errorDate > 0 &&
          errorDate <= currentTime && errorDate >= currentTime - 600,
      }));
    } else if (checkOnly) {
      console.log("Имя бота и административный статус подтверждены.");
    } else {
      const webhookUrl = new URL("/telegram/webhook", base).href;
      const installed = await telegram("setWebhook", { url: webhookUrl,
        secret_token: secret, allowed_updates: ["message", "callback_query"], max_connections: 5 });
      if (installed !== true) throw new Error();
      const webhook = await telegram("getWebhookInfo", {});
      if (webhook?.url !== webhookUrl || webhook.has_custom_certificate === true ||
          !hasExpectedUpdates(webhook.allowed_updates)) {
        throw new Error();
      }
      console.log("Webhook подключён. Теперь проверьте подписку через приложение.");
    }
  }
} catch {
  console.error("Telegram не подтвердил настройку. Проверьте токен, права бота и доступность Telegram.");
  process.exitCode = 1;
}
