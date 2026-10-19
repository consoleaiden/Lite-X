# 本 fork 的定制改动

基于上游 `TGX-Android/Telegram-X`，做了三类改动：**界面中文化**、**内容过滤**、**抽屉菜单精简**。

## 1. 界面固定为简体中文

Telegram X 的界面文案**不在** `res/` 里，而是运行时从 TDLib 语言包（`localization_target = android_x`）取的，
`res/values/strings.xml` 只是兜底。所以做了两件事：

1. **构建期**：把 `app/src/main/res/values/strings.xml` 的全部 5315 条字符串替换为官方译文
   （来源 `https://translations.telegram.org/zh-hans/android_x/export`），并把
   `language_code` 强制为 `zh-hans`、`language_name` 为「简体中文」、`language_dateFormatLocale` 为 `zh-CN`。
   脚本见 `_tools/apply_zh.py`（工作区根，不在仓库里）。这样即便断网、语言包没拉到，界面也是中文。
2. **运行时**：内置语言包 id 变成 `zh-hans`，TDLib 会同步该语言包，整个 UI 显示中文。

### 已移除的语言切换入口

| 位置 | 说明 |
| --- | --- |
| `ui/SettingsController.java` | 设置页的「语言」项 |
| `ui/PhoneController.java` | 登录页右上角的语言按钮 |
| `ui/MainController.java` | `showLanguageSuggestion()` 直接返回 false，不再弹「切换为…语言」 |

> `SettingsLanguageController` 类仍在代码里（无入口可达）。想彻底减体积可以再删它及其残留引用。

## 2. 内容过滤

入口：**设置 → 内容过滤**。

- **规则**：关键词（大小写不敏感的包含匹配）或正则表达式（`Pattern.find()`，大小写不敏感）。
  匹配对象是消息正文和媒体说明文字（caption）。
- **两种模式**
  - 完全过滤：命中的消息在聊天窗口里**完全不渲染**。
  - 折叠提示：命中的消息显示成一行「内容已被过滤，点击查看」，**点击该消息即可展开原文**。
- **聊天列表**：最近一条消息命中时，摘要显示为「消息已被过滤」（`ContentPreview`）。
- **实时生效**：开关、模式、规则任何变更都会让聊天窗口重建消息、聊天列表重建摘要，
  关闭开关后原来的内容立刻恢复显示。
- **规则保存**：存在 `Settings`（LevelDB）里，重启后保留。新增正则若语法错误，会提示「正则表达式无效」且不参与匹配。

### 涉及的文件

| 文件 | 作用 |
| --- | --- |
| `unsorted/ContentFilter.java` | 新增。规则存取、匹配、`version()` 变更计数 |
| `ui/SettingsContentFilterController.java` | 新增。开关、模式、规则增删界面 |
| `component/chat/MessagesLoader.java` | 历史消息：命中且为「完全过滤」时跳过 |
| `component/chat/MessagesManager.java` | 实时新消息 / `parseMessages`：同上 |
| `data/TGMessageText.java`、`data/TGMessageMedia.java` | 折叠模式下把正文 / caption 换成占位文案；`expandContentFilter()` 恢复原文 |
| `data/TGMessage.java` | 折叠状态字段 + `onMessageClick` 里处理「点击展开」 |
| `data/ContentPreview.java` | 聊天列表（及转发预览等）显示「消息已被过滤」 |
| `ui/MessagesController.java`、`ui/ChatsController.java` | 焦点恢复 / `onActivityResume` 时比对 `ContentFilter.version()`，变化即刷新 |

文案放在 `res/values/local_strings.xml`（该文件头部标注不同步翻译平台），键名以 `ContentFilter` / `ContentFiltered` 开头。

### 已知限制

- 「完全过滤」只影响本客户端的渲染，消息本身仍在服务器上，换个客户端照常可见。
- 折叠模式下展开是**逐条**的，且仅对当前会话有效（重新进入会话会回到折叠态）。
- 媒体消息只有说明文字（caption）参与匹配，图片/视频内容本身不做识别。

## 3. 抽屉菜单精简

`navigation/DrawerController.java` 中移除了三项：

- 「邀请朋友」（`btn_invite`）
- 「通话」（`btn_calls`）
- 「帮助」（`btn_help`）

> 设置页里的「提问」（`AskAQuestion`）保留，因为需求只针对抽屉菜单。

## 4. 应用名称与图标

- 名称由 `local.properties` 的 `app.name` 决定（→ `resValue AppName` / `BuildConfig.PROJECT_NAME`），CI 里来自仓库变量 `APP_NAME`（当前：**Lite X**），工作流兜底值同样是 `Lite X`。改名称只需改这个变量。
- 图标：`app/src/main/res/mipmap-*/app_launcher.png`（方形）、`app_launcher_round.png`（圆形）、`app_notification.png`（通知栏，Android 只取 alpha，所以只画白色心形）、`logo_middle.png`。
  源文件 `_tools/icon_lite_x.svg`（仓库外），用 `_tools/gen_icons.js` 一次性导出全部密度：
  ```bash
  NODE_PATH=<node workspace>/node_modules node _tools/gen_icons.js <repo root>
  ```
  圆形 logo 在画布中占 86%（方形）/ 92%（圆形变体），四周留白，避免被各家的自适应图标蒙版裁掉。

## 5. 基于 GitHub Releases 的应用内更新

原来的「检查更新」走 Google Play / Telegram 频道，这里改成读**本仓库的 GitHub Releases**。

- 版本判定：Release 的 tag 形如 `v<versionCode>-<CI build number>`（例：`v1818000-42`）。
  `versionCode` 大于当前包，或相同但 build number 大于 `BuildConfig.CI_BUILD_NUMBER` 即认为有新版本。
  `CI_BUILD_NUMBER` 由 CI 通过 `-PciBuildNumber=$GITHUB_RUN_NUMBER` 注入，本地构建为 0。
- 接口：`https://api.github.com/repos/<owner>/<repo>/releases/latest`，由 `BuildConfig.SOURCES_URL`（`app.sources_url`）推导。
- 资源挑选：按 `Build.SUPPORTED_ABIS` 优先匹配 `arm64-v8a`，其次 `armeabi-v7a`。
- 点击「检查更新」（`SettingsController#btn_checkUpdates`）→ 后台线程请求 → 有新版本弹窗（下载更新 / 查看更新日志 / 取消），没有则 Toast「当前已是最新版本」，失败 Toast「检查更新失败」。
  「下载更新」是打开浏览器下载 Release 里的 APK，装包仍由系统完成（未内置静默安装）。
- 自动检查（冷启动 `BaseActivity`）同样走这条链路，受「自动更新」设置（`Settings.AUTO_UPDATE_MODE_*`）控制。

| 文件 | 改动 |
| --- | --- |
| `util/AppUpdater.java` | 新增 `FlowType.GITHUB_RELEASE`、`checkForGitHubUpdates()` / `offerGitHubUpdate()`、Release JSON 解析 |
| `ui/SettingsController.java` | 「检查更新」改为触发 GitHub 检查，图标换成 GitHub |
| `app/build.gradle.kts` | `buildConfigInt("CI_BUILD_NUMBER", …)` |
| `.github/workflows/build-apk.yml` | 新增 `release` job（下载两个 ABI 产物 → 生成 SHA256 → `softprops/action-gh-release` 发布） |
| `res/values/local_strings.xml` | `AppUpdateGithubPrompt` / `UpdateChangelog` / `UpdateCheckNoUpdates` / `UpdateCheckFailed` |

> 注意：工作流的 **tag 推送触发条件已改为 `build-*`**，因为发布用的 tag 是 `v<versionCode>-<runNumber>`，若继续匹配 `v*` 会出现「发布 → 触发构建 → 再发布」的死循环。
