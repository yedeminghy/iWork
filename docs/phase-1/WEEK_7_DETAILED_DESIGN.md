# 第 7 周详细设计：Windows 打包、显式升级与崩溃恢复

> 状态：已定稿，评审后可开工  
> 日期：2026-08-22  
> 上位方案：[一期最终架构方案](../FINAL_ARCHITECTURE.md)  
> 前置基线：[第 6 周详细设计](WEEK_6_DETAILED_DESIGN.md)  
> 实施状态：[阶段一实施状态](IMPLEMENTATION_STATUS.md)

## 0. 结论与设计约束

本周严格对应总体方案中的唯一交付：

| 总体方案阶段 | 交付内容 | 验收 |
| --- | --- | --- |
| 第 7 周 | 打包、升级、崩溃恢复 | 可安装、升级，异常退出不丢失已持久化事件 |

设计采用以下结论：

1. 首发只交付 Windows x64 当前用户安装包，不请求管理员权限；macOS/Linux 安装包留到后续跨平台阶段。
2. 使用 `electron-builder + NSIS` 打包 Electron、React 生产资源、Spring Boot Host、内置 Skills 和精简 Java 21 runtime；目标电脑不依赖全局 Java、Maven、Node 或网络下载。
3. “升级”只指用户显式运行新版本安装包后的就地升级。总体方案明确排除后台同步、无人值守自动安装和自动升级，因此本周不引入 updater 服务、静默下载或后台安装。
4. 安装目录与 `appHome` 严格分离。升级只替换安装资源；正常卸载绝不删除 `%USERPROFILE%\.agent-desktop`、workspace、SQLite、Skills、配置或 `safeStorage` 数据。
5. 升级前对现有 SQLite 生成一致性备份，再执行 Flyway 前向 migration；迁移失败时 Host 不进入 ready，不自动用旧程序打开新 schema，也不静默覆盖备份。
6. 崩溃恢复的含义是“恢复已持久化状态并安全终止未完成动作”，不是自动续跑。异常退出后保留已提交 Item，未完成 Turn 对账为 `INTERRUPTED` 或既有稳定取消码，Tool、MCP、脚本和审批均不重放。
7. Main 只可对崩溃后的 Host 做一次有界重启；重启成功后重新加载快照，不恢复内存授权。重复崩溃进入可诊断失败态，等待用户显式重启应用。
8. 发布物以 release manifest、SHA-256 和 Windows 代码签名形成完整性链。没有正式产品名、图标或签名证书时可以生成标记清晰的内部验收包，但不能声明为生产发布包。
9. 第 1～6 周的 loopback 鉴权、受限 preload、ToolGateway、Approval、文件根、Skill/MCP 安全边界在安装态必须原样成立。
10. 第 8～9 周的多模型兼容矩阵、长时 soak、广泛设备兼容和内测反馈不提前纳入本周。

### 0.1 当前基线差距

| 当前实现 | 第 7 周要求 |
| --- | --- |
| Electron 仅能从仓库构建和开发/冒烟启动 | 生成可安装的 Windows x64 NSIS 安装包 |
| `resolveJava()` 可探测 `resources/jre`，但尚无 runtime image 构建 | 构建、裁剪、打包、校验固定 Java 21 runtime |
| `resolveHostJar()` 可探测 `resources/agent-host.jar` | 将正式 Host JAR、Skills 和 manifest 作为只读安装资源发布 |
| Renderer 路径按仓库层级解析 | 开发态与 `app.asar` 安装态分别使用确定性资源路径 |
| Host 仅覆盖冷启动失败重试与正常退出 | 覆盖运行期 Host 崩溃、Main/Renderer 崩溃和父进程消失 |
| 启动对账可中断未完成 Turn | 将该行为纳入安装态崩溃恢复和不重放验收 |
| Flyway 在 Host 启动时直接 migrate | 升级前一致性备份、版本兼容检查、失败锁定和诊断 |
| 无发布版本/文件清单 | 单一版本源、release manifest、hash、签名与可追溯构建 |
| 无安装/升级/卸载门禁 | 新增 `verify-week7.ps1` 与隔离 Windows 安装验收 |

### 0.2 本周完成定义

只有 W7-A01～W7-A12 全部通过，才能将第 7 周标为完成：

1. 从干净 checkout 可一键生成 Windows x64 NSIS 当前用户安装包、unpacked 目录、SHA-256 和 release manifest。
2. 无管理员权限、无全局 Java/Node/Maven、无网络的验收用户可安装并首次启动。
3. 安装态只使用包内精简 Java 21 runtime、Host JAR、Renderer 与内置 Skills；资源缺失或 hash 不符时 fail closed。
4. 新安装自动创建既有 `appHome`/Profile/workspace，完成一次真实生产 Renderer→Main→Host 会话。
5. 从上一可升级基线安装新版本后，Thread/Item、MCP 配置、Skill 安装、文件根和密钥密文均保留；安装目录被替换而 `appHome` 不迁移、不复制到安装目录。
6. 数据库升级前有一致性备份；Flyway 成功后写入 release state，失败时 Host 不 ready、备份仍可校验且错误不泄露私有数据。
7. Host、Renderer、Electron Main 三类异常均有稳定恢复策略；所有已提交事件可从快照恢复，未提交内存增量允许丢失。
8. 崩溃后未完成 Turn/Approval、Tool、Skill 脚本和 MCP 调用全部 fail closed，不重复副作用；W6 thread grant 清空。
9. 正常卸载只删除安装资源、快捷方式和本产品安装注册信息，不删除 `appHome` 或用户选择的文件根内容。
10. 安装包/可执行文件签名与 release manifest 可验证；内部无证书验收包必须显式标为 unsigned，不得混入 production channel。
11. 安装态日志、崩溃标记、备份 manifest、命令行、注册表和安装日志均不含 token、正文、原始参数、私有绝对路径或 reasoning。
12. `verify-week7.ps1` 覆盖构建、unpacked smoke、真实安装/升级/卸载、崩溃注入、数据保持、泄漏扫描和 Week 6 完整回归，并返回 0。

## 1. 范围与非范围

### 1.1 本周必须交付

1. 根 npm workspace：统一发布版本、`package`/`dist:win` 脚本和锁文件一致性检查。
2. `desktop-shell`：安装态路径解析、release manifest 校验、单实例、运行期 Host 监督、父子进程存活契约、崩溃状态与恢复 IPC。
3. `desktop-ui`：Host 崩溃/恢复状态、只读版本信息、恢复后的快照重载和可复制诊断编号。
4. `agent-persistence`：升级兼容检查、一致性 SQLite 备份、备份 manifest、保留策略与迁移成功 release state。
5. `agent-host`：启动恢复摘要、父 Main 存活监测、优雅/异常退出分类，以及不重放既有 Turn/Tool 的对账回归。
6. `packaging/`：electron-builder 配置、图标占位策略、runtime 模块清单、release manifest 模板和 NSIS 安全定制。
7. `scripts/`：JRE image、release manifest、Windows 包构建和 `verify-week7.ps1`。
8. 可重复的 previous-release 数据库 fixture 与显式 `-PreviousInstaller` 升级验收入口。

### 1.2 明确不在本周

- 不实现后台检查更新、自动下载、差分下载、静默安装、无人值守自动安装或强制升级。
- 不实现远程更新服务、发布 CDN、更新 Feed、灰度发布后台或遥测上传。
- 不支持应用运行中热替换 Host/JRE/asar；安装前必须退出旧实例。
- 不自动降级数据库，不允许旧 Host 打开超出其支持范围的 schema。
- 不把崩溃恢复解释为自动继续模型循环、重试 Provider、重放 Tool、恢复 Approval 或重新执行脚本。
- 不删除用户 workspace，不在卸载器中提供默认勾选的“删除数据”。本期卸载器始终保留 `appHome`。
- 不新增 macOS/Linux installer，不处理 ARM64，不提前执行第 8～9 周的广泛兼容性和长稳测试。
- 不扩展 Provider、MCP OAuth、Skill 市场、多 Agent、浏览器自动化或新的文件/进程能力。

## 2. 发布拓扑与文件布局

### 2.1 Windows 安装模型

| 属性 | 固定决策 |
| --- | --- |
| 架构 | Windows x64 |
| 安装器 | electron-builder NSIS assisted installer |
| 权限 | `perMachine=false`、`allowElevation=false`，当前用户安装 |
| 默认安装位置 | `%LOCALAPPDATA%\Programs\agent-desktop`；正式产品名确定后只改展示名，不改稳定 appId |
| 单实例 | Electron `app.requestSingleInstanceLock()`；第二实例只聚焦主窗口 |
| 快捷方式 | 开始菜单；桌面快捷方式由安装页显式选择 |
| 卸载 | 删除安装目录、快捷方式和安装注册信息；保留 `appHome` |
| 运行数据 | `%USERPROFILE%\.agent-desktop`，与 Electron 安装目录无包含关系 |

`appId` 是升级识别主键，一经发布不得因产品展示名变化而改变。内部暂用 `dev.agentdesktop.desktop`；生产发布前由项目负责人确认正式反向域名。`productName` 未确认前保持 `agent-desktop`，不得使用“Codex”等第三方产品名。

### 2.2 安装资源布局

```text
<install>/
  agent-desktop.exe
  resources/
    app.asar
      desktop-shell/dist/**
      desktop-ui/dist/**
      package.json
    agent-host.jar
    jre/
      bin/javaw.exe
      bin/server/jvm.dll
      legal/**
      release
    skills/<builtin-skill>/**
    release-manifest.json
    THIRD_PARTY_NOTICES.txt
```

以下内容绝不能进入安装目录：SQLite、日志、备份、Provider/MCP 密钥密文、用户 Skills、导入 staging、用户 workspace 或运行时 session token。

### 2.3 Main 路径解析

新增 `AppPaths`，禁止各模块自行拼路径：

```ts
type AppPaths = {
  packaged: boolean;
  installRoot: string;
  rendererIndex: string;
  hostJar: string;
  javaExecutable: string;
  builtinSkills: string;
  releaseManifest: string;
  appHome: string;
};
```

- 开发态可接受现有显式环境变量，但仅在 `!app.isPackaged` 或 `AGENT_DESKTOP_SMOKE=1` 时生效。
- 安装态忽略 `AGENT_DESKTOP_JAVA`、`AGENT_DESKTOP_HOST_JAR`、`AGENT_DESKTOP_BUILTIN_SKILLS` 和任意 renderer override，防止普通环境变量替换可信安装资源。
- `rendererIndex` 从 `app.getAppPath()/desktop-ui/dist/index.html` 解析；不再依赖仓库的 `../..` 层级。
- `hostJar`、JRE、Skills 和 manifest 必须在 `process.resourcesPath` 下，real path 不得逃逸安装资源根。
- Main 启动 Host 时增加固定 `-Dagentdesktop.install-dir=<resources real path>` 和 `-Dagentdesktop.builtin-skills=<skills real path>`，供 W6 保护目录校验；值来自应用自身而非 Renderer/模型。

## 3. 精简 Java 21 runtime

### 3.1 构建方式

1. CI/开发机使用锁定的 Windows x64 JDK 21 发行版作为构建输入；JDK 下载 URL、版本、vendor 和 SHA-256 记录在 `packaging/runtime-lock.json`。
2. `mvn -pl agent-host -am clean package` 先生成可执行 fat JAR。
3. 使用 `jdeps --multi-release 21 --ignore-missing-deps --print-module-deps` 分析 Host，再与人工维护的 `packaging/jre-modules.txt` 合并。
4. `jlink --strip-debug --no-header-files --no-man-pages --compress=zip-6` 生成 runtime image。
5. 运行包内 `java.exe --list-modules`、`javaw.exe -jar agent-host.jar` bootstrap smoke，避免只依据静态模块分析。

模块清单必须显式评审。首版至少验证 `java.base`、`java.logging`、`java.management`、`java.naming`、`java.net.http`、`java.sql`、`java.xml`、`jdk.crypto.ec`、`jdk.unsupported`；不得为了省事打入完整开发 JDK，也不得在客户机动态下载缺失模块。

### 3.2 runtime 安全要求

- 只打包运行时，不包含 `javac`、`jar`、`jlink`、源码和构建缓存。
- Electron 固定启动包内 `javaw.exe`，不搜索 `PATH`，不回退系统 Java。
- Host JAR、runtime `release` 文件和关键二进制 hash 写入 release manifest。
- runtime 许可证和第三方 notice 随包发布。
- 构建脚本发现 vendor/version/hash 不一致即失败，不自动接受新 JDK。

## 4. 版本、兼容性与 release manifest

### 4.1 单一版本源

根 `package.json.version` 是应用发布版本，采用 SemVer。构建前脚本校验以下值一致：

- 根 npm version、Electron artifact version、Host `Implementation-Version`。
- release manifest `appVersion`/`hostVersion`。
- `protocolVersion`、`snapshotSchemaMax`、`dbSchemaMax` 是独立兼容版本，不能用应用版本替代。

SNAPSHOT 可以用于开发构建，但 NSIS production channel 只接受无 prerelease 的 SemVer 和干净、可追溯的源码 revision。

### 4.2 release manifest

`resources/release-manifest.json` 为构建生成文件：

```json
{
  "schemaVersion": 1,
  "appId": "dev.agentdesktop.desktop",
  "appVersion": "0.1.0",
  "hostVersion": "0.1.0",
  "platform": "win32",
  "arch": "x64",
  "protocolMin": 1,
  "protocolMax": 1,
  "snapshotSchemaMax": 2,
  "dbSchemaMax": 7,
  "jdk": { "feature": 21, "vendor": "<locked>", "runtimeHash": "sha256:<hex>" },
  "artifacts": {
    "agent-host.jar": "sha256:<hex>",
    "skills": "sha256:<canonical-tree-hash>"
  },
  "sourceRevision": "<revision-or-source-archive-id>",
  "channel": "internal|production",
  "unsigned": true
}
```

Main 在创建窗口和启动 Host 前完成 schema/platform/arch/path/hash 校验。manifest 不含构建机绝对路径、用户名、时间敏感随机值或密钥。`unsigned=true` 只允许 internal channel。

### 4.3 兼容规则

| 场景 | 行为 |
| --- | --- |
| manifest schema 未知 | Main fail closed，显示 `RELEASE_MANIFEST_UNSUPPORTED` |
| Host 协议不在 Main min/max | 停止 Host，`HOST_PROTOCOL_INCOMPATIBLE` |
| 数据库 schema 高于 Host max | 不打开业务 Repository，`DATABASE_TOO_NEW` |
| 数据库 schema 低于 Host max | 先备份，再由 Flyway 前向迁移 |
| snapshot schema 高于 Renderer max | 不猜测渲染，显示升级诊断 |
| 尝试安装更低应用版本 | 安装器可替换程序，但 Host 仍按 DB max 拒绝危险降级；不自动恢复旧备份 |

## 5. 构建与签名流水线

### 5.1 构建顺序

```text
toolchain/lock check
  -> mvn clean verify
  -> npm ci
  -> npm run typecheck
  -> npm run build
  -> package Host JAR
  -> jdeps + jlink + packaged Host smoke
  -> generate release manifest/notices
  -> electron-builder --win nsis --x64
  -> Authenticode sign (production)
  -> verify signatures/hashes
  -> unpacked smoke
  -> installer acceptance
```

新增命令：

```powershell
npm run package:win
powershell -ExecutionPolicy Bypass -File .\scripts\build-week7-package.ps1 -Channel internal
powershell -ExecutionPolicy Bypass -File .\scripts\verify-week7.ps1 -PreviousInstaller <approved-baseline.exe>
```

构建产物统一写入 `dist-release/`，不得覆盖源码或 `appHome`。脚本先校验输出目录位于仓库 `dist-release` 下，再清理本脚本拥有的旧产物。

### 5.2 electron-builder 固定项

- `asar=true`，Renderer/Main 源码映射不随 production 包发布。
- `extraResources` 只包含 Host JAR、jre、builtin Skills、manifest、notice。
- `files` 使用明确 allowlist；排除 tests、fixtures、docs、`.git`、`.env*`、构建缓存和本地配置。
- `npmRebuild=false` 仅在确认无 native dependency 时使用；未来加入 native 模块必须恢复目标架构 rebuild。
- NSIS `oneClick=false`、`perMachine=false`、`allowElevation=false`、`deleteAppDataOnUninstall=false`。
- artifact 名固定包含产品、SemVer、Windows、x64；不包含分支名、用户名或绝对路径。

### 5.3 签名

production channel 必须：

1. 安装器和主 EXE 使用受信 Authenticode 证书及时间戳服务签名。
2. 签名密钥只从 CI secret/certificate store 提供，不写仓库、命令输出或 artifact。
3. `Get-AuthenticodeSignature` 状态为 Valid；安装器 SHA-256 与发布清单一致。
4. 签名失败不生成 production 发布结果。

当前仍缺正式产品名、appId/publisher、`.ico`、隐私/EULA 文本和签名证书。它们不阻塞本详细设计，但阻塞“production signed”验收；内部包必须使用独立 channel 与视觉标识。

## 6. 首次安装与启动

### 6.1 首次启动顺序

1. Main 取得单实例锁并固定 `appHome`/Electron `userData`。
2. 校验 release manifest 和安装资源 hash。
3. 原子写入 `run/desktop-session.json`，状态为 `STARTING`，只含 sessionId、版本、pid、startedAt 和上次清洁退出标志。
4. Host 在任何业务 Repository 打开前执行数据库兼容检查和必要备份。
5. Flyway migrate；复用既有默认本地用户、workspace、file root 初始化。
6. Host 输出 ready，Main 完成 loopback health/protocol 校验。
7. session marker 更新为 `READY`，创建 Renderer；Renderer 从 Host Snapshot 加载，而不是信任本地缓存。

目录创建必须使用当前用户 ACL。应用不放宽整个 `appHome` 权限，不向 Everyone 写权限，也不把目录设为网络共享。

### 6.2 启动错误

错误只向 UI 返回稳定码与诊断 ID：

- `RELEASE_MANIFEST_INVALID`
- `INSTALL_RESOURCE_MISSING`
- `INSTALL_RESOURCE_TAMPERED`
- `RUNTIME_INCOMPATIBLE`
- `DATABASE_TOO_NEW`
- `DATABASE_BACKUP_FAILED`
- `DATABASE_MIGRATION_FAILED`
- `HOST_PROTOCOL_INCOMPATIBLE`
- `HOST_CRASH_LOOP`

详细日志仅写 appHome 的有界脱敏日志，不展示 token、bootstrap JSON、绝对私有路径、SQL 内容或异常对象全量 dump。

## 7. 显式升级设计

### 7.1 用户流程

```text
用户获得并验证新安装包
  -> 显式运行安装器
  -> 安装器发现旧实例/互斥锁
  -> 请求用户正常退出应用
  -> 替换当前用户安装目录
  -> 保留 appHome 与 safeStorage userData
  -> 用户启动新版本
  -> 兼容检查 + 数据库备份 + Flyway
  -> Host ready 后标记升级成功
```

安装器不强杀 Host/Java/MCP，不在应用运行中替换 JAR/JRE/asar。用户拒绝关闭时取消安装，不进行部分升级。

### 7.2 数据库升级协调器

在 `LocalDatabase.migrate()` 外增加 `DatabaseUpgradeCoordinator`：

1. 读取只读 release state 与 Flyway schema history；新数据库无需备份。
2. 若 schema 大于 manifest `dbSchemaMax`，返回 `DATABASE_TOO_NEW`。
3. 若 schema 小于目标版本，在没有其他实例且数据库可独占打开时执行 `PRAGMA integrity_check`。
4. 使用 SQLite `VACUUM INTO` 在 `appHome/backups/<version>-<id>/agent.db` 生成一致性备份；目标路径由应用生成并验证处于 backups 根内。
5. 计算备份 SHA-256，原子写 `backup-manifest.json`，包含 from/to app version、from/to schema、hash、size 和创建时间；不含 workspace 路径或用户内容。
6. 执行 Flyway validate/migrate。migration 只允许前向追加，已发布 migration 不修改、不重排。
7. 成功打开 Repository、完成启动对账和 health 后，原子写 `data/release-state.json` 的 `lastSuccessfulVersion/schema/at`。
8. 只在一次完整成功启动之后按“最近 3 个成功升级备份”清理更旧备份；清理目标必须逐个验证处于 backups 根内。

若 integrity、backup 或 migration 任一步失败：Host 不 ready，原数据库和备份保持可诊断状态，不自动恢复、不继续启动。恢复备份是显式维护动作，需先复制当前故障数据库并关闭所有进程；不由安装器隐式执行。

### 7.3 升级保持矩阵

| 数据 | 位置 | 升级 | 正常卸载 |
| --- | --- | --- | --- |
| Thread/Turn/Item/Audit | `appHome/data/agent.db` | 保留并迁移 | 保留 |
| 用户/工作区 Skills | `appHome/skills`、workspace | 保留 | 保留 |
| Skill staging/work/backup | `appHome` 受控目录 | 保留；启动清理未完成事务 | 保留 |
| MCP/Provider 密钥密文 | Electron `userData`/safeStorage | 保留 | 保留 |
| MCP 配置、文件根 | SQLite | 保留并迁移 | 保留 |
| 内置 Skills | 安装资源 | 由新版本替换 | 删除 |
| Host/JRE/Renderer | 安装资源 | 原子替换 | 删除 |
| thread grant/session token | 内存 | 不保留 | 不保留 |

## 8. 崩溃恢复

### 8.1 恢复不变量

1. Repository 事务提交成功的 Item 必须可恢复；尚未提交的内存 delta 不承诺恢复。
2. 任意异常退出后，同一 Turn 不得自动重新调用 Provider 或 Tool。
3. 运行中 Turn 启动对账为 `INTERRUPTED`；等待审批沿用 W6 的 `CANCELLED/APPROVAL_INVALIDATED`；已终态 Turn 不改写。
4. Approval pending handle、ALLOW_THREAD、ExecutionPermit、Provider stream、MCP invocation 和脚本进程句柄均不持久化为可续跑能力。
5. Tool 结果只有已经作为 Item 提交时才展示；不能因“可能执行过但结果未提交”而自动重放。
6. 恢复后的用户动作是“新建重试 Turn”，不是复用旧 turnId/callId/approvalId。

### 8.2 Host 运行期崩溃

`HostSupervisor` 增加运行期状态机：

```text
stopped -> starting -> ready
starting -> failed
ready -> restarting (unexpected exit, at most once)
restarting -> ready | crash-loop
ready -> stopping -> stopped (normal quit)
```

- 只有 `ready` 后非预期退出才计入运行期崩溃；正常 shutdown 不触发重启。
- Main 立即结束所有活动 SSE 转发并向 Renderer 发布 `host:state=RESTARTING`。
- 等待 500 ms 后只重启一次，总预算 15 秒；新 Host 必须使用新 session token、新 nonce 和相同可信安装资源。
- 新 Host 启动时执行现有 unfinished Turn 对账；ready 后 Renderer 重新请求 thread list/snapshot。
- 10 分钟窗口内再次非预期退出进入 `CRASH_LOOP`，不继续自动重启；用户只能显式重启应用并查看诊断编号。

### 8.3 Electron Main 异常退出

Host 增加 `ParentLivenessMonitor`：Main 保持 bootstrap stdin 管道打开；正常退出先调用 shutdown，再关闭管道。若 Main 被终止导致 stdin EOF：

1. Host 停止接受新 Turn/Approval resolve。
2. 请求取消活动 Provider/MCP/脚本，等待既有有界期限。
3. 对尚未终态 Turn 使用既有安全终态/下次启动对账。
4. 关闭 MCP 子进程和 SQLite，随后 Host 自行退出。

不得按进程名扫描或终止其他 Java/MCP 进程。若操作系统强制同时终止所有进程，下次启动仍由数据库对账兜底。

### 8.4 Renderer 崩溃或 reload

- Main/Host 保持运行，记录无 URL/DOM/输入内容的 `RENDERER_GONE` 诊断。
- Main 最多自动重建一次 BrowserWindow；新 Renderer 从 Host Snapshot 恢复会话和 Approval Card。
- 旧 Renderer sender 立即失去 IPC 权限；任何旧回调发送前检查 `webContents.isDestroyed()`。
- pending Approval 只有 Host 仍持有 handle 时可继续操作；否则 Snapshot 必须显示 INVALIDATED，不能重新启用按钮。
- 第二次 renderer crash 显示本地 crash 页面，不形成无限 reload loop。

### 8.5 崩溃标记与日志

`desktop-session.json` 和 `release-state.json` 均采用临时文件 + flush + atomic move；内容不含 message、Tool 参数、路径、token 或密钥。正常退出在 Host 完全停止后写 `CLEAN_EXIT`。下次启动发现 `READY/STARTING` 未清洁结束只形成诊断，不据此自动重放工作。

日志采用按大小轮转，总量有界；production 默认不生成进程内存 dump，因为 dump 可能包含 API Key、文件正文或 MCP 返回。若将来提供诊断导出，必须独立做脱敏和用户确认，本周不上传任何数据。

## 9. 第 6 周安全边界在安装态的继承

1. 安装资源根、包内 JRE、Host JAR 和 builtin Skills 自动加入 `FILE_ROOT_PROTECTED`；用户不能把其本身或上级目录登记为普通文件根。
2. Main 安装态不接受环境变量替换 executable/JAR/Skill 根，Renderer 无路径参数 IPC。
3. Host 重启后所有 `ALLOW_THREAD` 清空；pending Approval 原子失效；once permit 不复活。
4. 崩溃恢复不得绕过 ToolGateway 调用 MCP、文件或脚本；恢复代码没有 Tool 执行权限。
5. 升级时 builtin Skill hash 变化会使旧激活/授权 stale；用户 Skills 保留但仍按 W4～W6 重新发现和校验。
6. MCP 自动连接只恢复配置/连接，不恢复或重放 Tool call；凭据仍由 Main safeStorage 临时注入内存。
7. 备份数据库与安装日志视为敏感本地数据，禁止打包进安装器或诊断输出。

## 10. UI 与 IPC 契约

### 10.1 新增只读 IPC

```ts
getReleaseInfo(): Promise<{
  appVersion: string;
  hostVersion: string;
  channel: 'internal' | 'production';
  signed: boolean;
  platform: 'win32';
  arch: 'x64';
}>;

getRecoveryState(): Promise<{
  hostState: 'STARTING' | 'READY' | 'RESTARTING' | 'CRASH_LOOP' | 'FAILED';
  recoveredFromUncleanExit: boolean;
  diagnosticId?: string;
}>;
```

Renderer 不能请求安装、下载更新、选择任意 executable、读取 manifest 原文或获得安装绝对路径。本周不新增 `installUpdate()`。

### 10.2 UI 行为

- 设置/关于页显示版本、channel、签名状态和“升级需显式运行可信安装包”的说明。
- Host 重启期间禁用发送、审批和设置变更，保留历史只读展示。
- 恢复成功后提示“已恢复已保存内容；未完成操作未自动继续”，并刷新活动会话。
- crash-loop/migration failure 展示稳定错误码、诊断 ID、重启/退出按钮；不展示 stack trace 或绝对路径。
- 不提供“删除所有本地数据”卸载快捷入口。

## 11. 实施工作包

| 工作包 | 主要改动 | 完成门 |
| --- | --- | --- |
| W7-A 发布身份 | 统一版本、appId/productName/channel、manifest schema | 版本不一致构建失败 |
| W7-B 安装态路径 | `AppPaths`、asar Renderer、resources real path/hash | unpacked/installed 均启动 |
| W7-C JRE image | lock、jdeps/jlink、runtime smoke、licenses | 无系统 Java可启动 |
| W7-D NSIS | electron-builder allowlist、当前用户、卸载保留数据 | 安装/升级/卸载通过 |
| W7-E 升级协调 | DB 兼容、backup manifest、Flyway、release state | 成功升级与失败锁定通过 |
| W7-F 崩溃恢复 | Supervisor 状态机、stdin EOF、session marker、快照重载 | 三类崩溃通过 |
| W7-G UI/IPC | 版本、恢复提示、crash-loop | 生产 Renderer smoke 通过 |
| W7-H 发布安全 | 签名、hash、日志/包内容扫描 | internal/prod channel 隔离 |
| W7-I 自动化 | package/install/upgrade/crash/uninstall/W6 regression | `verify-week7.ps1` 返回 0 |

依赖顺序：`W7-A -> W7-B/W7-C -> W7-D -> W7-E/W7-F -> W7-G/W7-H -> W7-I`。数据库备份和崩溃不重放测试完成前，不得把安装器标记为可升级基线。

## 12. 测试设计

### 12.1 Java

1. 空目录首次 migrate；V1～V7 fixture 升级；schema 高于 max 拒绝。
2. 升级前 `integrity_check`、`VACUUM INTO`、hash/size manifest 和备份根路径约束。
3. migration 成功才更新 release state；故意失败时 Host 不 ready且备份未改变。
4. retention 只删除应用拥有且已验证位于 backups 根内的旧备份。
5. RUNNING/WAITING_APPROVAL/CANCELLING 在异常启动对账后各自稳定终态；已完成事件和 sequence 不变。
6. crash-after-commit-before-delivery：Snapshot 含已提交 Item；同一 Tool call 不再执行。
7. Parent stdin EOF 触发有界 shutdown，不按名称杀进程。
8. W6 Approval/thread grant/ExecutionPermit 在 Host 重启后全部 fail closed。

### 12.2 Electron/Renderer

1. packaged `AppPaths` 不读取开发 override，不逃逸 `resourcesPath`。
2. manifest 缺失、schema 错误、JAR/Skills hash 修改均拒绝启动 Host。
3. 单实例、正常退出不重启、Host 异常退出只重启一次、重复退出进入 crash-loop。
4. renderer crash/reload 后旧 sender 失效，窗口最多重建一次，快照与 pending Approval 状态正确。
5. About/Recovery UI 只显示允许字段，无安装路径或 stack trace。
6. preload 不出现 generic shell/fs/process/update API。

### 12.3 安装、升级与卸载

真实安装验收必须在 Windows Sandbox、一次性 VM 或专用当前用户下执行，不能污染开发者正式 `appHome`：

1. 断网、PATH 不含 Java/Node；静默检查安装器不请求 elevation，交互安装到当前用户目录。
2. 首启完成 Renderer→Host 会话，写入 thread、Skill、MCP 配置、根目录和密文哨兵。
3. 异常终止 Host：验证重启一次、旧 Turn 中断、已提交 Item 保留、副作用计数不增加。
4. 异常终止 Electron：验证 Host 因 stdin EOF 退出；再启动恢复快照且不续跑。
5. 安装 `-PreviousInstaller` 后再安装当前包，验证 backup、Flyway、release state 和全部保持矩阵。
6. 运行卸载器，确认安装目录/快捷方式移除而隔离 `appHome` 哨兵和外部文件根内容存在。
7. 重新安装当前版本，原会话可加载，密钥仍为 safeStorage 密文且不出现在扫描结果。

previous installer 必须是已通过门禁的受信基线，参数必须给出明确文件路径与期望 SHA-256；脚本不从网络临时下载“上一版”。若本地只做开发验证，可使用 V1～V7 数据 fixture 验证 migration，但不能替代发布门禁的真实 N→N+1 安装测试。

## 13. `verify-week7.ps1`

脚本分两层：

### 13.1 每次提交必跑

```text
1. 校验 Java/Maven/Node/npm/electron-builder/runtime lock。
2. mvn clean verify；npm ci；npm typecheck；npm build。
3. 构建 Host/JRE/manifest/NSIS 与 unpacked 目录。
4. 扫描包内容 allowlist、source map、.env、测试 fixture、secret 和绝对构建路径。
5. 运行包内 javaw + Host bootstrap smoke、真实 unpacked Renderer smoke。
6. 注入 Host/Renderer/Main 崩溃，验证状态机、快照和零重放。
7. 校验 manifest/hash；production 额外校验 Authenticode。
8. 执行 verify-week6.ps1 完整回归。
```

### 13.2 发布候选必跑

```text
1. 在一次性 Windows 当前用户安装 previous installer。
2. 生成 W1～W6 持久化状态与敏感哨兵。
3. 安装 current installer，完成备份/migration/启动。
4. 核对会话、Skills、MCP、Roots、safeStorage 与授权失效。
5. 卸载 current，确认 appHome/外部 roots 未删除。
6. 重新安装 current，确认数据仍可恢复。
7. 输出不含私有路径/secret 的 JSON 验收摘要和 artifact hashes。
```

脚本任何跳过参数都必须在摘要标注。`-SkipInstallAcceptance` 可用于普通开发迭代，但带该标记的结果不能满足 W7-A02/A05/A09/A12，也不能把第 7 周标记完成。

## 14. 验收矩阵

| 编号 | 验收主题 | 通过条件 |
| --- | --- | --- |
| W7-A01 | 可重复发布物 | NSIS/unpacked/manifest/hash 从干净 checkout 一键生成，版本一致 |
| W7-A02 | 当前用户安装 | 无管理员、无系统 Java/Node、断网可安装首启 |
| W7-A03 | 包内运行时 | 只使用可信包内 JRE/Host/Skills/Renderer，篡改 fail closed |
| W7-A04 | 首次启动 | 默认 Profile/workspace 建立，生产 Renderer 会话成功 |
| W7-A05 | 显式升级 | 真实 N→N+1 保留全部用户状态，安装资源正确替换 |
| W7-A06 | 数据迁移 | 一致备份、Flyway、release state 和失败锁定均可验证 |
| W7-A07 | 崩溃恢复 | Host/Main/Renderer 异常后已持久化事件恢复，状态明确 |
| W7-A08 | 零重放 | Turn/Approval/Tool/MCP/脚本不自动续跑，grant/permit 失效 |
| W7-A09 | 安全卸载 | 安装资源删除，appHome/workspace/外部 roots 不删除 |
| W7-A10 | 发布完整性 | manifest/hash/channel 有效；production 签名有效 |
| W7-A11 | 隐私诊断 | 包、日志、标记、备份 manifest、注册表无 secret/正文/私有路径/reasoning |
| W7-A12 | 自动化回归 | Week 7 全门禁与 Week 6 完整回归一键返回 0 |

## 15. 风险与决策

| 风险 | 控制 |
| --- | --- |
| jdeps 漏掉反射加载模块 | 静态分析 + 人工模块清单 + 包内 Host 真实 smoke |
| asar 路径与开发路径不同 | 集中 `AppPaths`，unpacked/installed 双态测试 |
| 安装中强退形成混合版本 | 安装前互斥/正常退出；manifest 版本与 hash fail closed |
| Flyway 成功一半或磁盘满 | 迁移前一致备份、空间检查、失败不 ready、不自动覆盖 |
| Tool 已产生副作用但结果未提交 | 不自动重放；旧 Turn 中断，用户决定是否新建重试 |
| Main 崩溃遗留 Host/MCP | stdin EOF 父存活监测 + 下次启动对账 |
| 自动重启形成 crash loop | 只重启一次，窗口计数，随后显式失败态 |
| 卸载误删用户文件 | `deleteAppDataOnUninstall=false`，NSIS 无 appHome 删除逻辑，哨兵验收 |
| 签名材料未提供 | internal unsigned channel 可开发；production 门禁硬失败 |
| 旧版本读取新 schema | manifest/db max 兼容检查，拒绝危险降级 |

## 16. Definition of Done

- Windows x64 当前用户安装包可在干净、断网、无全局 Java/Node 环境安装并完成生产会话。
- 用户显式运行新安装包可以完成真实 N→N+1 升级，数据库先备份再迁移，全部用户数据与密文保持。
- Host、Main、Renderer 异常退出后，所有已持久化事件可恢复；未完成 Turn 安全终止，任何外部副作用不自动重放。
- 正常卸载不删除 `appHome`、workspace 或用户选择的文件根内容；重新安装可恢复原会话。
- 安装态继承第 1～6 周全部安全边界，资源篡改、版本不兼容和迁移失败均 fail closed。
- internal/production channel、签名、manifest、hash、日志和备份均可审计且不泄露敏感内容。
- `verify-week7.ps1` 的提交门禁、发布候选门禁和 `verify-week6.ps1` 完整回归全部返回 0。
- 不实现后台自动升级、无人值守安装、自动续跑或第 8～9 周兼容性/长稳范围。

通过 W7-A01～W7-A12 后，才进入第 8～9 周兼容性、稳定性与内测。本周产出的安装基线、previous installer、release manifest、数据库备份和崩溃注入夹具将成为后续版本的强制升级回归资产。
