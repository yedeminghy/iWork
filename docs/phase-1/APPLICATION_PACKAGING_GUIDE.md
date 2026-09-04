# iWork 应用打包指南

更新日期：2026-08-28  
适用平台：Windows x64  
当前应用版本：`1.0.0`

## 1. 打包产物说明

项目使用 Electron Builder 生成 Windows NSIS 安装器，并将以下运行时一并封装：

- Electron Main 与 Preload：`desktop-shell/dist`
- React Renderer：`desktop-ui/dist`
- Java Host：`agent-host.jar`
- 通过 `jlink` 裁剪的 Java 21 Runtime
- 内置 Skills、Provider Catalog、第三方许可证和发布清单

最终产物位于 `dist-release/`：

```text
dist-release/
├── iWork-<version>-windows-x64.exe           # NSIS 安装器
├── win-unpacked/                             # 免安装展开目录
├── release-artifacts.json                    # 安装器 hash、渠道、签名状态
└── week*-verification.json                   # 对应门禁的验证摘要
```

`dist-release/` 和 `packaging/stage/` 都是可重新生成的构建目录，不应作为源码资产提交。

## 2. 环境准备

在 Windows PowerShell 中执行打包。所需工具如下：

1. Java 21 JDK，必须与 `packaging/runtime-lock.json` 中的 vendor、version 和 `release` 文件 SHA-256 完全一致。
2. Maven，可执行 `mvn`。
3. Node.js 与 npm，可正常执行项目中的 npm workspace 脚本。
4. Windows PowerShell 5.1 或 PowerShell 7。
5. 生产打包还需要 Authenticode 代码签名证书。

检查环境：

```powershell
java -version
mvn -version
node --version
npm --version
```

当前运行时锁定为：

```text
JDK feature: 21
Vendor: Eclipse Adoptium
Version: 21.0.12
Implementor version: Temurin-21.0.12+8
Platform: Windows x64
```

如果系统中存在多个 JDK，打包时应显式传入 `-JdkHome`，不要依赖 PATH 中的默认 Java。

## 3. 打包前检查

### 3.1 检查版本一致性

以下文件中的应用版本必须一致：

- `package.json`
- `desktop-shell/package.json`
- `desktop-ui/package.json`
- `pom.xml` 中的 `agentdesktop.release.version`

修改版本后同步 npm lockfile：

```powershell
npm install --package-lock-only --ignore-scripts
```

打包脚本会再次检查版本；不一致时直接终止。

### 3.2 禁止带入敏感信息

打包前确认源码和环境中没有 Provider Key、Bearer Token、Cookie、私钥、用户绝对路径或 `.env` 文件。包内容验证会扫描 ASAR、发布清单和第三方许可证，并拒绝以下内容：

- `src`、`test`、`fixtures`、`docs`、`.git`、`.env`
- Source Map
- 完整 Node `node_modules`
- JDK 开发工具，如 `javac.exe`、`jlink.exe`
- Electron elevation helper
- 可识别的本机用户路径和密钥格式

### 3.3 执行提交级门禁

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\verify-week9.ps1 -Profile commit
```

该命令会执行 Maven 测试、`npm ci`、类型检查、桌面构建、第 9 周契约、输出物预览安全检查和历史回归。任何步骤失败都不应继续生成候选包。

## 4. 构建内部测试包

推荐显式指定锁定的 JDK：

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\build-week7-package.ps1 `
  -Channel internal `
  -JdkHome 'C:\path\to\jdk-21.0.12'
```

如果 PATH 中的 Java 已经与 `runtime-lock.json` 完全匹配，也可以使用快捷命令：

```powershell
npm run package:win
```

内部渠道允许生成未签名包。生成的 `release-manifest.json` 会包含：

```json
{
  "channel": "internal",
  "unsigned": true
}
```

内部未签名安装器只能用于开发或受控内测，分发时必须附带 `release-artifacts.json` 中的 SHA-256，并明确提示 Windows 可能显示“未知发布者”。

## 5. 打包脚本执行流程

`build-week7-package.ps1` 会按以下顺序执行：

1. 检查 npm workspace、Maven release version 是否一致。
2. 执行 `mvn -q clean verify`。
3. 执行 `npm ci`，严格按照 `package-lock.json` 安装依赖。
4. 执行 `npm run typecheck`。
5. 清理旧的 UI/Shell `dist` 并执行 `npm run build`。
6. 将 Host JAR、Skills、Provider Catalog 和许可证复制到 `packaging/stage/`。
7. 使用 `jdeps` 计算 Java 模块，再通过 `jlink` 生成精简 JRE。
8. 生成带文件 hash、运行时 hash 和 source revision 的 `release-manifest.json`。
9. 使用 Electron Builder 生成 `win-unpacked/`。
10. 使用 Electron Builder 生成 x64 NSIS 安装器。
11. 校验包内容白名单、ASAR 内容、运行时、manifest 和敏感信息。
12. 计算安装器 SHA-256，并生成 `release-artifacts.json`。

`-SkipBuild` 只适合本地调试打包脚本。正式候选包不得使用该参数，因为它会复用已有 JAR 和前端构建物。

## 6. 验证生成的包

### 6.1 查看发布摘要

```powershell
Get-Content .\dist-release\release-artifacts.json
```

示例结构：

```json
{
  "schemaVersion": 1,
  "appVersion": "1.0.0",
  "channel": "internal",
  "unsigned": true,
  "installer": "iWork-1.0.0-windows-x64.exe",
  "sha256": "...",
  "sourceRevision": "source-sha256:..."
}
```

### 6.2 重新计算安装器 hash

```powershell
Get-FileHash `
  .\dist-release\iWork-1.0.0-windows-x64.exe `
  -Algorithm SHA256
```

结果必须与 `release-artifacts.json` 一致。

### 6.3 校验展开包

```powershell
node .\scripts\verify-week7-package.mjs `
  .\dist-release\win-unpacked `
  internal
```

### 6.4 验证桌面运行链路

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\verify-desktop-smoke.ps1 `
  -JavaHome 'C:\path\to\jdk-21.0.12' `
  -SkipBuild
```

该测试使用独立临时 appHome，不读取或覆盖日常用户配置。

## 7. 安装、升级和卸载验收

发布候选包必须在可丢弃的 Windows x64 VM 或一次性非管理员用户中执行，不要使用开发者日常账户。

验收环境要求：

1. 当前用户不是管理员。
2. 未安装当前产品，不存在遗留卸载项和默认安装目录。
3. 断开网络，PATH 不依赖全局 Java、Node 或 Maven。
4. 准备已核验 hash 的 previous-release 安装器。
5. 设置一次性用户的真实 SID。

```powershell
$env:AGENT_DESKTOP_INSTALL_ACCEPTANCE = '1'
$env:AGENT_DESKTOP_ACCEPTANCE_USER_SID = '<一次性用户 SID>'

powershell -ExecutionPolicy Bypass -File .\scripts\verify-week7.ps1
```

不得携带 `-SkipPackage` 或 `-SkipInstallAcceptance`。成功结果应写入：

```text
dist-release/week7-verification.json
```

只有 `result=passed` 且 `releaseGate=week7-complete` 才表示安装、升级、数据保留、卸载和重装门禁完整通过。带 skip 参数的结果只能标记为 `development-only`。

## 8. 构建生产签名包

生产构建前必须完成以下事项：

- 冻结正式 `appId`、产品名和 Publisher。
- 配置 Windows 多尺寸 `.ico`。
- 完成 EULA 与隐私文本确认。
- 在 `packaging/runtime-lock.json` 中填写官方 JDK `downloadUrl` 与 `archiveSha256`。
- 配置 Authenticode 证书、私钥安全注入方式和时间戳服务。

Electron Builder 通常通过受保护的进程环境或 CI Secret 注入签名材料，例如：

```powershell
$env:CSC_LINK = '<证书文件路径、URL 或受支持的编码值>'
$env:CSC_KEY_PASSWORD = '<证书密码>'
```

不要把这些值写入脚本、配置文件、终端日志或源码仓库。

执行生产打包：

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\build-week7-package.ps1 `
  -Channel production `
  -JdkHome 'C:\path\to\locked-jdk-21.0.12'
```

生产渠道会启用 `signAndEditExecutable`，并要求安装器 Authenticode 状态为 `Valid`。以下任意情况都会 fail closed：

- JDK 官方归档 URL 或 SHA-256 缺失。
- 本地 JDK 与 runtime lock 不一致。
- 未找到签名材料。
- 安装器签名无效或无法验证。
- 包内容或发布清单验证失败。

签名后检查：

```powershell
Get-AuthenticodeSignature `
  .\dist-release\iWork-1.0.0-windows-x64.exe | `
  Format-List Status,StatusMessage,SignerCertificate,TimeStamperCertificate
```

必须确认 `Status` 为 `Valid`，签名主体与正式 Publisher 一致，并存在有效时间戳。

## 9. 可选依赖说明

LibreOffice 当前不会随安装器打包。DOCX/XLSX/XLS 的“生成版式预览”会按可信固定位置检测系统 LibreOffice；未安装时应用会明确降级为快速预览或“使用默认应用打开”。

如果未来决定内置 LibreOffice，必须先完成许可证、体积、补丁更新、可执行文件签名、包内容白名单和转换沙箱评审，不能直接把 Office 目录复制进 `packaging/stage/`。

## 10. 常见问题

### JDK vendor、version 或 release hash 不匹配

原因：打包使用的 JDK 与 `packaging/runtime-lock.json` 不一致。

处理：安装锁定版本，并通过 `-JdkHome` 指向其根目录。不要修改 hash 来迁就未知来源的 JDK。

### Production runtime lock is incomplete

原因：生产渠道要求 `downloadUrl` 和 `archiveSha256` 非空。

处理：从 JDK 官方发布页重新核对归档地址和 SHA-256，再更新 runtime lock 并重新执行完整构建。

### npm ci 失败

原因通常是 `package.json` 与 `package-lock.json` 不一致，或依赖源不可达。

处理：确认版本和依赖变更正确后执行：

```powershell
npm install --package-lock-only --ignore-scripts
npm ci
```

### Electron Builder 下载失败

确认网络、代理和缓存目录可用，不要通过关闭 TLS 校验规避问题。完成依赖下载后应从头重新执行打包脚本。

### 文件被占用，无法清理 dist-release 或 packaging/stage

关闭正在运行的 `agent-desktop.exe`、Electron 测试进程以及打开这些目录的终端或杀毒扫描任务，再重新打包。不要手工混用旧 stage 与新构建物。

### Packaged runtime 出现 AccessDeniedException

在一次性 Windows 用户或 VM 中确认临时目录和 appHome 可写，并检查安全软件或沙箱策略。不要以管理员身份绕过验收，因为正式安装目标是当前用户、无提权模式。

## 11. 发布交付清单

交付候选包时至少保留：

- NSIS 安装器
- `release-artifacts.json`
- `release-manifest.json`
- `week7-verification.json`
- `week9-verification.json`
- 安装器 SHA-256
- Authenticode 校验结果（生产渠道）
- 安装、升级、卸载和重装验收记录
- 构建使用的源码 revision、JDK lock 和构建时间

生产发布前还必须逐项关闭 `PRODUCTION_RELEASE_TODO.md` 中的正式身份、品牌、法务、签名、JDK 归档锁和真实安装证据。
