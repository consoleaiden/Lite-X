/**
 * Lite-X relay — a tiny Cloudflare Worker that copies Telegram messages
 * server-side using a bot token.
 *
 * Why it exists
 * -------------
 * Telegram refuses to forward messages from chats that enable "content
 * protection". There is exactly one way around it: fetch the media and publish
 * it again as a brand new message. That operation needs a *user* session
 * (api_id / api_hash) and therefore cannot run on Cloudflare — Workers have no
 * raw TCP and no MTProto runtime.
 *
 * What a bot CAN do on Cloudflare is `copyMessage`, which Telegram executes
 * server-side: no download, no upload, no traffic on the phone. For every chat
 * that is not protected this is 10-50x faster than the local bypass, and it keeps
 * working when `api.telegram.org` is unreachable from the phone (only the
 * Cloudflare domain has to be reachable).
 *
 * The Lite-X app automatically falls back to the local download + re-upload path
 * whenever this relay answers with a per-message failure, so protected content
 * still works.
 *
 * Endpoints
 * ---------
 *   POST /api/copy     { "from": "-100…", "to": "-100…", "messages": [1,2,3] }
 *   POST /api/clone    { "from": "-100…", "to": "-100…", "limit": 100 }
 *   POST /api/health   {}
 *   POST /webhook      Telegram webhook (/start, /id, /copy, /clone)
 *
 * Environment variables (Workers → Settings → Variables)
 *   BOT_TOKEN     required, from @BotFather
 *   RELAY_SECRET  optional shared secret, must match the app setting
 *   WEBHOOK_PATH  optional, defaults to "webhook"
 */

const API = 'https://api.telegram.org';
const MAX_BATCH = 40;
const CLONE_LIMIT_MAX = 200;

export default {
  async fetch (request, env, ctx) {
    const url = new URL(request.url);

    if (request.method === 'GET' && url.pathname === '/') {
      return new Response(INDEX_HTML, { headers: { 'content-type': 'text/html; charset=utf-8' } });
    }
    if (request.method === 'OPTIONS') {
      return new Response(null, { headers: corsHeaders() });
    }
    if (request.method !== 'POST') {
      return json({ ok: false, error: 'method not allowed' }, 405);
    }

    const isWebhook = url.pathname === '/' + (env.WEBHOOK_PATH || 'webhook');
    if (isWebhook) {
      return handleWebhook(request, env);
    }

    if (!authorized(request, env)) {
      return json({ ok: false, error: 'unauthorized' }, 401);
    }
    if (!env.BOT_TOKEN) {
      return json({ ok: false, error: 'BOT_TOKEN is not configured' }, 500);
    }

    let body;
    try {
      body = await request.json();
    } catch {
      return json({ ok: false, error: 'invalid json' }, 400);
    }

    if (url.pathname === '/api/health') {
      const me = await tg(env, 'getMe', {});
      return json(me.ok
        ? { ok: true, bot: me.result.username }
        : { ok: false, error: me.description });
    }
    if (url.pathname === '/api/copy') {
      return json(await copyMessages(env, body.from, body.to, body.messages || []));
    }
    if (url.pathname === '/api/clone') {
      const limit = Math.min(Number(body.limit) || 100, CLONE_LIMIT_MAX);
      return json(await cloneHistory(env, body.from, body.to, limit, Number(body.start) || 0));
    }
    return json({ ok: false, error: 'not found' }, 404);
  }
};

function corsHeaders () {
  return {
    'access-control-allow-origin': '*',
    'access-control-allow-headers': 'content-type,x-relay-secret',
    'access-control-allow-methods': 'POST,OPTIONS'
  };
}

function json (data, status = 200) {
  return new Response(JSON.stringify(data), {
    status,
    headers: { 'content-type': 'application/json; charset=utf-8', ...corsHeaders() }
  });
}

function authorized (request, env) {
  const expected = env.RELAY_SECRET;
  if (!expected) {
    return true;
  }
  return request.headers.get('x-relay-secret') === expected;
}

async function tg (env, method, payload) {
  const response = await fetch(`${API}/bot${env.BOT_TOKEN}/${method}`, {
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify(payload)
  });
  try {
    return await response.json();
  } catch {
    return { ok: false, description: `HTTP ${response.status}` };
  }
}

async function copyMessages (env, from, to, messages) {
  if (!from || !to || !Array.isArray(messages) || messages.length === 0) {
    return { ok: false, error: 'from, to and messages[] are required' };
  }
  const ids = messages.slice(0, MAX_BATCH).map(Number).filter(Boolean);
  let copied = 0;
  const failed = [];
  for (const id of ids) {
    const result = await tg(env, 'copyMessage', {
      chat_id: String(to),
      from_chat_id: String(from),
      message_id: id,
      disable_notification: true
    });
    if (result.ok) {
      copied++;
    } else {
      failed.push({ id, description: result.description || 'unknown error' });
    }
  }
  return { ok: true, copied, failed };
}

/**
 * Copies up to `limit` messages starting from `start`.
 *
 * The Bot API cannot enumerate channel history, but message ids inside a channel
 * are sequential, so the caller only has to pass the id of the newest message
 * (visible in any t.me/name/<id> link). Ids that do not exist are skipped by
 * Telegram, which is why they are reported in `failed`.
 */
async function cloneHistory (env, from, to, limit, start) {
  if (!from || !to) {
    return { ok: false, error: 'from and to are required' };
  }
  if (!start) {
    return {
      ok: false,
      error: 'start (id of the newest message) is required: bots cannot list channel history'
    };
  }
  const ids = [];
  for (let id = start; id > 0 && ids.length < limit; id--) {
    ids.push(id);
  }
  ids.reverse();
  return copyMessages(env, from, to, ids);
}

async function handleWebhook (request, env) {
  let update;
  try {
    update = await request.json();
  } catch {
    return json({ ok: false }, 400);
  }
  const message = update.message || update.channel_post;
  if (!message || !message.text) {
    return json({ ok: true });
  }
  const chatId = message.chat.id;
  const parts = message.text.trim().split(/\s+/);
  const command = parts[0].replace(/@.*$/, '');

  if (command === '/start' || command === '/help') {
    await tg(env, 'sendMessage', {
      chat_id: chatId,
      text: [
        'Lite-X relay is running.',
        '',
        '/id — show this chat id',
        '/copy <from> <to> <messageId> — copy one message',
        '/clone <from> <to> <limit> <startId> — copy messages backwards from startId',
        '',
        'Protected (no-forward) chats cannot be handled by a bot:',
        'use the Lite-X app, which downloads and re-uploads the media.'
      ].join('\n')
    });
  } else if (command === '/id') {
    await tg(env, 'sendMessage', { chat_id: chatId, text: `chat id: ${chatId}` });
  } else if (command === '/copy' && parts.length >= 4) {
    const result = await copyMessages(env, parts[1], parts[2], [Number(parts[3])]);
    await tg(env, 'sendMessage', { chat_id: chatId, text: JSON.stringify(result) });
  } else if (command === '/clone' && parts.length >= 4) {
    const result = await cloneHistory(env, parts[1], parts[2], Number(parts[3]) || 50, Number(parts[4]) || 0);
    await tg(env, 'sendMessage', { chat_id: chatId, text: JSON.stringify(result).slice(0, 3500) });
  }
  return json({ ok: true });
}

const INDEX_HTML = `<!doctype html>
<html lang="zh-CN"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>Lite-X Relay</title>
<style>
 body{font:15px/1.7 -apple-system,Segoe UI,Roboto,sans-serif;max-width:640px;margin:48px auto;padding:0 20px;color:#111}
 code{background:#f3f4f6;padding:2px 6px;border-radius:4px}
 h1{font-size:22px} li{margin:6px 0}
</style></head><body>
<h1>Lite-X Relay</h1>
<p>This Worker copies Telegram messages server-side with a bot token.</p>
<ul>
  <li><code>POST /api/copy</code> — copy a list of message ids</li>
  <li><code>POST /api/clone</code> — copy the newest messages of a channel</li>
  <li><code>POST /api/health</code> — check the bot token</li>
</ul>
<p>Content protected chats cannot be handled here: the Lite-X app downloads and re-uploads those locally.</p>
</body></html>`;
