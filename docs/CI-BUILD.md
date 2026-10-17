# 在 GitHub Actions 上构建 APK

工作流文件：`.github/workflows/build-apk.yml`（`Build APK`）

## 1. 一次性准备

1. Fork 本仓库，并把这份工作流一起提交到 **默认分支**（`workflow_dispatch` 型工作流必须存在于默认分支才会出现在 Actions 页面）。
2.（可选但强烈建议）在 **Settings → Secrets and variables → Actions** 中配置：

| 名称 | 类型 | 说明 |
| --- | --- | --- |
| `TELEGRAM_API_ID` | Secret | 在 https://core.telegram.org/api/obtaining_api_id 申请 |
| `TELEGRAM_API_HASH` | Secret | 同上 |
| `KEYSTORE_BASE64` | Secret | `base64 -w0 your.jks` 的输出；不配置时 CI 会临时生成一个 |
| `KEYSTORE_PASSWORD` / `KEY_ALIAS` / `KEY_PASSWORD` | Secret | 与上面 keystore 配套 |
| `APP_ID` | Variable | 默认 `org.example.tgx` |
| `APP_NAME` | Variable | 默认 `Telegram X` |

> 不配置 Telegram API 凭据时，构建仍会通过（回落到仓库示例凭据），但登录 Telegram 服务器可能被拒。
> 不配置 keystore 时，每次运行都会新生成一个，签名不一致意味着**无法覆盖安装**上一次的 APK。把 `save_generated_keystore` 勾上可以把生成结果导出保存成 Secret。

## 2. 运行

Actions → **Build APK** → *Run workflow*，可选：

- `build_type`：`Debug`（默认，最稳） / `Release`
- `flavor`：`latest`(minSdk 33，默认) / `qrs` / `tens` / `marshmallow` / `lollipop` / `legacy`
- `abi`：`arm64`（默认，单 ABI，体积与耗时最优） / `universal` / `arm32` / `x64` / `x86`
- `free_disk_space`：默认开，删掉镜像预装的 dotnet/Android SDK/Haskell 等，多腾出约 8–10 GB
- `save_generated_keystore`：导出临时 keystore（公开仓库慎开）

结束后在 Artifacts 里下载 `tgx-<flavor>-<abi>-<buildType>`，里面就是可直接安装的 `.apk`。

## 3. 工作流做了什么（以及为什么）

1. **清理磁盘** —— 官方 runner 实际可用空间约 22 GB，而仓库带子模块约 7.3 GB、Android SDK + NDK 约 6 GB，加上编译中间产物很容易撑爆。
2. **拉子模块 + LFS** —— `validateGitSetup` 任务会校验 `.gitmodules` 里每个子模块的 `.git` 都存在，且 `tdlib` 下的 `.so` 不是 LFS 指针。因此必须 `git submodule update --init --recursive` 并且对子模块执行 `git lfs pull`；actions/checkout 自带的 `lfs: true` **不会**处理子模块里的 LFS，所以这里手工执行。先尝试 `--depth 1`（快），失败则退回完整克隆。
3. **JDK 25** —— `buildSrc/src/main/kotlin/java-toolchain-convention.gradle.kts` 固定了 `JavaLanguageVersion.of(25)`。
4. **安装 SDK 组件** —— 版本号全部从 `version.properties` 读取（`sdk_package` / `build_tools` / `ndk_primary` / `cmake`），避免硬编码后与仓库升级脱节。`legacy` flavor 额外装 `ndk_legacy` 并追加 `-PuseLegacyNdk=true`。
5. **生成 `local.properties`** —— 构建必须存在该文件（`AppConfigurationSource` 直接 `getOrThrow("sdk.dir")`）。写 `sdk.dir`、`app.id/name`、`telegram.api_id/hash`、`keystore.file`。
6. **生成签名配置** —— `keystore.file` 指向的是 *配置文件*（本 CI 写的 `keystore.properties`），里面才是 `keystore.file / keystore.password / key.alias / key.password`（见 `Config.Keystore`）。
7. **内存调优** —— 仓库 `gradle.properties` 里 Gradle 与 Kotlin daemon 各 8 GB，runner 只有 16 GB，追加覆盖为 4 GB / 2 GB（同名属性后出现者生效）。
8. **构建** —— 任务名格式为 `assemble<Flavor><Abi><BuildType>`，例如 `assembleLatestArm64Debug`。Gradle 缓存由 `setup-java(cache: gradle)` 保留，`BuildFfmpegTask` / `BuildLibvpxTask` 等是 `@CacheableTask`，二次运行会显著加快。

## 4. 关于 `app.id` 的两个档位

- `org.example.*`（默认）：命中 `isExampleBuild`，于是**不启用** `google-services` 插件、**不做** R8 混淆、跳过 `validateApiTokens`。好处是无需 Firebase 配置即可构建；代价是 **FCM 推送不可用**，其余功能正常。
- `org.thunderdog.challegram` 等真实包名：会启用 google-services 插件与 R8，需要仓库里的 `google-services.json` 与包名匹配（`ValidateApiTokensTask` 会校验）。若要换成自有包名，需按 README 的 *Publishing* 章节替换 `google-services.json`。

## 5. 排查

| 现象 | 原因 / 处理 |
| --- | --- |
| `Submodules are not initialized` | 子模块没拉全，查看 *Fetch submodules* 步骤日志 |
| `LFS objects are not fetched` | `tdlib` 的 LFS 没拉到，同上 |
| `No space left on device` | 改单 ABI（`arm64`）、确认 `free_disk_space` 已开，或换更大 runner / 自建 runner |
| `sdk.dir is not set` | `local.properties` 生成步骤失败 |
| Release 构建卡在 `fetchLocalizedStrings` | 该任务会访问 translations.telegram.org 拉取全部语言；网络不通时改用 `Debug` |
| 构建超时 | 首次全量编译（含 WebRTC/FFmpeg/libvpx）通常 1–2 小时，上限已设为 360 分钟 |

## 6. 耗时与体积提示

- 首次运行：子模块 + LFS 拉取 + 全量原生编译，通常 **40–120 分钟**。
- 后续运行：命中 Gradle 构建缓存后通常 **10–25 分钟**。
- `universal` ABI 会同时编译 `arm64-v8a` 与 `armeabi-v7a`，耗时与磁盘占用接近翻倍。
