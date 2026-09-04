# 阶段一实施状态

更新时间：2026-08-22

## 第 1 周验收结论

**已完成。** 对照《一期整体方案设计》“Electron 启动 Host，AG-UI 流式聊天”的交付要求，当前实现已能稳定完成流式会话并展示 Host/运行状态，可以进入第 2 周详细设计与交付。

| 第 1 周要求 | 实现与证据 | 结论 |
| --- | --- | --- |
| Electron 启动 Java Host | Main 以 stdin bootstrap 启动 Host，校验结构化 ready 与 health；单次等待 10 秒，失败后自动重试一次 | 通过 |
| 本地访问安全 | Host 仅监听随机 loopback 端口；Bearer token、`X-App-Protocol` 与 loopback 来源均校验 | 通过 |
| AG-UI 流式聊天 | Mock Provider 产生增量事件，Host 通过 SSE 输出，Main 转发，React reducer 逐步渲染 | 通过 |
| 运行状态展示 | Renderer 展示 Host 状态、当前运行/完成/失败状态，并支持停止操作 | 通过 |
| 稳定完成一次会话 | Electron 生产构建端到端冒烟连续执行两轮，均返回 0 | 通过 |
| 会话数据落盘 | 多轮冒烟均写入 Thread/Turn/Item，抽查最近 Turn 状态为 `COMPLETED`，且包含完整结束事件 | 通过 |

## 本周完成的实现

- Java Host：bootstrap/ready/health、随机 loopback 端口、协议版本与令牌鉴权、优雅关闭。
- Electron Main：Host 生命周期管理、冷启动超时重试、SSE 转发、白名单 IPC、退出时回收子进程。
- Electron Preload：`sandbox: true`、`contextIsolation: true`，仅暴露经过约束的 `agentDesktop` API。
- React Renderer：Host 状态、消息输入、流式消息、完成/错误/取消状态及停止操作。
- AG-UI：稳定事件 envelope、严格递增 sequence、Mock Provider 增量输出和结束事件。
- SQLite/Flyway：本地用户、默认 workspace、Thread/Turn/Item 写入与时间线查询；该部分同时为第 2 周提供数据基础。
- 生产资源加载：Vite 使用相对资源基址，保证 `file://` 下可正确加载 Renderer。
- 安全测试：错误 token、错误协议版本和非 loopback 请求均被拒绝；日志执行基础脱敏。

## 自动化验证结果

| 验证项 | 结果 |
| --- | --- |
| JDK / Maven | Java 21.0.10、Maven 3.9.16 |
| Maven `clean verify` | 通过，共 11 个测试 |
| Host 协议端到端脚本 | 通过：bootstrap、鉴权、SSE、持久化、关闭 |
| TypeScript 类型检查 | 通过 |
| React/Vite 与 Electron 生产构建 | 通过 |
| Electron 桌面端冒烟 | 连续两轮通过：Renderer、preload IPC、Host SSE、React 展示 |
| 仓库 API Key 扫描 | 通过，无明文 Key 命中 |

## 验证命令

```powershell
# Java 单元/集成测试与 Host 协议闭环
mvn clean verify
powershell -ExecutionPolicy Bypass -File .\scripts\verify-phase1.ps1 -SkipBuild

# 桌面端生产构建与完整链路冒烟
npm run typecheck
npm run build
powershell -ExecutionPolicy Bypass -File .\scripts\verify-desktop-smoke.ps1 -SkipBuild
```

## 后续阶段边界

第 1 周只按总体方案完成 Host 启动和 AG-UI 流式聊天验收。Thread/Turn/Item 的基础持久化已经存在，但“停止后的快照恢复、应用重启后的会话与工具时间线恢复”仍应按第 2 周交付标准继续设计和验收；P1-D 官方 Provider、P1-E ReAct/Tool/审批以及 P1-F 安装包也不在本周完成声明内。

## 第 2 周验收结论

**已完成。** 对照《一期最终架构方案》和《第 2 周详细设计》，已完成 Thread/Turn/Item、停止与恢复交付，并通过完整自动化验收。

| 验收项 | 实现与证据 | 结论 |
| --- | --- | --- |
| W2-A01 会话管理 | 会话列表、打开、重命名、归档与恢复 API；Renderer 可切换活动/已归档会话 | 通过 |
| W2-A02 正常重启恢复 | 完成的会话通过一致 ThreadSnapshot 在独立 appHome 的重启后恢复 | 通过 |
| W2-A03 工具时间线恢复 | 仅验收环境启用的确定性 Tool Call/Result 夹具走 Provider→SSE→SQLite→快照路径，重启后验证名称、摘要和状态 | 通过 |
| W2-A04 停止 | 幂等取消、持久化 CANCELLING/CANCELLED，自动化检查在 10 秒内完成 | 通过 |
| W2-A05 意外中断 | 运行中终止 Host，下一次启动对账为 INTERRUPTED，已生成文本保留且不续跑 | 通过 |
| W2-A06 幂等与竞态 | Repository 事件重放、重复取消及单终态断言均已覆盖 | 通过 |
| W2-A07 安全边界 | Renderer 仅经白名单 IPC；快照隐藏 SYSTEM_AUDIT；临时 appHome 扫描无密钥、Authorization 或 reasoning | 通过 |
| W2-A08 自动化 | `verify-week2.ps1` 已完整通过 Maven、TypeScript、构建及三轮 Electron/Host 验收 | 通过 |

### 第 2 周验证命令

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\verify-week2.ps1
```

该脚本使用独立临时 `appHome`，依次验证：正常完成、工具时间线、取消、Host 异常中断、重启恢复和敏感信息扫描，不污染本地用户数据。

### 进入下一周的边界

第 2 周的工具事件仅为确定性恢复夹具，不连接真实 MCP。可以进入第 3 周，严格按总体方案实现 STDIO 与 Streamable HTTP MCP、Server 生命周期管理及完整 Tool Card；Skill 和审批仍分别保留给第 4、5 周。

## 第 3 周验收结论

**已完成。** 对照《第 3 周详细设计》，STDIO 与 Streamable HTTP MCP 已通过同一 Java Client Manager、Host API、Electron IPC 和 Tool Card 时间线闭环验收。

| 验收项 | 实现与证据 | 结论 |
| --- | --- | --- |
| W3-A01/A02 两类传输 | 官方 MCP Java SDK 2.0.0；本地 STDIO 与带 Bearer 的 Streamable HTTP fixture 均完成 initialize、tools/list 与 tools/call | 通过 |
| W3-A03 工具目录 | 稳定 `mcp:{serverId}:{toolName}` ID，按 Server 持久化目录并避免同名冲突 | 通过 |
| W3-A04 结构化调用 | Mock Provider 仅在验收开关下产生原生结构化 Tool Call；echo/sum 均通过 Schema 与执行门 | 通过 |
| W3-A05 Tool Card | 参数、Server、状态、耗时、结果摘要和错误码经 Item/AG-UI 按统一顺序展示，重启后由 Snapshot 恢复；错误码可复制 | 通过 |
| W3-A06 取消与故障 | 延迟 Tool 可在 2 秒内取消；401 不退避重试；5xx/断流有限重试；超时与 STDIO 进程退出形成唯一稳定终态，支持显式重连 | 通过 |
| W3-A07 安全 | STDIO 无 shell、工作目录受限、环境最小化；Bearer 只保存 safeStorage 密文；设置页实时校验并展示脱敏诊断；appHome 明文扫描无命中 | 通过 |
| W3-A08 周次边界 | 未知/高风险 Tool 返回 `TOOL_APPROVAL_REQUIRED`；未提前实现 Skill、OAuth 或审批 | 通过 |
| W3-A09 自动化 | Maven 29 个测试、TypeScript、桌面构建、两轮 Electron 重启、敏感扫描和第 2 周回归均通过 | 通过 |

### 第 3 周验证命令

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\verify-week3.ps1
```

验证脚本使用隔离 appHome 和 workspace，启动两类本地 MCP fixture，首次完成 echo/sum 与 Tool Card 持久化，第二次验证 Server 自动恢复和历史 Tool Card 恢复，并扫描明文 Bearer、Authorization、Cookie 与 reasoning。Windows 验收入口会规范化重复的 `PATH`/`Path`，Host 启动等待保持有界且可配置，以覆盖 Spring/Flyway 冷启动；Java 集成测试另覆盖 401、5xx、断流、Schema 失败、取消、调用超时、STDIO 进程退出和显式重连。

2026-08-13 稳定性复验修复了 STDIO 异常退出测试的请求发送竞态：fixture 在工具目录握手后自主异常退出，测试统一执行 Manager 清理；目标用例连续三次及全量 27 项测试均通过。Electron smoke 在 Host 优雅关闭后采用有界 HTTP 等待与验收进程退出兜底，快速双启动验收耗时由约 240 秒收敛到 106 秒。

2026-08-14 补齐 MCP Server 软删除闭环：设置页确认删除、preload/Main IPC、Host DELETE、Manager 断开、目录停用、Repository `deleted_at` 过滤和 safeStorage 密文清理均已完成；启动时额外清理异常退出遗留的孤立凭据。新增持久层与 MCP 删除回归后全量为 29 项测试，`verify-week3.ps1` 再次完整返回 0。生产配置已按产品要求允许显式 HTTP/HTTPS endpoint，文档同步修正。

### 进入下一周的边界

可以进入 [第 4 周 Skill 发现与渐进加载](WEEK_4_DETAILED_DESIGN.md)。MCP Tool 仍只允许确定性的 `DEMO_SAFE` 验收工具执行；未知或高风险能力继续稳定拒绝，直至顺延后的第 6 周 `PermissionEngine` 和人工审批交付。

## 第 4 周验收结论

**已完成。** 对照《第 4 周详细设计》，已完成固定根目录 Skill 发现、目录与激活持久化、显式/隐式激活、渐进式资源加载、AgentScope Harness 只读适配、设置页与会话时间线闭环。

| 验收项 | 实现与证据 | 结论 |
| --- | --- | --- |
| W4-A01 多 scope 发现 | builtin/user/workspace 三类隔离 fixture 全部发现；workspace > user > builtin、SHADOWED、无效高优先级不回退和刷新失败保留上一代均有自动化覆盖 | 通过 |
| W4-A02 规范兼容 | 覆盖必填字段、kebab-case、目录名一致、空 description、恶意 YAML 类型、超大正文、非法 UTF-8、可选字段和稳定错误码 | 通过 |
| W4-A03 管理 UI | 生产 Renderer 自动点击 scope 筛选、刷新、启停、正文与资源 viewer；HTML/script/link 均按纯文本处理，Renderer 只通过受限 IPC 访问 | 通过 |
| W4-A04 显式激活 | 稳定 Skill ID 随 Turn 提交，首个模型调用前加载正文；最多 5 个、Turn 快照冻结、发送后 chip 清空且历史摘要持久化 | 通过 |
| W4-A05 隐式激活 | 移除 Week 4 特殊 fixture ID；Mock Provider 只按公开 name/description 匹配并返回结构化 skillId，生产 Turn 每次模型调用均绑定 AgentScope 只读仓库 | 通过 |
| W4-A06 渐进资源 | 激活不加载资源；Provider 依据正常请求选择 resourceId，仅装载所需引用；覆盖二进制、超大、深层、stale、错误 ID 与 1 MiB 聚合预算 | 通过 |
| W4-A07 安全边界 | 覆盖 junction/symlink、`..`、Windows 保留名、目录逃逸、脚本候选、secret 与 allowed-tools 不授权；正文/资源不进入事件、SQLite 或日志 | 通过 |
| W4-A08 周次边界 | AgentScope Harness 禁用文件系统、Shell、Memory、Session、Workspace 和 Subagent；未引入审批、市场、远程安装、自演化或多 Agent | 通过 |
| W4-A09 自动化 | Maven 42 项测试、TypeScript、生产构建、两轮真实 Renderer/Host、重启恢复、敏感内容扫描及第 3 周完整回归均由 `verify-week4.ps1` 一键返回 0 | 通过 |

### 第 4 周验证命令

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\verify-week4.ps1
```

验证脚本使用隔离 appHome、workspace 和三 scope Skill fixtures，覆盖三层优先级、真实 Renderer 筛选/刷新/启停/viewer、显式/隐式激活、按需资源读取、脚本不执行、正文不落盘及重启恢复，并串行执行第 3 周完整回归。2026-08-17 最终无跳过参数执行返回 0。

### 进入下一周的边界

[第 5 周 Skill 导入与安装详细设计](WEEK_5_DETAILED_DESIGN.md) 已形成，评审后可开工。第 4 周仅完成 Skill 指令和只读资源的安全装载；第 5 周只增加用户确认的受控导入，不执行 Skill 中的脚本，也不放宽未知或高风险 MCP Tool 的拒绝策略。PermissionEngine 与人工审批顺延至第 6 周。

## 第 5 周验收结论

**已完成。** 本地目录和公开 GitHub Skill 已统一进入受控 staging、共享 W4 校验器和原子 USER 安装事务；导入、启用与 Tool 授权继续保持隔离。

| 验收项 | 实现与证据 | 结论 |
| --- | --- | --- |
| W5-A01 本地目录 | Main 原生选择器无参数 IPC；Renderer 不接收路径；Host 复制前后校验类型、大小、时间与 hash | 通过 |
| W5-A02 GitHub | 仅接受 `https://github.com/owner/repo`，只请求 Contents/commit API，浮动 ref 固定为 40 位 commit | 通过 |
| W5-A03/A04 校验与预览 | W4/W5 共用无数据库副作用的 `SkillPackageValidator`；预览包含来源、hash、资源、脚本、依赖、warning 与冲突 | 通过 |
| W5-A05 原子安装 | staging 与 USER 根隔离，同卷 `ATOMIC_MOVE`，新安装/替换/回滚强制停用，失败恢复旧目录 | 通过 |
| W5-A06 来源追踪 | V6 保存可信 source、commit、subdirectory、package/content hash 与 revision；不保存本地绝对路径或正文 | 通过 |
| W5-A07 管理 | expected content hash 防竞态；支持同包 no-op、替换、最近版本回滚和受管 USER 删除 | 通过 |
| W5-A08 安全边界 | 无 archive/任意 URL/脚本执行/MCP 自动安装或授权；链接、逃逸、大小写碰撞、secret 与容量限制稳定拒绝 | 通过 |
| W5-A09 扩展兼容 | `agents/openai.yaml` 只生成 MCP readiness 提示；`_user_meta.json` 保持普通资源身份 | 通过 |
| W5-A10 自动化 | 48 项 Java 测试、TypeScript、生产构建、两轮 Week 5 Electron、重启/删除/泄漏扫描及 W4→W3 回归均返回 0 | 通过 |

### 第 5 周验证命令

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\verify-week5.ps1
```

2026-08-20 完整执行通过。脚本使用隔离 appHome 和脱敏 fixture，验证本地安装、替换、回滚、重启、Renderer 管理页、受管删除、外部来源保留、脚本不执行、敏感内容不落盘，并串行执行 Week 4 与 Week 3 回归。公开 GitHub 路径由本地 fake API 单元测试覆盖，不消耗真实 GitHub rate limit。

### 进入下一周的边界

[第 6 周 PermissionEngine、审批卡与文件根目录控制详细设计](WEEK_6_DETAILED_DESIGN.md) 已形成，评审后可开工。第 5 周的安装来源、脚本候选、`allowed-tools` 与 MCP 依赖状态仍只是第 6 周风险说明和执行前重验输入，不构成任何预授权；第 6 周尚未进入实现完成声明。

## 第 6 周验收结论

**已完成。** 对照《第 6 周详细设计》，MCP、工作区文件和已激活 Skill 脚本已统一进入 `ToolGateway → PermissionEngine → ApprovalCoordinator`，高风险副作用可在执行前暂停，并以一次允许、当前会话允许或拒绝三种决定安全恢复。

| 验收项 | 实现与证据 | 结论 |
| --- | --- | --- |
| W6-A01 统一权限入口 | MCP、`workspace.read_text/write_text` 与 `skill.run_script` 均先生成应用可信执行计划和权限请求；Manager、preload 与 Renderer 无直接执行旁路 | 通过 |
| W6-A02 ASK 原子暂停 | V7、Approval/Turn/Item/Audit 同事务提交；提交后发布 `approval.required`，Turn 进入 `WAITING_APPROVAL` | 通过 |
| W6-A03 三种决定 | `ALLOW_ONCE` permit 单次消费；`ALLOW_THREAD` 仅驻留 Host 内存；`DENY` 返回结构化 Tool Result；重复决定和冲突决定幂等/拒绝 | 通过 |
| W6-A04 授权作用域 | invocation 与 thread grant 绑定 Thread、能力、subject、resource、参数、root/catalog/script hash；跨 Thread、配置变化及 Host 重启失效 | 通过 |
| W6-A05 超时/取消/恢复 | 固定 5 分钟审批 TTL；停止、脚本超时、Host 异常退出和重启均 fail closed；重启原子追加 `INVALIDATED` 历史事件和审计，不续跑副作用 | 通过 |
| W6-A06 Approval Card | 真实生产 Renderer 展示可信风险、来源、目标、倒计时和三种决定；无危险默认焦点；实时事件与 Snapshot 使用同一 schema | 通过 |
| W6-A07 根目录管理 | 默认 workspace 根、原生目录选择器、masked DTO、READ_ONLY/READ_WRITE、版本化启停/删除和 appHome 保护规则闭环 | 通过 |
| W6-A08 文件边界 | 仅接受 rootId+协议相对路径；拒绝绝对/遍历/ADS/保留名/链接与非普通文件；UTF-8/1 MiB/overwrite/原子 move/identity 重验生效 | 通过 |
| W6-A09 Skill 脚本 | 仅当前 Turn 已激活、VALID、hash 一致的 `.ps1` resource；固定系统 PowerShell、参数数组、最小环境、30 秒/512 KiB 上限和进程树终止 | 通过 |
| W6-A10 MCP 迁移 | Manager 只保留可用性、schema、取消和超时校验；UNKNOWN MCP 进入 ASK，批准前零副作用，目录/配置变化使旧批准 stale | 通过 |
| W6-A11 安全审计 | ASK/决定/失效、策略自动允许、硬拒绝及 root 变更均以脱敏 fingerprint 审计；Approval、Item 与 Tool Card 不保存正文、原始参数、stderr、环境值或 reasoning | 通过 |
| W6-A12 自动化 | 64 项 Java 测试、TypeScript、生产构建、真实 Renderer、MCP 三分支、文件/脚本、异常退出/重启及 W5→W4→W3 完整回归由 `verify-week6.ps1` 一键返回 0 | 通过 |

### 第 6 周验证命令

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\verify-week6.ps1
```

2026-08-22 完整执行通过。脚本使用隔离 appHome、workspace、固定 PowerShell Skill 和本地 UNKNOWN MCP fixture；首轮验证真实 Approval Card、批准前无副作用、once/thread/deny、跨 Thread 隔离、取消、文件越界、脚本 hash 失效，随后在待审批状态异常终止 Host，第二轮验证 Approval=`INVALIDATED`、Turn=`CANCELLED/APPROVAL_INVALIDATED` 且文件不晚到写入，最后串行执行第 5、4、3 周完整回归。

### 进入下一周的边界

2026-08-22 进入第 7 周前再次按“总体方案—W6 详细设计—代码—自动化”复核，并补强二进制/非法 UTF-8/超限文件拒绝、链接与父目录替换 TOCTOU、写后 identity 重验、脚本输出上限/取消、Java/Electron 安装资源保护，以及策略自动允许/硬拒绝的独立脱敏审计。补强后 64 项 Java 测试、TypeScript、生产构建、第 6 周真实 Electron/Host 场景和 W5→W4→W3 完整回归均再次返回 0。

第 6 周完成后进入 [第 7 周 Windows 打包、显式升级与崩溃恢复详细设计](WEEK_7_DETAILED_DESIGN.md)。第 6 周没有引入永久/全局授权、任意 Shell、任意 executable、文件删除/移动、Skill 自动授权或市场能力；这些边界继续由后续周次继承。

## 第 7 周开发状态

**内部未签名候选包与自动化已实现，正式验收尚未声明完成。** 2026-08-24 已生成 `0.1.1/internal/unsigned` NSIS current-user 安装器、unpacked 目录、release manifest 和 SHA-256；previous-release `0.1.0` 安装器已固定 hash 并纳入仓库夹具。数据库自动化覆盖 V1～V6 分别升级到 V7、一致性备份、备份失败、release-state 失败、过高 schema 拒绝与最多三份保留。Host/Main/Renderer 崩溃、父进程 stdin EOF、Host 单次有界重启、crash-loop、审批失效以及 workspace/MCP/Skill 三类副作用零重放均由最终打包产物验证通过。

`verify-week7.ps1 -SkipPackage -SkipInstallAcceptance` 已返回 0，并串行通过最终包内容、manifest fail-closed、父进程存活和 Week 6→5→4→3 完整回归；摘要明确标记 `development-only`。生产 npm 依赖审计为 0 个已知漏洞，发布介质扫描确认未包含 `node_modules`、源码、测试、文档、source map、密钥或私有绝对路径。最终候选安装器及验证证据见 [第 7 周发布就绪清单](WEEK_7_RELEASE_READINESS.md)。

剩余门禁是：在一次性 Windows 用户或 VM 中不带跳过参数完成真实首次安装、`0.1.0 → 0.1.1` 数据保留升级、卸载保留和重装恢复；提供正式 appId、产品名、Publisher、图标、EULA/隐私文本；锁定官方 JDK 归档 URL/hash；配置并校验 Authenticode 证书。上述条件通过前，不把第 7 周标记完成，也不把当前安装器标记为生产或可升级正式基线。

2026-08-25 项目负责人明确接受 `internal/unsigned` 内测基线风险，批准在不改变第 7 周 `development-only` 状态的前提下先进入 [第 8 周核心兼容性、稳定性基线与受控内测详细设计](WEEK_8_DETAILED_DESIGN.md)。正式 Publisher、品牌、法务与签名材料已登记到 [生产发布资料与门禁待办](PRODUCTION_RELEASE_TODO.md)；W7 真实安装门禁调整为 W8 内测候选分发前的强制准入门，不能被 Mock、unpacked smoke 或人工声明替代。

## 第 8 周设计状态

**详细设计已完成，核心代码已开始实施，尚未声明第 8 周完成。** 2026-08-25 已完成第一批 W8-A～W8-I 基础实现：固定且严格校验的 DeepSeek/Qwen Provider Catalog；safeStorage 到一次性、5 分钟、Provider 绑定的 Host secret session；生产运行切换至 HarnessAgent 单一 ReAct（最多 10 轮）并继续通过 ToolGateway/PermissionEngine/ApprovalCoordinator 执行 Tool；Provider 设置与连接校验 UI；V8 Provider 状态及 `retry_of_turn_id` 迁移；失败/取消/中断重试；有界 Steering 与工具返回安全点注入；以及不读取时间线正文和原始日志的 allowlist 诊断预览/显式本地导出。

当前自动化基线：`npm run typecheck` 返回 0；`mvn -pl agent-persistence,agent-host -am test` Reactor 返回 `BUILD SUCCESS`，Persistence 18、MCP 12、Runtime 32、Host 14，共 76 项既有测试通过。Retry/Steering 与诊断聚合的新增边界测试已加入，需在下一次门禁执行中计入更新后的测试总数。

设计复核识别出的生产 Mock、未驱动 Harness ReAct、Provider 密钥无运行闭环以及 Retry/Steering 缺口已在本批代码中完成结构性修复；Mock 仅在显式测试夹具开关下保留。详细工作包、W8-A01～W8-A14 门禁和外部依赖见 [第 8 周详细设计](WEEK_8_DETAILED_DESIGN.md)。

仍为外部阻塞：DeepSeek/Qwen 低权限测试 key 与 Qwen 区域（W8-A05 真实验收）、一次性 Windows x64 非管理员 VM/用户（W7 补证及 W8-A14）、3～5 名技术内测者（W8-A13）。100 Turn、2 小时 soak、Provider/MCP fixture 矩阵、敏感哨兵扫描、`verify-week8.ps1` beta 总门禁和受控内测证据尚未完成；在 W8-A01～W8-A14 全部通过前，状态保持“实施中”。

2026-08-28 已把本周实施中形成的 iWork 导航、会话上下文与摘要、Markdown/调用链折叠、受控 Python/JavaScript Skill 执行、执行前 reference、单轮统一授权、常规设置、结构化输出物和右侧安全预览补录到第 8 周详细设计。补录同时新增 W8-OA01～W8-OA10 验收项；针对性构建、Renderer/Host smoke 和 internal 安装包内容校验已通过，但不替代上述外部与长稳门禁。

## 第 9 周设计状态

**详细设计已完成，第一批自动化与长稳观测代码已开始实施，尚未声明第 9 周完成。** [第 9 周详细设计](WEEK_9_DETAILED_DESIGN.md) 严格承接总体方案“兼容性、稳定性与内测”的最后阶段，只安排 DeepSeek/Qwen、STDIO/Streamable HTTP、真实 Skill/MCP 样本、Windows x64 安装/网络矩阵、8 小时且不少于 500 Turn 长稳、8～15 名受控内测者反馈收敛和一期总验收，不引入新 Provider、多 Agent、RAG、MCP OAuth、Computer Use 或其他一期外能力。

2026-08-28 第一批实现新增 `verify-week8.ps1` 与 `verify-week9.ps1` 的 `commit/matrix/soak/beta` 分层入口、候选 SHA-256 绑定的机器可读证据校验、敏感字段/私有路径拒绝和三态发布建议；新增静态合同检查，冻结 Java 21、AgentScope `1.1.0-RC2`、V8 schema、双 Provider Catalog、Renderer IPC 与 ToolGateway 安全边界。Host 诊断 allowlist 增加活动 Turn/Thread、SSE 订阅、待审批、一次性密钥会话、MCP 连接/STDIO、Skill 脚本和应用拥有子进程计数；所有字段仅为数值，不读取时间线正文、Tool 参数/结果、日志或路径。

首批验证结果：`npm run typecheck` 返回 0；五模块 `mvn -pl agent-host -am test` 返回 `BUILD SUCCESS`，Persistence、MCP、Runtime、Host 均无失败（Runtime 保留 1 个既有环境型跳过）；Week 9 静态合同、证据校验正向样本、敏感证据拒绝和开发态 `commit` 入口均返回 0。Provider 断流 fixture 与脚本进程计数回归已补入定向测试。上述结果只证明开发基线可继续，不替代真实 Provider、Windows 安装、8 小时长稳和扩大内测证据。

第 9 周允许先实现扩面 fixture、矩阵脚本和安全回归，但 W8-A01～W8-A14 及 W8-OA01～W8-OA10 全部继承为基础门禁。最终只允许输出 `GO_PRODUCTION`、`GO_INTERNAL_ONLY` 或 `NO_GO` 三种建议；生产身份、签名、法务或真实安装材料未关闭时，即使一期功能门禁通过也只能建议 internal/unsigned。

2026-09-01 项目负责人确认 DeepSeek、Qwen、Windows 安装矩阵、真实 MCP 与真实 Skill 已人工验证，本轮不再重复执行；集中内测反馈持续收集，不作为本次变更内容。正式发布身份已冻结为 `iWork 1.0.0`、`com.candlefish.iwork`、Publisher `杭州烛光鱼有限公司`，图标使用 `build/icon.ico`，隐私文本使用 `packaging/legal/PRIVACY_zh-CN.md`。发布通道未定且 Authenticode 明确推迟到二期，因此当前候选继续限定为 `internal/unsigned`；正式身份变化后的安装/升级兼容仍需基于新候选复核，旧身份安装证据不能直接作为 `1.0.0` 证据。

同批实现新增安装资源 `initial-mcp-catalog.json`，首次启动幂等初始化无凭据的 `xquant-indicator-mcp`，已存在同名配置不覆盖、用户删除后不复活；配置及隐私文本均纳入 release manifest 防篡改校验。`indicator-extraction` 与 Apache-2.0 的 `web-artifacts-builder` 已加入内置 Skill。当前导入的 `docx`、`xlsx`、`pptx` 目录许可证明确限制复制与再分发，不能直接进入 iWork 安装包；需提供可再分发版本或改为自研替代 Skill 后再纳入。

2026-09-01 已完成 `iWork-1.0.0-windows-x64.exe` 内部未签名候选构建，SHA-256 为 `b755b70a2bba558d71f992a1528fafb27c41a69885b6197a6d2812171d9bba8c`。完整 Maven 回归共 104 项，0 failure、0 error、1 项环境型 skip；TypeScript typecheck、UI/Electron 生产构建和包内容校验均返回 0。ECharts 已从 5.5.0 升级到 6.1.0，隔离 Electron HTML 预览实际渲染通过，`npm audit --omit=dev` 为 0 个已知漏洞。候选包内实际包含 `indicator-extraction`、`skill-catalog-guide`、`web-artifacts-builder` 三个 Skill，未冒险携带不可再分发的 `docx/xlsx/pptx`。由于当前为 `NotSigned`、EULA 未决定且隐私文本仍为内测措辞，发布建议保持 `GO_INTERNAL_ONLY`。

2026-09-01 第二次收口复核确认发布通道为 `beta`，新身份安装复核由项目负责人确认通过；代码已支持 `internal | beta | production` 三通道并通过类型、生产构建和 release manifest 静态回归。Temurin 21.0.12+8 Windows x64 官方归档 URL/hash 也已再次对照官方 `.sha256.txt` 并写入发布锁。指定来源中的 `docx/xlsx/pptx` 无可再分发许可，且原始运行指令与 iWork 权限边界不兼容，仍未打入。当前 `PRIVACY_zh-CN.md` 实际是带占位符且与默认不上传遥测行为矛盾的 EULA；独立隐私文本、8 小时/500 Turn 证据、8～15 人 beta 反馈和无 Skip 总门禁仍缺失。详见 [一期收口复核](PHASE_1_CLOSEOUT_REVIEW.md)；当前结论为 `NO_GO`，尚不启动二期总体设计。

2026-09-02 项目负责人明确 `docx/xlsx/pptx` 不进入一期安装包，因此该项转为范围决定，不再阻塞一期。已将旧的混合法律文本拆分为 `EULA_zh-CN.txt` 和独立隐私政策法务审阅草案：内容按本地优先、默认不上传遥测、用户显式调用 Provider/MCP 的真实数据流编写；EULA 已接入 NSIS，两份文本均进入 release manifest 防篡改校验。新增发布法律文本校验，任何草案标记或联系信息占位符都会在构建前失败关闭。当前仍待法务定稿、最终候选 hash 绑定的 8 小时/500 Turn、8～15 人 beta 反馈、最终安装/真实服务重验归档和无 Skip Week 9 beta 总门禁，结论保持 `NO_GO`。责任划分见 [第 9 周最终证据责任清单](WEEK_9_EVIDENCE_RESPONSIBILITY.md)。

2026-09-02 法务确认和联系信息补充已完成，法律草案标记已清除，正式校验返回 0。已重新构建包含取消竞态修复的 `iWork 1.0.0 beta/unsigned` 候选，安装器 SHA-256 为 `546354edd199669f0c75da2897fe64fa9d6a546c0a855a98edca6f1eca74b546`；完整 Maven 回归、TypeScript、生产构建和包内容检查通过。候选绑定的 `security-scan.json` 已重新生成并通过五类检查。新增 8 小时/500 Turn 执行器、用户证据模板和严格导入器；长稳运行授权已取得，先通过短时执行器校验再进入连续 8 小时运行，最终 Windows/真实服务证据和 8～15 人 beta 材料仍需按模板提交。因此一期结论仍为 `NO_GO`，尚未开始二期总体设计。

2026-09-03 首次正式长稳实际运行 28,841 秒并完成 6,217 Turn；资源增长、取消/审批延迟、数据库完整性、终态唯一性及五项最终运行计数均通过，但原始 `soak-summary.json` 因唯一失败码 `SOAK_EVENT_TIMEOUT` 保持 `failed`。根因确认为执行器在同一 Thread 获得 `ALLOW_THREAD` 后仍强制等待新的 `approval.required`，属于测试执行器误判。现已隔离每次显式审批的 Thread，并将 Host 恢复等待对齐到受控 120 秒上限；修正后的 60 Turn 回归通过，`failureCodes` 为空，Host 恢复在约 90 秒时成功。正式 8 小时门禁仍需重跑，不使用修正后的短门禁覆盖原始失败结果。用户侧证据草稿 `evidence/week9/user-evidence.json` 已建立，真实样本/安装状态按项目负责人此前确认预填为通过，证据 ID、匿名环境 ID 和 8～15 名 beta 测试者仍待补齐。

2026-09-03 经项目负责人确认，长稳采用可审计裁定而不为受控 beta 重跑。新增 `adjudicate-week9-soak.mjs`：只允许原始完整长稳唯一失败码为 `SOAK_EVENT_TIMEOUT`，重新计算 20 槽位分布并要求取消、重试、steering、Provider 故障槽位完整，要求全部稳定性阈值通过、最终计数清零，同时绑定原始汇总与修复后 60 Turn 回归的 SHA-256。`verify-week9-evidence.mjs` 仅在上述条件全部满足时接受 `soak-adjudication.json`，并强制裁定范围为 `CONTROLLED_BETA_INTERNAL_ONLY`；production 仍要求重新执行完整长稳。正向、源证据篡改、production 越权和敏感证据反向测试均通过。因此 P1-C06 对本次受控 beta 已关闭，剩余门禁为用户侧证据、兼容自动化证据和 Week 9 beta 总门禁。
