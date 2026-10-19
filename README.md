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
| 版本 | `versionCode = version.app × 1000`（Debug 时 +0），当前 `version.app=1821` → **1821000** |
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
- 结论：服务端对 `messages.forwardMessages`（含 `asCopy`）强制 `CHAT_FORWARDS_RESTRICTED`，协议层无法绕过；采用「内容重发」。
- `data/TD.forwardMessages(tdlib, ...)` 新重载：按 chatId 分组，源聊天 `hasProtectedContent` 时走 `buildCopyFunctions()`。
- `TD.toInputMessageContent(MessageContent, removeCaption)`：文本/图片/视频/文件/动图/音频/贴纸/语音/圆形视频/位置/地点/联系人/骰子 → `InputMessage*`；媒体用 `InputFileId(远端文件 id)`，不重新上传；相册按 `mediaAlbumId` 聚合成 `SendMessageAlbum`。
- 入口放行：`TGMessage.canBeForwarded()` 对 `hasProtectedContent` 聊天返回 true；`MessagesController.canShareSelectedMessages()` 改用 `TGMessage.canBeForwarded()`。
- 折损：受限来源内容以「新消息」发布（无「转发自」署名、不计原浏览数）；投票、付费媒体等未支持类型会被跳过。

### 10. 频道克隆
- 入口：`ui/ProfileController.showCommonMore()` 在「添加成员」后插入「克隆频道」（仅频道显示）。
- 目标选择：新增 `ui/CloneChannelController`，`GetChats` 按 100/300/600 递增拉取，过滤 `ChatTypeSupergroup.isChannel` + `permissions.canSendBasicMessages`；首行为「新建频道」。
- 新建频道：`ui/CreateChannelController.setOnChannelCreatedListener()` 钩子，创建完成后回到克隆页继续选范围（不再进入「添加成员」流程）。
- 执行引擎：`telegram/ChannelCloner` 先复制封面（`SetChatPhoto` + 远端 `InputFileId`）与简介（`GetSupergroupFullInfo` → `SetChatDescription`）；消息两遍处理——`GetChatHistory` 分页收集 message id（100/页），再从最旧页开始 `GetMessages` → 转 `SendMessage(Album)` 按时间顺序重发（天然支持 noforwards 来源）。范围：最近 100 / 1,000 / 全部。

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
