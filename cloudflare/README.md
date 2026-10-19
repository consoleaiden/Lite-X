# Lite-X 云端中转（Cloudflare）

这个目录提供一个可选的 Cloudflare Worker，用 **BOT_TOKEN** 在 Telegram 服务器侧搬运消息。
它是 App 内「下载 + 重新上传」方案的加速/兜底，不是替代品。

## 重要结论：为什么不能把 user session 放到 Cloudflare

你给的三个参考项目（`Save-Restricted-Content-Bot-v3`、`save-restricted-content-bot`、`Auto-Forward-Bot-V2`）绕过禁止转发的原理完全一致：

1. 用 **api_id / api_hash 登录的 user session**（Pyrogram / Telethon）读取受限频道消息；
2. 下载媒体到服务器；
3. 以**全新消息**重新上传到目标频道（所以没有「转发自」署名）。

bot 只承担交互层（`/login`、`/single`、`/batch`），真正干活的是 user session。
**这个流程和 Lite-X 现在在手机上做的事完全一样**（`ContentCopySender`：`DownloadFile` → `InputFileLocal` 重传），
因此没有必要把它们的代码搬进 App。

Cloudflare Workers 没有 MTProto 运行时（无裸 TCP、无法跑 Pyrogram），
所以 **禁止转发的核心突破只能发生在客户端**（本 App 已实现）或一台 VPS 上（跑上面那些项目）。

Cloudflare 能做的、也确有价值的事是：`copyMessage` —— Telegram 服务端直接复制，
不消耗手机流量，速度快 10~50 倍，并且在 `api.telegram.org` 被墙、只有 Cloudflare 域名可达时依然能用。

## 部署（Workers，推荐）

```bash
cd cloudflare
npx wrangler login
npx wrangler deploy                 # 生成 https://lite-x-relay.<你>.workers.dev
npx wrangler secret put BOT_TOKEN   # 粘贴 @BotFather 给的 token
npx wrangler secret put RELAY_SECRET # 可选，与 App 里的「中转口令」一致
```

## 部署（Pages Functions）

1. Cloudflare 控制台 → Workers & Pages → Create → Pages → 连接到仓库；
2. 构建设置留空（纯静态），把本目录作为根目录；
3. 新建 `functions/api/copy.js`：

```js
import worker from '../../worker.js';
export const onRequest = ({ request, env }) => worker.fetch(request, env, {});
```

4. 环境变量里加 `BOT_TOKEN`（加密），部署后地址形如 `https://lite-x-relay.pages.dev`。

## App 内配置

设置 → 受限内容与机器人：

| 字段 | 说明 |
| --- | --- |
| BOT_TOKEN | 机器人 token；机器人需为目标频道管理员（发布消息），并加入源频道 |
| 中转口令 | 与 `RELAY_SECRET` 一致，留空则服务端不校验 |
| 服务地址 | `https://lite-x-relay.<你>.workers.dev` |
| 克隆时经由云端服务 | 打开后克隆优先走云端 |

未填任何内容时，App 仍使用本机「下载 → 重传」方案，禁止转发的内容照常可以搬运。

## 接口

```http
POST /api/copy
{ "from": "-1001234567890", "to": "-1009876543210", "messages": [101, 102] }

→ { "ok": true, "copied": 2, "failed": [] }
```

```http
POST /api/clone
{ "from": "-100…", "to": "-100…", "limit": 100, "start": 500 }

→ { "ok": true, "copied": 98, "failed": [{ "id": 499, "description": "message not found" }] }
```

`start` 必填：Bot API 无法列举频道历史，但频道内 message id 是连续的，给出最新一条的 id 即可向前遍历。

```http
POST /api/health       → { "ok": true, "bot": "your_bot" }
POST /webhook          → Telegram webhook：/start /id /copy /clone
```

## 限制

- 单个请求最多 `40` 条（免费版子请求限制），`/api/clone` 最多 `200` 条；
- 受保护（禁止转发）的频道：机器人一样会被拒，返回 `failed` 项，App 自动改用本机下载 + 重传；
- 机器人必须是源频道的成员/管理员，否则 `copyMessage` 直接 400。
