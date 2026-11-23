# 野搜 WildSearch

一个用 Kotlin + Jetpack Compose 写的 Android 应用，调用**博查 Web Search API** 做全网搜索，并支持**应用内检查更新**（GitHub Releases）。

- 仓库：<https://github.com/Wildcreator2010/WildSearch>
- 下载：<https://github.com/Wildcreator2010/WildSearch/releases/latest>

## 功能

- 关键词搜索，结果卡片展示：站点图标、站点名、发布时间、标题、来源域名、摘要
- 每条结果可展开博查**长摘要**（`summary`）
- 筛选：时间范围（不限 / 一天 / 一周 / 一月 / 一年）、返回条数（5/10/20/30/50）、长摘要开关
- 结果操作：点击打开浏览器、复制链接、系统分享
- 搜索历史（本地最近 20 条，可单条删除、可清空）
- **原始响应**查看器：返回体的 UTF-8 安全预览可查看、复制（最多 256 KiB，截断会标明），用于核对接口字段
- **应用内更新**：启动与回到前台时自动检查（6 小时节流）+ 设置页手动检查，应用内下载 APK 并调起系统安装器
- API Key 存在本机 DataStore，不写日志、不进构建产物

## 技术栈

| 项 | 版本 |
|---|---|
| Kotlin | 2.0.21（+ compose / serialization 插件） |
| AGP / Gradle | 8.7.3 / 8.9 |
| compileSdk / minSdk | 35 / 26 |
| UI | Jetpack Compose（compose-bom 2024.10.01, material3 1.3.1） |
| 网络 | OkHttp 4.12.0（不用 Retrofit，单端点直连，便于留存原始响应） |
| 序列化 | kotlinx.serialization 1.7.3 |
| 持久化 | DataStore Preferences 1.1.1 |
| 图片加载 | Coil 2.7.0 |

## 构建

需要 JDK 17+（推荐 JDK 21，并设置 `JAVA_HOME`）。

```bash
export JAVA_HOME=/path/to/jdk-21            # Windows: $env:JAVA_HOME="C:\path\to\jdk-21"
./gradlew assembleDebug                      # 产物 app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

`local.properties` 里的 `sdk.dir` 是机器相关配置，不要提交。

## 使用

1. 到 [open.bochaai.com](https://open.bochaai.com) 申请 API Key。
2. 打开 App → 右上角设置 → 填入 Key → 保存。
3. 回到首页输入关键词搜索。

## 应用内更新（GitHub Releases）

### 更新源

正式更新源固定为：

- 仓库：<https://github.com/Wildcreator2010/WildSearch>
- 元数据：`https://api.github.com/repos/Wildcreator2010/WildSearch/releases/latest`
- APK：只从该仓库 latest Release 的 `.apk` 附件下载

`gradle.properties` 已配置：

```properties
wildsearch.update.repo=Wildcreator2010/WildSearch
# 仅调试用：把更新 API 换成自建 mock
# wildsearch.update.apiBase=http://10.0.2.2:8899
```

仓库留空时，「检查更新」显示**未配置更新仓库**，不会发出任何网络请求。配置通过 BuildConfig 注入（`UPDATE_REPO` / `UPDATE_API_BASE`），可用 `-P` 覆盖。

### 发布新版本

1. 更新 `app/build.gradle.kts` 的 `versionCode`（+1）和 `versionName`。
2. 在 `docs/release-notes/<version>.md` 写更新说明（会原样显示在更新对话框里）。
3. 提交并推送 `main`。
4. 设置只读环境变量后运行发布脚本：

```powershell
$env:GITHUB_TOKEN = 'github_pat_...'   # fine-grained PAT，Contents: Read and write
# 首次发布：初始化 Git、提交、创建公开仓库、推送 main、构建并发布
.\tools\publish-release.ps1 -Bootstrap
# 后续版本（已提交并推送 main）
.\tools\publish-release.ps1
```

脚本会构建 Release、校验签名证书指纹与 APK 版本、生成 SHA-256、创建 `v<version>` Release、上传 APK 与 `.sha256`，并核对远端 latest 的 tag、大小和 digest。`-DryRun` 只构建并生成 `dist/`，不访问 GitHub；Token 未设置时会隐藏输入。

不要把 `dist/`、`audit/`、签名材料或 Token 提交到仓库。

判定逻辑：保留 `v/V` 前缀、短版本及缺失数字段补零兼容；同核心版本按 SemVer 预发布优先级比较（正式版高于 beta），构建元数据不参与排序。非法版本不提示更新。

### 正式签名（不要提交密钥或密码）

当前版本为 `1.0.2` / `versionCode=3`。默认签名材料在工作区外：

- `%USERPROFILE%\.android\wildsearch\wildsearch-release.p12`
- `%USERPROFILE%\.android\wildsearch\release-signing.properties`

首次创建使用 `tools/create-release-signing.ps1`：PKCS12、RSA 3072、别名 `wildsearch`、有效期 10,950 天，随机密码不打印；目录仅授权当前用户和 SYSTEM。脚本发现既有密钥或配置就拒绝覆盖，**日后发版不得重新生成密钥**。

Gradle 默认读取当前用户的 `.android/wildsearch/release-signing.properties`；可用环境变量 `WILDSEARCH_SIGNING_PROPERTIES` 指定其他配置文件。配置字段为 `storeFile`、`storeType`、`keyAlias`、`storePassword`、`keyPassword`；相对密钥路径以该配置文件的目录为基准。缺失或不完整时 Release 构建明确失败，Debug 保留原签名且可独立构建。

请将密钥、密码配置和证书指纹一起备份到**加密的离线存储**，不要上传代码仓库或放入 APK。后续更新必须一直使用这份密钥。新正式签名不能覆盖现有 debug 签名安装；本次不会自动卸载应用或删除数据。

### 实现要点

- 端点：`GET {apiBase}/repos/{owner}/{repo}/releases/latest`
  Header：`Accept: application/vnd.github+json`、`User-Agent`（GitHub 强制要求）
- 附件选择优先级：含 `release` → 含 `universal` → 名字最短的 `.apk`；`.sig` 等非 APK 忽略
- 下载到 `cacheDir/updates/`，三重校验后才交给安装器：
  1. **大小**与 release 里的 `size` 一致
  2. 若 release 带 `digest`（`sha256:...`），比对本地计算的 SHA-256
  3. 文件头必须是 `PK\x03\x04`（APK 本质是 zip）
- 安装：`FileProvider`（`${applicationId}.fileprovider`，路径 `cache/updates`）+ `ACTION_VIEW`，
  权限 `REQUEST_INSTALL_PACKAGES`；未授权会跳到「安装未知应用」授权页，返回后检查实际权限并继续安装缓存 APK。拒绝授权或调起安装器失败可以重试，无需再次下载
- 网络取消覆盖响应读取全过程；关闭更新检查会取消检查，关闭下载弹窗只隐藏界面。下载采用独立超时：连接 15 秒、读取 60 秒、总计 10 分钟；临时文件按任务隔离，不覆盖已有有效缓存
- 自动检查：应用启动时与每次回到前台时触发，6 小时内最多一次（GitHub 匿名 API 有速率限制），设置页可关闭

## 接口约定（博查，实测）

```
POST https://api.bochaai.com/v1/web-search
Authorization: Bearer <API_KEY>
Content-Type: application/json          # 缺失会 415

{"query":"...","freshness":"noLimit|oneDay|oneWeek|oneMonth|oneYear","summary":true,"count":10}
```

成功：

```json
{"code":200,"log_id":"...","msg":null,
 "data":{"_type":"SearchResponse",
         "queryContext":{"originalQuery":"..."},
         "webPages":{"webSearchUrl":"...","totalEstimatedMatches":606721,
                     "value":[{"id","name","url","displayUrl","snippet","summary",
                               "siteName","siteIcon","datePublished","dateLastCrawled"}]}}}
```

实测中值得记录的几处坑（代码已按此实现）：

1. **HTTP 状态码才是成败依据**。实测出现过 HTTP 400 而 body 里 `code:"500"`，不能拿 body 的 `code` 判成败。
2. **`code` 类型不一致**：成功体是数字 `200`，错误体是字符串 `"401"`。所以用 `JsonElement` 接。
3. **错误文案字段不统一**：`message`（业务错误）/ `msg`（带 `success:false` 的封装）/ `error`（Spring 默认体）。已按 `message → msg → error → subCode` 兜底。
4. **415/404 会返回带完整 Java 堆栈的 `trace` 字段**，错误体上限 256 KiB；正常搜索响应上限 8 MiB。超过上限明确提示，不将截断内容作为完整 JSON 解析。
5. `query` 的 400 字符上限**没有官方来源**，故不在客户端拦截；超限由服务端返回 400 并原样展示提示。

## 目录

```
app/src/main/java/com/wildcreator/wildsearch/
├── MainActivity.kt                 入口 + 页面切换 + 更新安装副作用
├── data/
│   ├── model/BochaModels.kt        接口 DTO + 宽松 Json 配置
│   ├── model/SearchModels.kt       领域模型 / 筛选枚举 / 设置项
│   ├── remote/BochaApi.kt          OkHttp 调用 + 错误映射
│   ├── remote/SearchError.kt       可读错误 + 调用结果类型
│   ├── remote/UpdateModels.kt      GitHub Release DTO + 更新领域模型
│   ├── remote/UpdateRepository.kt  检查更新 + 下载 + 完整性校验
│   └── SettingsRepository.kt       DataStore 读写（Key / 筛选 / 历史 / 更新开关）
├── ui/
│   ├── SearchViewModel.kt          搜索状态源
│   ├── UpdateViewModel.kt          更新状态源（检查 / 下载 / 安装状态机）
│   ├── SearchScreen.kt             搜索页 / 空态 / 错误态 / 原始响应弹窗
│   ├── SettingsScreen.kt           设置页（含版本更新卡片）
│   ├── components/ResultCard.kt    结果卡片
│   ├── components/UpdateDialog.kt  更新对话框（检查/可更新/下载中/失败/未配置）
│   └── theme/Theme.kt              固定配色
└── util/
    ├── Platform.kt                 打开链接 / 复制 / 分享 / 格式化
    ├── VersionCompare.kt           semver 比较 / 字节格式化
    └── Installer.kt                FileProvider 安装 / 安装权限判断
```

## 开发期调试说明

`tools/` 下两个脚本**只在开发时用**，不参与 App 运行、也不打进 APK。

### 1. 模拟器 DNS 不可用时访问博查 API

部分模拟器实例里 DNS 不可用（UDP 被拦）但 TCP 正常，导致无法解析 `api.bochaai.com`：

```bash
python tools/dev_proxy.py 8888 &
adb reverse tcp:8888 tcp:8888
adb shell settings put global http_proxy 127.0.0.1:8888
adb shell am force-stop com.wildcreator.wildsearch
```

测完清理：

```bash
adb shell settings put global http_proxy :0
adb reverse --remove-all
```

真机不需要这些步骤，直连即可。

## 回归检测

```powershell
.\gradlew.bat testDebugUnitTest testReleaseUnitTest assembleDebug assembleRelease lintDebug lintRelease
python -m unittest tools.test_dev_proxy -v
```

JVM 回归使用真实 Preferences DataStore 与 MockWebServer，不使用真实 API Key 或博查额度；慢速下载用例需约 46 秒。本地审计脚本与证据在 `audit/`，该目录含机器路径和构建缓存，不提交到仓库。

GitHub Actions 在 push / PR 时运行单元测试、Debug 构建与 Lint；Release 签名只在本地执行，密钥不会上传 CI。

### 2. 本地模拟 GitHub Releases API

没有真实仓库也能验证整条更新链路——服务返回与 `api.github.com` 同构的 JSON，APK 附件直接用本地构建产物：

```bash
# 模拟有新版本
python tools/mock_update_api.py app/build/outputs/apk/debug/app-debug.apk 8899 v1.1.0 &
# 模拟已是最新
python tools/mock_update_api.py app/build/outputs/apk/debug/app-debug.apk 8899 1.0.0 &

# 用 mock 构建并安装（debug 构建才允许明文 HTTP 访问 10.0.2.2）
./gradlew assembleDebug "-Pwildsearch.update.repo=demo/demo" "-Pwildsearch.update.apiBase=http://10.0.2.2:8899"
```

装好后启动 App 即触发自动检查；也可在设置页点「检查更新」。
明文 HTTP 只对 `10.0.2.2 / 127.0.0.1 / localhost` 在 **debug** 构建中放开（`src/debug/res/xml/network_security_config.xml`），release 仍是 HTTPS-only。
