# Lite X — 轻量、纯净的 Telegram Android 客户端

![Lite X](/images/feature.png)

**Lite X** 是基于 [Telegram X](https://github.com/TGX-Android/Telegram-X)（官方实验性客户端，TDLib + MTProto）的第三方定制版，聚焦三件事：**中文优先、去广告、去打扰**。所有定制均为客户端本地行为，不修改协议、不收集任何数据。

## ✨ 功能特性

### 内置中文化
- 内置简体中文语言包（同步自官方翻译平台，5300+ 条），启动即中文，移除语言切换入口。

### 屏蔽广告（默认开启，设置 → 屏蔽广告）
- **聊天列表**：屏蔽 MTProto 代理的赞助商广告；
- **聊天窗口**：屏蔽频道/群中带「广告 / Ad」标识的官方推广消息；
- **链接黑名单**：长按广告链接 → 「屏蔽广告」，按域名加入持久黑名单，之后命中该域名的广告模块（正文链接、按钮、链接预览）一律不渲染，普通消息不受影响；
- 关闭开关即全部恢复显示。

### 内容过滤（设置 → 内容过滤）
- 支持**关键词 + 正则表达式**两类规则，作用于聊天列表摘要与聊天窗口；
- 两种模式：**完全过滤**（消息直接不渲染）与**折叠提示**（折叠成一行，点击展开原文）；
- 修改规则后聊天列表与聊天窗口实时生效，关闭开关即恢复。

### 界面与体验
- 主页顶部聊天文件夹标签显示**图标 + 名称**；
- 精简侧边抽屉：移除「邀请朋友」「通话」「帮助」；
- **代理测速**：代理连接弹窗新增「测速」，直接返回 RTT（ms），不改动已保存的代理配置。

### 应用内更新（GitHub Releases）
- 设置 → 检查更新：直接查询本仓库的 [Releases](https://github.com/consoleaiden/Lite-X/releases/latest)；
- 按 CPU 架构（arm64-v8a 优先，其次 armeabi-v7a）自动匹配 APK 资源；
- 版本对比规则：Release tag 形如 `v<versionCode>-<构建号>`，`versionCode` 更大、或相同而构建号更大即提示更新；
- 「下载更新」跳转浏览器下载对应架构的 APK，同时提供「查看更新日志」。

## 📲 下载

* [**最新 Release**](https://github.com/consoleaiden/Lite-X/releases/latest) — 每次构建自动发布，含 `SHA256.txt`
  * `arm64-v8a`：绝大多数现代设备
  * `armeabi-v7a`：32 位设备（与 arm64 包名相同，不能共存）
* 已安装的用户可在 **设置 → 检查更新** 中直接检测新版本。

## 🛠 GitHub Actions 自动构建

仓库自带 [`.github/workflows/build-apk.yml`](.github/workflows/build-apk.yml)（`workflow_dispatch` 手动触发），支持参数：

| 参数 | 说明 |
| --- | --- |
| `flavor` | SDK 兼容层：`marshmallow`（默认，minSdk 23）/ `latest` / `lollipop` / `legacy` |
| `abi` | `both`（默认，arm64 + arm32 并行）/ `arm64` / `arm32` / `universal` / `x64` / `x86` |
| `build_type` | `Debug` / `Release` |
| `publish_release` | 构建成功后自动发布到 GitHub Releases（默认开启） |

构建流程包含：子模块 + LFS 拉取 → JDK 25 → 按 `version.properties` 安装 SDK/NDK → 运行时生成 `local.properties` 与签名 keystore → flavor 与原生层白名单预检 → 构建并 `aapt2` 校验元数据 → 发布 Release。

详细说明见 [docs/CI-BUILD.md](docs/CI-BUILD.md)，各定制点的代码位置见 [docs/CUSTOMIZATION.md](docs/CUSTOMIZATION.md)。

## 🔨 本地构建

1. `$ git clone --recursive <本仓库> lite-x`（务必带 `--recursive`，缺了就 `git submodule update --init --recursive`）
2. 参考 [Obtain Telegram API credentials](https://core.telegram.org/api/obtaining_api_id) 申请 API 凭据
3. 在根目录创建 `local.properties`：<pre>sdk.dir=YOUR_ANDROID_SDK_FOLDER
   app.name=Lite X
   app.id=org.example.tgx
   app.sources_url=https://github.com/consoleaiden/Lite-X
   telegram.api_id=YOUR_TELEGRAM_API_ID
   telegram.api_hash=YOUR_TELEGRAM_API_HASH</pre>
4. 用 Android Studio 打开，或命令行构建：`./gradlew assembleMarshmallowArm64Debug`

## 上游项目（Telegram X）

以下为上游 [TGX-Android/Telegram-X](https://github.com/TGX-Android/Telegram-X) 的原始构建说明，仍然适用于本仓库的代码结构：

<details>
<summary>Build instructions（原版）</summary>

* Repository must be fetched via `git`
* **JDK** or **[Android Studio](https://developer.android.com/studio/)** (with compatible bundled JDK)
* At least **8GB** of RAM
* At least **7,32GB** of free disk space when cloning with `--shallow-submodules --depth=1`
* At least **2-5x** times more disk space for files generated during build process.

In Windows `.properties` files, use forward slashes in paths, e.g. `keystore.file=E:/Telegram-X/release-tgx.jks` in `keystore.properties`. In `local.properties`, `keystore.file` points to the settings file, e.g. `keystore.file=keystore.properties`.

## Reproducing public builds

In order to verify that there is no additional source code injected inside official APKs, you must use one of the following versions of **Ubuntu**:

* **21.04**: for builds published before [26th May 2023](https://github.com/TGX-Android/Telegram-X/commit/e9a054a0f469a98a13f7e0d751539687fef8759b)
* **22.04.2 LTS**: for builds published before 27th September 2025
* **24.04 LTS**: for builds published before 1st October 2026
* **26.04 LTS**: for any newer releases

And update its configuration:

1. Create user called `vk` with the home directory located at `/home/vk`
2. Clone `tgx` repository to `/home/vk/tgx`
3. Check out the specific commit you want to verify
4. In rare cases of builds that include unmerged pull requests, you must follow actions performed by [Publisher's](https://github.com/TGX-Android/Publisher/blob/main/main.js) `fetchPr` and `squashPr` tasks
5. `cd` into `tgx` folder and install dependencies: `# apt install $(cat reproducible-builds/dependencies.txt)`
6. Follow up the build instruction from the previous section
7. Run `$ apkanalyzer apk compare --different-only <remote-apk> <reproduced-apk>`
8. If only signature files and metadata differ, build reproduction is successful.

## Verifying side-loaded APKs

If you downloaded **Telegram X** APK from somewhere and would like to simply verify whether it's an original APK without any injected malicious source code, you need to get checksum (`SHA-256`, `SHA-1` or `MD5`) of the downloaded APK file and find whether it corresponds to any known **Telegram X** version.

In order to obtain **SHA-256** of the APK:

* `$ sha256sum <path-to-apk>` on **Ubuntu**
* `$ shasum -a 256 <path-to-apk>` on **macOS**
* `$ certutil -hashfile <path-to-apk> SHA256` on **Windows**

Once obtained, there are three ways to find out the commit for the specific checksum:

* Sending checksum to [`@tgx_bot`](https://t.me/tgx_bot)
* Searching for a checksum in [`@tgx_log`](https://t.me/tgx_log). You can do so without need in installing any Telegram client by using this URL format: [`https://t.me/s/tgx_log?q={checksum}`](https://t.me/s/tgx_log?q=c541ebb0a3ae7bb6e6bd155530f375d567b8aef1761fdd942fb5d69af62e24ae) (click to see in action). Note: unpublished builds cannot be verified this way.

</details>

## License

上游 `Telegram X` 以 **GNU General Public License v3.0** 授权，本仓库的定制改动同样以 GPLv3 发布。

For more information, see [LICENSE](/LICENSE) file.

License of components and third-party dependencies it relies on might differ, check `LICENSE` file in the corresponding folder. List of third-party components used can be found [here](/docs/THIRDPARTY.md).

## Contributions

欢迎 issue / PR。提交前建议先阅读上游的 [contributor guide](/docs/GUIDE.md) 了解 Telegram X 内部结构。
