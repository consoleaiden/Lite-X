# Lite X — 基于 Telegram X 的轻量中文定制版

![Lite X](/images/feature.png)

**Lite X** 是 [TGX-Android/Telegram-X](https://github.com/TGX-Android/Telegram-X)（TDLib + MTProto）的第三方定制分支，目标是：**中文优先、去广告、去打扰**。所有定制都在客户端本地生效，不改协议、不采集数据。

- 仓库：<https://github.com/consoleaiden/Lite-X>（包名 `org.example.tgx`，主 Activity `org.thunderdog.challegram.MainActivity`）
- 安装包：[Releases/latest](https://github.com/consoleaiden/Lite-X/releases/latest)（每次构建自动发布，含 `SHA256.txt`）
- 许可：GPLv3（跟随上游）

---

## 📌 当前状态

| 项 | 值 |
| --- | --- |
| 应用名 | **Lite X**（仓库 Variables `APP_NAME`） |
| 版本 | `versionCode = version.app × 1000`（Debug 时 +0），当前 `version.app=1829` → **1829000** |
| flavor / ABI | `marshmallow`（minSdk 23）+ `arm64-v8a` / `armeabi-v7a` |
| 构建方式 | GitHub Actions `.github/workflows/build-apk.yml`（`workflow_dispatch`），约 22 分钟 |
| 应用内更新 | 读本仓库 `/releases/latest` |
| 上游基线 | `8b7c7fc`（此后 14 个定制提交） |

---

## ✨ 已完成的定制（下一轮对话的起点）

### 1. 内置简体中文（`859cac7`）
- `res/values/strings.xml` 整体替换为官方翻译平台 `zh-hans/android_x` 导出（5315 条），`language_code` 强制 `zh-hans`。
- 移除语言切换入口：`ui/SettingsController`（`btn_languageSettings`）、`ui/PhoneController:138`、`ui/MainController.showLanguageSuggestion()`（返回 false）。
- **品牌残留**：云端语言包仍带 "Telegram X"，在 `core/Lang.java` 的 `getStringImpl` 两个云包返回点统一走 `applyBranding()` 替换为 `BuildConfig.PROJECT_NAME`。

### 2. 内容过滤（`cf52e85`）
- 核心：`unsorted/ContentFilter.java`（规则存储/匹配/黑名单/`version` 计数）；设置页 `ui/SettingsContentFilterController.java`。
- 规则类型：关键词 / 正则表达式；模式：完全过滤 / 折叠提示（点击展开）。
- 拦截点：`component/chat/MessagesLoader`、`component/chat/MessagesManager`（列表与实时消息）、`data/TGMessageText`（正文）、`data/TGMessageMedia`（caption）、`data/ContentPreview`（聊天列表摘要）、`data/TGMessage`（折叠状态 + `onMessageClick` 展开）。
- 实时生效：`ui/MessagesController`、`ui/ChatsController` 在 `onActivityResume`/`onFocusStateChanged` 比对 `ContentFilter.version()`。
- 规则**可编辑**：点击规则行 → 编辑规则 / 删除规则（`ContentFilter.updateRule(index, pattern)`）。

### 3. 屏蔽广告（`cf00da3`、`936c297`）
- 聊天列表：`ui/ChatsController.list()` 的 slice Filter 拒绝 `ChatSourceMtprotoProxy`。
- 聊天窗口：`MessagesLoader.canShowSponsoredMessage()` 增加 `!ContentFilter.blockAds()`（不请求即不渲染）。
- 链接黑名单：`ContentFilter.blockAdUrl/isAdUrlBlocked`（host 级宽松匹配），渲染时 `stripBlockedAds()` 会**删掉链接文本并重排 entity**；纯链接消息显示占位「广告链接已屏蔽」。
- 长按弹窗「屏蔽广告」已覆盖**全部**入口：
  `util/text/TextEntityMessage.performLongPress`（消息内文字链接 / `t.me` / @用户名）、`TdlibUi.showUrlOptions`、`TdlibUi.openUrlOptions`、`ViewController.showCopyUrlOptions`（`data/TGWebPage` 链接预览、`data/TGInlineKeyboard` 内联按钮）、`data/PageBlockMedia`（Instant View）。
- 设置页入口：设置 → 内容过滤 → 屏蔽系统广告（默认开）。

### 4. 界面与交互
- 代理测速（`TdlibUi.openProxyAlert` → `btn_proxyTest`）：`AddProxy(enable=false)` → `PingProxy` → Toast RTT，测完 `RemoveProxy` 清理。图标 `drawable/baseline_signal_speed_24.xml`。
- 聊天文件夹标签：`TdlibSettingsManager.DEFAULT_CHAT_FOLDER_STYLE = ChatFolderStyle.LABEL_AND_ICON`（图标 + 名称）。
- 抽屉精简：`navigation/DrawerController` 删除 `btn_invite`（邀请朋友）、`btn_calls`（通话）、`btn_help`（帮助）。
- 设置页精简：删除「向我们提问」「Telegram 常见问题」「隐私政策」。

### 5. 品牌：名称 + 图标（`8abfedc`）
- 名称：`local.properties` 的 `app.name` → `resValue AppName` + `BuildConfig.PROJECT_NAME`；CI 取自仓库 Variables `APP_NAME`（当前 `Lite X`）。
- 图标源：`_tools/icon_lite_x.svg`（仓库外），生成脚本 `_tools/gen_icons.js`：
  ```bash
  NODE_PATH=<node workspace>/node_modules node _tools/gen_icons.js <repo root>
  ```
  输出到 `app/src/main/res/mipmap-*/`：`app_launcher`（圆占画布 86%）、`app_launcher_round`（92%）、`app_notification`（只画白色心形，Android 只取 alpha）、`logo_middle`。

### 6. GitHub Releases 应用内更新（`8abfedc`）
- `util/AppUpdater.java` 新增 `FlowType.GITHUB_RELEASE`；接口由 `BuildConfig.SOURCES_URL`（=`app.sources_url`）推导为 `https://api.github.com/repos/<owner>/<repo>/releases/latest`。
- 版本判定：Release tag = `v<versionCode>-<CI构建号>`；同 versionCode 时比 `BuildConfig.CI_BUILD_NUMBER`（CI 传 `-PciBuildNumber=$GITHUB_RUN_NUMBER`）。
- 资源挑选按 `Build.SUPPORTED_ABIS`（arm64-v8a 优先）；「下载更新」= 浏览器打开 asset。
- CI 新增 `release` job：汇总产物 → SHA256 → `softprops/action-gh-release@v2` 发布。

### 7. CI 构建流水线（`cb72ef7` 起）
子模块+LFS → JDK 25 → 按 `version.properties` 装 SDK/NDK → 运行时生成 `local.properties` 与 keystore → flavor 与 CMake 白名单预检 → 构建 → `aapt2` 校验 → 上传 artifact → 发布 Release。
详见 [`docs/CI-BUILD.md`](docs/CI-BUILD.md)，逐项代码位置见 [`docs/CUSTOMIZATION.md`](docs/CUSTOMIZATION.md)。

### 8. WiFi 抽屉图标 + 网络速率
- `navigation/BackHeaderButton`：`factor=0`（主界面）不再画三条横线 + 角标，改为 WiFi 字形（三条圆弧 + 底部圆点）；`factor>0` 仍保留原本的汉堡↔返回箭头形变动画。
- 速率采样：新增 `tool/NetworkSpeedMonitor`（`TrafficStats` 按 UID 统计收发字节，1.2s 一次采样，快升慢降 EMA），映射 4 档（阈值 24KB/s、256KB/s、2MB/s）→ 圆弧实心数量：0 档全空心，3 档全实心。
- 底部圆点：有未读时取 `MainActivity.updateCounter()` 下发的 `ColorId.headerBadge`（橙色）着色；无未读时跟随图标主题色。原右上角小角标已移除。

### 9. 禁止转发（noforwards）突破
- 结论：服务端对 `messages.forwardMessages`（含 `asCopy`）**以及复用受保护聊天远端 fileId 的 `messages.sendMedia`** 都会返回 `CHAT_FORWARDS_RESTRICTED`；唯一可靠方案是「下载 → 全新上传」。
- 新增 `telegram/ContentCopySender`：顺序处理消息——主媒体未在本地时异步 `DownloadFile(..., synchronous=false)` 后轮询 `GetFile` 直到完成（**不能用 `synchronous=true`**，它会占住 TDLib 整个请求队列直到传输结束，大视频直接把界面卡死），然后以 `InputFileLocal` 构建内容发送；相册按 `mediaAlbumId` 聚合成 `SendMessageAlbum`；任一步失败即中止并回调错误。
- `data/TD.forwardMessages(tdlib, ...)`：源聊天 `hasProtectedContent` 的分组直接交给 `ContentCopySender`（异步），其余仍走 `ForwardMessages`。
- `TD.toInputMessageContent(content, removeCaption, localMediaPath)`：文本/图片（含 MessagePhoto 内嵌视频）/视频/文件/动图/音频/贴纸/语音/圆形视频/位置/地点/联系人/骰子 → `InputMessage*`；投票、付费媒体等未支持类型跳过。
- 入口放行：`TGMessage.canBeForwarded()` 对 `hasProtectedContent` 聊天返回 true；`MessagesController.canShareSelectedMessages()` 改用 `TGMessage.canBeForwarded()`。
- 折损：受限来源内容以「新消息」发布（无「转发自」署名、不计原浏览数）；大体积媒体需先完整下载。
- **踩过的坑（1825 修）**：只做「下载 → `InputFileLocal(下载路径)`」仍然会被 400。原因在客户端：**TDLib 的文件数据库按 path 建有 `path → file_id` 映射**，把 TDLib 自己从受保护聊天下载的那份文件原样递回去，TDLib 直接复用了原来的远端 file_id，服务端视角与 `InputFileId` 完全等价。修法：发送前把文件**落到一个 TDLib 从未见过的路径**（`cacheDir/reupload/upload_<nano>_<n><ext>`，同盘优先硬链接零拷贝、否则流复制），强制真正上传；会话结束后延时 60s 清理。
- **第二道保险（1825 加）**：`chat.hasProtectedContent` 这个缓存标志并不可靠（可能陈旧、或该聊天从未被 `getChat` 过），所以 `ShareController` 发送处拦截 `ForwardMessages` 的返回：命中 `CHAT_FORWARDS_RESTRICTED`（`TD.isForwardsRestricted`）就**自动降级**为 `ContentCopySender` 重发，服务端不会有重复消息（被拒的转发根本没落库）。降级失败的错误串会带 `[copy]` 后缀，便于区分是「转发被拒」还是「下载重传失败」。

### 10. 频道克隆
- 入口：`ui/ProfileController.showCommonMore()` 在「添加成员」后插入「克隆频道」（仅频道显示）。
- 目标选择：新增 `ui/CloneChannelController`，先 `ChatListMain` 再 `ChatListArchive` 累加去重（缺失的 Chat 用 `GetChat` 补拉），过滤 `ChatTypeSupergroup.isChannel` + **自己**可发帖（见下条）；首行为「新建频道」。
- 新建频道：`ui/CreateChannelController.setOnChannelCreatedListener()` 钩子，创建完成后回到克隆页继续选范围（不再进入「添加成员」流程）。
- 目标判定：`Chat.permissions` 是「非管理员成员」的权限（广播频道恒为不可发帖），**不能**用来判定自己；改用 `tdlib.chatStatus(chatId)`——Creator 或 Administrator+canPostMessages 才算可发布（克隆引擎侧对刚创建、状态未缓存的频道保持乐观）。
- 执行引擎：`telegram/ChannelCloner` 先复制封面（`SetChatPhoto` + 远端 `InputFileId`）与简介（`GetSupergroupFullInfo` → `SetChatDescription`）；消息两遍处理——`GetChatHistory` 分页收集 message id（100/页），再从最旧页开始 `GetMessages` → 交给 `ContentCopySender` 按时间顺序「下载+重发」（天然支持 noforwards 来源）。范围：最近 100 / 1,000 / 全部。
- 分页去重：`getChatHistory` 的 `offset=0` 会**包含** `fromMessageId` 自身，导致每页边界消息被收集两次（克隆结果重复）。改为只接受 `id < 已收集最小 id` 的消息，`count == 0` 即视为到底。

### 11. 链接直存 + 机器人中转 + Cloudflare 中转（`cloudflare/`）
先给结论：`Save-Restricted-Content-Bot-v3` / `save-restricted-content-bot` / `Auto-Forward-Bot-V2` 绕过禁止转发的原理与第 9 节**完全一致**——用 api_id/api_hash 登录的 **user session** 读取受限消息 → 下载 → 以全新消息重新上传（bot 只做交互层）。所以没有移植它们的代码，只把其中两处「确有增量」的能力做进了 App：

- 链接直存：新增 `telegram/RestrictedContentSaver`。`GetMessageLinkInfo` 解析 `t.me` 链接 → 直接复用 `ContentCopySender`；入口在 设置 → 受限内容与机器人 → 粘贴链接保存内容，目标可选收藏夹或任一可发帖聊天。
- 三级降级链：`Cloudflare 中转` → `Bot API copyMessage` → `本机下载+重传`。前两级由 Telegram 服务端搬运（不耗手机流量、快 10~50 倍），失败（尤其受保护内容）自动落到第三级，所以禁止转发仍然可用。
- 新增 `telegram/BotApiClient`（`HttpURLConnection` + JSON，无新依赖）：`getMe` / `getChat` / `copyMessage` / `sendMessage`。
- 新增 `telegram/RemoteRelayClient`：调用自建 Cloudflare Worker 的 `/api/copy`，批量提交一页消息；`api.telegram.org` 不可达时也能用。
- 新增 `ui/SettingsBotRelayController`（设置 → 受限内容与机器人）：`BOT_TOKEN`、`API_ID`、`API_HASH`、中转地址/口令、开关与「测试连接」；存储走新增的 `unsorted/BotRelayConfig`（`Settings` 键值，token 只展示 `123456:••••`）。
- `ChannelCloner` 增加可选快路径：开启机器人中转时先逐条 `copyMessage`，被拒的消息收集起来再走 `ContentCopySender`。
- `cloudflare/`：`worker.js`（`/api/copy`、`/api/clone`、`/api/health`、`/webhook`）+ `wrangler.toml` + 部署说明。Worker 只有 Bot API 能力，**跑不了 user session**，因此禁止转发的核心突破仍在客户端完成。
- 已部署实例：`https://lite-x-relay.84339075.workers.dev`（密钥 `BOT_TOKEN`、`RELAY_SECRET` 已配）。App 的「服务地址」默认预填该地址；**口令不进仓库**（仓库与 Release 的 APK 都公开，硬编码等于把 bot 配额送给所有人），需在 App 里粘一次。

### 12. 视频画面横向滑动快进/快退（1826 → 1827 → 1828 → 1829 修复）
- **判据方向（1829 修正，1826–1828 全部无效的最终根因）**：`commonFactor` 的 `1f` 表示查看器**完全打开**（源码 `isFullyShown()` 即 `commonFactor == 1f`），`0f` 才是关闭。
  - 前几版把 HUD 可见性写成 `commonFactor < 1f`、把 `canSeek()` 写成 `commonFactor >= 1f` 时返回 false，等于只在 reveal 动画进行中的那零点几秒是激活的 → 正常观看时浮层永远 `GONE`、手势永远不启动，用户看到的就是"完全没变化"。
  - 正确写法：`updateSeekOverlayVisibility()` 要求 `commonFactor == 1f`；`canSeek()` 在 `commonFactor != 1f` 时返回 false。
  - 教训：把动画值当开关前，先找同类现成用法（如 `isFullyShown()`）确认两个端点各自的含义，不要靠"非零 / 为零"猜。
- `mediaview/VideoSeekGestureController`：只做**状态与绘制**，不处理 `MotionEvent`。`IDLE → SEEKING`，对外暴露 `canStartSeek / beginSeek / updateSeekTo / commitSeek / cancelSeek`。
- **挂载点**：`MediaViewController` 构建 `contentView` 的最后（`switch (mode)` 之后、`updateVideoState()` 之前）调 `attachSeekOverlayView(context)`，**对所有 mode 生效**。
  - 1826/1827 把它写在 `createViewGalleryMode()` 里，只有 `MODE_GALLERY` 会走；从聊天/频道点开视频是 `MODE_MESSAGES`（值 **0**），该分支从不调用 `createViewGalleryMode()`，覆盖层根本不存在 —— 这是"改动完全没生效"的真正根因，务必先确认 `mode` 再谈其它。
- **触摸仲裁在 `contentView.dispatchTouchEvent()`**，不在覆盖层上。
  - 视图级方案不可行：`ACTION_DOWN` 返回 false → 该 View 被移出本次手势，后续 `ACTION_MOVE` 永远收不到；返回 true → 吞掉点击与纵向手势。
  - 也不能用 `onInterceptTouchEvent`：pager 一旦开始跟踪就调 `requestDisallowInterceptTouchEvent(true)`，`FrameLayout` 会跳过对 `onInterceptTouchEvent` 的调用。
  - `ACTION_DOWN` **永不消费**，只记录起点；`ACTION_MOVE` 超过 `max(touchSlop, 12dp)` 且 `|dx| > |dy| * 1.15` 才夺取手势，先给子 View 发合成 `ACTION_CANCEL`（pager 回弹、不切图），再进入 seek。
  - 纵向优先放手：`|dy| > slop && |dy| >= |dx| * 1.15` 立刻 `seekTracking = false`，音量/亮度不受影响。多点触控取消。
- 灵敏度：**一屏宽 ≈ 整个视频时长**（`SENSITIVITY_FACTOR = 1.0`），边界 `Math.max(0, Math.min(duration, target))`。
- 预览优先：拖动只调 `videoSliderView.updateSeek` + 重绘 HUD（`时间 / 总时长` + 进度细条 + `±mm:ss` 徽标），松手才 `mediaView.setSeekProgress(progress)` 落点一次。
- 时间读数：**常驻**显示在控制条上方居中，`onSeekProgress → onPlaybackProgress` 驱动，**只在整秒变化时重绘**；`resetPlaybackProgress()` 在切到图片时清空，避免残留上一段视频的时间。
- 时长兜底：`getDurationMs()` 先读 `MediaCellView.getTimeTotal()`，为 -1 时回落 `item.getVideoDuration(true, MILLISECONDS)`，否则开播瞬间 `beginSeek` 拿不到长度会直接放弃拖拽。
- 与进度条共存：`isOnBottomControls(y)` 用 `contentView` 高度 − `bottomWrap` 的 `bottomMargin` − 56dp 判定；控制条 `getInnerAlpha() <= 0.05f`（已隐藏）时不避让，整屏可 seek。覆盖层自身高度同样扣掉这段（`updateSeekOverlayLayout()`）。
- 单击画面：**不拦截**，走原有 `MediaCellView.onClick` → `playerView.playPause()`，与改动前完全一致。`MediaCellView.togglePlayPauseFromSurface()` 保留但已不再需要。
- 暂停恢复：若拖动期间播放器自己暂停（`onPlayPause(false)` 且 `isSeeking()`），记 `seekPausedPlayback`，松手跳转后恢复。

### 13. 右上角菜单倍速播放（1829）
- 菜单项：`MediaViewController.openMoreMenu()` 中，当前 item 为视频/GIF 且 `Config.VIDEO_PLAYER_AVAILABLE` 时追加 `R.id.btn_playbackSpeed`，标签为 `R.string.PlaybackSpeed`（非 1x 时附带当前倍速，如 `速度 (1.5x)`）。
- 选择器：`showPlaybackSpeedOptions()` → `showOptions`，列表 `1x(正常) / 1.25x / 1.5x / 1.75x / 2x / 2.5x / 3x`，当前值蓝色 + 对勾图标；回调必须 `return boolean`（否则编译报 `missing return value`）。
- 应用链路：`MediaViewController.playbackSpeed` → `MediaCellView.setPlaybackSpeed()`（存字段，`setHideStaticView` 挂载播放器时应用）→ `VideoPlayerView.setPlaybackSpeed()` → `ExoPlayer.setPlaybackParameters(PlaybackParameters)`；`1f` 用 `PlaybackParameters.DEFAULT`。
- 生效范围：整个查看器会话（滑动切到下一个视频由 `onPlayStarted` 重新推一次，避免 cell 复用丢失倍速）；关闭查看器后回到 1x。

---

## 🚀 怎么继续改（操作手册）

1. **改代码** → `git push`（远端 `origin=https://github.com/consoleaiden/Lite-X.git`）
2. **改版本号**（想让应用内能检查到更新时必做）：`version.properties` 的 `version.app += 1`（如 1820 → 1821，versionCode 随之 1820000 → 1821000）
3. **触发构建**：
   ```bash
   cd _tools && python trigger_run.py main marshmallow both Debug
   ```
   参数：`flavor`（`marshmallow`/`latest`/`lollipop`/`legacy`）、`abi`（`both`/`arm64`/`arm32`/`universal`）、`build_type`、`publish_release`
4. **等构建 + 自动装到手机**（手机需 USB 调试且已解锁）：
   ```bash
   cd _tools && python verify_and_install.py <run_id> 180
   ```
5. 本机没有 JDK/NDK，**不要尝试本地 gradle 构建**；一切以 CI 结果为准（约 22 分钟）。

### 本机工具（`_tools/`，不在 git 仓库里）

| 脚本 | 用途 |
| --- | --- |
| `trigger_run.py main <flavor> <abi> <type>` | 触发 GitHub Actions 构建 |
| `verify_and_install.py <run_id> [分钟]` | 等构建 → 下载 artifact → adb 安装 |
| `poll_run.py <run_id> <步骤...>` | 轮询指定步骤状态 |
| `fetch_logs.py <run_id>` | 拉失败日志（job logs 重定向会 401，必须用 `curl -L`，返回纯文本） |
| `gen_icons.js` | SVG → 各密度 launcher 图标 |
| `apply_zh.py` | 把官方 zh-hans 翻译烧进 `strings.xml` |
| `platform-tools/` | adb |

---

## ⚠️ 已知坑（改之前必读）

1. **flavor 只能选 4 个**：`app/jni/CMakeLists.txt` 白名单 `legacy|lollipop|marshmallow|latest`。Gradle 侧还有 `qrs`/`tens`，选了必在 `configureCMake` 失败（约 1 小时后）。手机 Android 12(API31) 用 **marshmallow**。
2. **TdApi 版本差异**：本项目 tdlib 子模块的 `ReplyMarkupInlineKeyboard` 只有 `(rows, forceReply)` 构造，**没有** `isPersistent`/`placeholder`。动 TdApi 前先对 `https://raw.githubusercontent.com/TGX-Android/tdlib/<commit>/src/main/java/org/drinkless/tdlib/TdApi.java` 比对。
3. **云端语言包优先级高于 res**：改品牌/文案要么用新 key 放 `res/values/local_strings.xml`，要么在 `Lang.applyBranding()` 里替换。新增字符串**不要**改 `strings.xml`（它是翻译平台导出），统一放 `local_strings.xml`，且中文里的英文双引号必须转义 `\"`。
4. **发布 tag 与触发条件的死循环**：发布 tag 形如 `v1819000-13`，所以工作流 `push.tags` 必须保持 `build-*`；若改回 `v*`，会出现「发布 → 触发构建 → 再发布」。
5. **仓库改名后**：`_tools/*.py` 里的 `OWNER, REPO` 要同步（旧名 POST 会 307 失败）。
6. **聊天列表广告过滤**在 slice 创建时生效，开关切换后需重新进入列表（或重启）才刷新。
7. **同一包名两个 ABI 不能共存**，arm32 会覆盖 arm64，验证时最后装 arm64。
8. **UI 自动化**：截图是 500px 缩略图，点击坐标按 `wm size`(1080) 换算 ×2.16；锁屏/后台时 `input swipe` 会报 `INJECT_EVENTS` 权限错误，需先唤醒解锁。
9. 子模块必须全量拉取 + `git lfs pull`，`ValidateGitSetupTask` 会校验。

---

## 🔨 本地构建（有 JDK/NDK 的环境才用）

1. `git clone --recursive https://github.com/consoleaiden/Lite-X lite-x`
2. 申请 [Telegram API 凭据](https://core.telegram.org/api/obtaining_api_id)
3. 根目录 `local.properties`：<pre>sdk.dir=YOUR_ANDROID_SDK_FOLDER
   app.name=Lite X
   app.id=org.example.tgx
   app.sources_url=https://github.com/consoleaiden/Lite-X
   telegram.api_id=YOUR_TELEGRAM_API_ID
   telegram.api_hash=YOUR_TELEGRAM_API_HASH</pre>
4. `./gradlew assembleMarshmallowArm64Debug`

---

<details>
<summary>上游 Telegram X 原始构建说明（仍适用于本仓库代码结构）</summary>

* Repository must be fetched via `git`
* **JDK** or **[Android Studio](https://developer.android.com/studio/)** (with compatible bundled JDK)
* At least **8GB** of RAM
* At least **7,32GB** of free disk space when cloning with `--shallow-submodules --depth=1`
* At least **2-5x** times more disk space for files generated during build process.

In Windows `.properties` files, use forward slashes in paths, e.g. `keystore.file=E:/Lite-X/release-tgx.jks` in `keystore.properties`. In `local.properties`, `keystore.file` points to the settings file, e.g. `keystore.file=keystore.properties`.

## Reproducing public builds

In order to verify that there is no additional source code injected inside official APKs, you must use one of the following versions of **Ubuntu**:

* **21.04**: for builds published before [26th May 2023](https://github.com/TGX-Android/Telegram-X/commit/e9a054a0f469a98a13f7e0d751539687fef8759b)
* **22.04.2 LTS**: for builds published before 27th September 2025
* **24.04 LTS**: for builds published before 1st October 2026
* **26.04 LTS**: for any newer releases

1. Create user called `vk` with the home directory located at `/home/vk`
2. Clone `tgx` repository to `/home/vk/tgx`
3. Check out the specific commit you want to verify
4. In rare cases of builds that include unmerged pull requests, you must follow actions performed by [Publisher's](https://github.com/TGX-Android/Publisher/blob/main/main.js) `fetchPr` and `squashPr` tasks
5. `cd` into `tgx` folder and install dependencies: `# apt install $(cat reproducible-builds/dependencies.txt)`
6. Follow up the build instruction from the previous section
7. Run `$ apkanalyzer apk compare --different-only <remote-apk> <reproduced-apk>`
8. If only signature files and metadata differ, build reproduction is successful.

## Verifying side-loaded APKs

Get the checksum (`SHA-256`, `SHA-1` or `MD5`) of a downloaded APK and look it up:

* `$ sha256sum <path-to-apk>` on **Ubuntu**
* `$ shasum -a 256 <path-to-apk>` on **macOS**
* `$ certutil -hashfile <path-to-apk> SHA256` on **Windows**

Then send it to [`@tgx_bot`](https://t.me/tgx_bot) or search [`@tgx_log`](https://t.me/tgx_log).

</details>

## License

上游 `Telegram X` 以 **GNU General Public License v3.0** 授权，本分支的定制改动同样以 GPLv3 发布。详见 [LICENSE](/LICENSE)，第三方组件见 [docs/THIRDPARTY.md](/docs/THIRDPARTY.md)。
