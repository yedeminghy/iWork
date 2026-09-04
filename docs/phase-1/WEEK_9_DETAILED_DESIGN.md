# 第 9 周详细设计：兼容扩面、长稳、反馈收敛与一期总验收

> 状态：已完成详细设计，可在第 8 周基础门禁可执行的前提下开始非发布阻塞工作  
> 日期：2026-08-28  
> 上位方案：[一期最终架构方案](../FINAL_ARCHITECTURE.md)  
> 前置设计：[第 8 周详细设计](WEEK_8_DETAILED_DESIGN.md)  
> 发布前置：[第 7 周发布就绪清单](WEEK_7_RELEASE_READINESS.md)、[生产发布资料与门禁待办](PRODUCTION_RELEASE_TODO.md)  
> 实施状态：[阶段一实施状态](IMPLEMENTATION_STATUS.md)

## 0. 设计结论与强制边界

本设计只展开总体方案既定的最后一段，不增加新产品阶段：

| 总体方案阶段 | 第 8 周已建立的基线 | 第 9 周唯一职责 |
| --- | --- | --- |
| 第 8～9 周：兼容性、稳定性与内测 | 双 Provider/双 MCP Transport 核心合同、Harness 单一 ReAct、取消/恢复/安全基线、受控内测入口 | 扩大 Windows x64、网络、真实 MCP/Skill 样本和内测覆盖；执行 8 小时以上长稳；收敛缺陷；完成一期总验收和发布建议 |

第 9 周采用以下不可变约束：

1. 不新增一期外功能。不得引入新 Provider、本地模型、MCP OAuth、市场、RAG、长期记忆、Subagent/A2A、多 Agent、Computer Use、浏览器自动化、任意 Shell、遥测或后台自动上传。
2. Provider 仍只验收安装 Catalog 中的 DeepSeek 与 Qwen；AgentScope Java 仍固定 `1.1.0-RC2`，本周不升级框架或 Electron 主版本。
3. 首发运行平台仍是 Windows x64；设备扩面指 Windows x64 的受支持系统、企业镜像、DPI、磁盘和网络条件，不代表 macOS/Linux/ARM64 进入一期。
4. 第 8 周 W8-A01～W8-A14 是基础门禁。允许提前准备矩阵、fixture、脚本和修复，但基础门禁未通过时不得把第 9 周或一期标记完成。
5. 所有兼容性修复必须保留 ToolGateway、PermissionEngine、根目录、哈希、解释器、safeStorage、loopback token 和 Renderer sandbox 边界；不能用“兼容”作为降低安全策略的理由。
6. 8 小时长稳必须使用可重复 fixture 为主，真实 Provider/MCP 只做有界抽样，避免把公网波动、配额或第三方故障误判为本地资源泄漏。
7. 任何 P0 立即停止候选分发；P1 阻塞一期完成；P2 必须有明确规避、负责人和发布决定；P3 可进入后续体验清单。
8. 第 9 周的最终产物是“可审计的一期验收结论与发布建议”，不是无条件 production 发布。签名、Publisher、图标、法务或真实安装门禁未关闭时，只能建议继续 internal/unsigned。

## 1. 目标、范围与非范围

### 1.1 本周必须交付

1. **兼容扩面**：DeepSeek/Qwen 在账号、区域、代理和异常网络组合下的稳定分类；STDIO/Streamable HTTP 真实 MCP 样本与协议差异；内置/用户/工作区/导入 Skill 的资源与脚本样本。
2. **Windows 矩阵**：至少覆盖两类干净 Windows x64 环境和一类企业受管镜像；普通用户安装、首次启动、升级、卸载保留、重装恢复、DPI 和路径字符组合。
3. **交互回归**：iWork 左侧导航、会话搜索/摘要/归档、连续上下文、Markdown/调用链折叠、统一授权、常规设置、输出物卡片与安全预览。
4. **8 小时以上长稳**：混合 Chat、Tool、Approval、取消、重试、Steering、MCP 重连、Skill 脚本和工件预览；采集有界资源指标并验证无孤儿、无永久运行态、数据库完整。
5. **内测反馈闭环**：扩大到 8～15 名受控技术/业务测试者；按候选 hash 和脚本编号收集问题，完成 P0/P1 清零和 P2/P3 发布决策。
6. **安全与隐私总回归**：secret、reasoning、路径、正文、诊断包、Renderer IPC、脚本解释器、HTML/Office 预览、Skill/MCP prompt injection。
7. **发布候选冻结**：冻结 schema、Catalog、IPC、安装身份和候选 hash；生成一期验收报告、已知问题、回滚说明和 production/internal 发布建议。
8. **自动化总门禁**：新增 `verify-week9.ps1`，串行执行第 9 周矩阵、8 小时 soak、installed smoke、Week 8→3 回归并输出机器可读证据。

### 1.2 明确不在本周

- 不通过增加第三个 Provider 或更多模型证明“兼容性”；只收敛 DeepSeek/Qwen 既定 Catalog。
- 不支持用户自定义 Provider endpoint/header，不增加本地模型和 GPU 探测。
- 不产品化 MCP Resources/Prompts/OAuth，不做市场、自动发现和远程安装。
- 不增加 Skill 自动授权、无人值守运行、计划任务、后台同步或任意依赖安装。
- 不把 AUTO“帮我批准”升级为永久/全局授权，不在 Renderer 或配置文件中保存 grant。
- 不把右侧预览做成完整 Office 编辑器；只读、安全、有界预览是一期边界。
- 不做云端账号、多用户、团队共享、云同步、远程日志上传或远程崩溃收集。
- 不解决正式品牌、签名证书和法律文本本身；只检查生产待办是否已经由负责人关闭。

## 2. 开工评估与周次门禁

### 2.1 信息充分性评估

代码、总体方案、第 7～8 周设计、现有自动化和近期问题样本已经足够开始 W9-A～W9-F 的实现：矩阵定义、fixture、脚本、交互回归、安全扫描和长稳采样不需要新的架构决策。

下列外部输入不阻塞编码，但阻塞对应最终验收：

| 外部输入 | 阻塞项 | 缺失时处理 |
| --- | --- | --- |
| DeepSeek/Qwen 低权限测试 key、Qwen 账号区域 | W8-A05、W9-A03 | `BLOCKED_EXTERNAL`，不能用 Mock 冒充 |
| 至少两类一次性 Windows x64 普通用户/VM，另有企业镜像更佳 | W7 无 skip、W9-A02/W9-A12 | 只能完成开发矩阵，不能形成安装发布建议 |
| 8～15 名受控内测者 | W9-A10 | 保持 internal，不宣称反馈已收敛 |
| 正式产品身份、图标、EULA/隐私、证书 | production 建议 | 可完成一期功能验收，但发布建议只能是 internal/unsigned |

### 2.2 进入、继续与完成规则

- **可开工**：第 8 周代码可构建，现有针对性 Renderer/Host smoke 返回 0，即可开始夹具、长稳和矩阵脚本。
- **可形成 RC**：W8-A01～W8-A12 通过，W8-A13/A14 所需环境已准备，P0/P1=0。
- **可扩大内测**：无 skip W7 安装门禁、W8 beta、2 小时 soak 和敏感扫描全部通过。
- **可完成第 9 周**：W9-A01～W9-A14 全部有证据，且所有继承的 W8 门禁通过。
- **可建议 production**：除一期功能门禁外，`PRODUCTION_RELEASE_TODO.md` 的身份、签名、法务、官方 JDK lock 和真实安装项全部关闭；否则结论必须是 internal/unsigned 或 no-go。

## 3. 一期总验收追踪

| 总体方案一期完成条件 | 第 9 周验证证据 |
| --- | --- |
| 会话可创建、恢复、取消和重试 | Windows 矩阵 installed smoke、连续上下文、崩溃恢复、取消/重试竞态 |
| Skill 可正确渐进加载 | 多 scope/导入 Skill、执行前 reference、资源预算、PS/Python/JS 脚本与重启恢复 |
| STDIO 与 Streamable HTTP MCP 可配置和调用 | 真实样本 + 公共合同 + 断线/重连/取消/Schema/限流矩阵 |
| 危险 Tool 可暂停、批准或拒绝 | ASK/AUTO、ALLOW_ONCE/ALLOW_THREAD/DENY、过期/取消/重启/指纹变化 |
| 工具、错误、取消和权限决策可观察 | 调用链、Tool Card、稳定错误码、Item 快照、脱敏诊断与内测证据 |
| 无 Renderer 系统旁路、密钥明文或越界文件访问 | 静态 IPC allowlist、运行态攻击夹具、secret/path/reasoning sentinel scan |

## 4. 兼容性扩面矩阵

### 4.1 Provider 矩阵

只覆盖 Catalog 中既有的 DeepSeek/Qwen Profile：

| 维度 | 样本 | 通过条件 |
| --- | --- | --- |
| 账号/区域 | 有效账号、Qwen 目标区域、模型不可用账号 | READY 或稳定 `MODEL_NOT_AVAILABLE/REGION_MISMATCH`，不替换模型 |
| 凭据 | 有效、空、错误、过期/撤销 | safeStorage 覆盖/删除正确，UI 不回显，错误稳定且无 body 泄漏 |
| 网络 | 直连、系统/企业代理、DNS 失败、TLS 失败、断网恢复 | 有界超时和明确错误；恢复只影响新 Turn，不重放旧 Tool |
| 服务异常 | 401/403/404/429/5xx、首包超时、中途断流 | 归一错误，已持久化文本保留，无永久生成态 |
| 内容/协议 | 中文、英文、Unicode、长文本、结构化 Tool、reasoning 字段 | 顺序/编码正确，Tool 完整后执行，reasoning 全路径不可见 |
| 上下文 | 连续问答、接续“上面内容”、接近上限 | 历史有界且角色正确，最多一次受控压缩，不循环重试 |

真实 Provider 每个 Profile 只执行低费用、低敏感度的最小脚本；连接检查、文本、只读 Tool、取消和无效 key 各至少一次。公网性能不进入本地 p95 门禁，但状态终结、错误分类和安全边界必须通过。

### 4.2 MCP 真实样本矩阵

每类 Transport 至少选择两个受控样本：一个项目 fixture 和一个真实但低风险 Server。真实 Server 必须由项目负责人确认许可证、来源、版本和测试数据边界。

| 场景 | STDIO | Streamable HTTP |
| --- | --- | --- |
| 初始化 | 正常 handshake、能力缺省/扩展字段、stderr 噪声 | session 建立、版本协商、header 限制 |
| Tool catalog | 空列表、1/50/128 Tool、Unicode 描述、复杂 schema | 同一公共 schema 合同 |
| 调用 | 文本/结构化结果、错误 result、超限/二进制 | 同一结果限制和摘要合同 |
| 故障 | executable 不存在、进程退出、半包、超时 | 401/404/429/5xx、断流、超时 |
| 生命周期 | 禁用/启用、显式重连、Host/Main 退出清理 | session 清理、凭据清除、有界重连 |
| 安全 | 无 shell 字符串、工作目录/环境 allowlist | endpoint 固定、Bearer 不落盘/日志 |

真实样本差异只能在 Transport/adapter 层处理，不能弱化 JSON Schema、权限、结果上限或错误契约。未知 schema 必须标记 `INCOMPATIBLE_SCHEMA`；连接恢复不得重放任何在途 call。

### 4.3 Skill 与脚本矩阵

覆盖 builtin/user/workspace/managed-import 四类来源，至少包含纯指令 Skill、带执行前 reference 的指标 Skill、带 PowerShell 的测试 Skill、带 Python/JavaScript 的 DOCX/XLSX 类 Skill。

1. 本地目录与公开 GitHub 预检、确认、安装、替换、回滚和卸载均保留来源、commit（如适用）、package hash 与 content hash。
2. 标准大型 Skill 在 20 MiB 有效包预算内可导入；单文本资源、总包、文件数、深度、链接、特殊文件和 staging 上限分别有负向测试。
3. 激活只注入名称/描述；读取 `SKILL.md` 后，如果“执行前准备”声明参考资源，则必须先加载 exact resource。缺失、超预算或 hash 变化显式失败。
4. PowerShell/Python/JavaScript 均验证解释器选择、参数数组、隔离环境、固定工作目录、hash、根 identity、取消、超时、输出上限和子进程清理。
5. 以“查询指标并生成 Word/Excel”为端到端样本，至少验证默认参数、指标 Tool、工作区脚本、文件落盘、工件 DTO、预览与重启后历史恢复。
6. 测试不能依赖生产数据。指标服务不可用时可以使用合同 fixture 验证调用顺序；真实业务结果只作为非阻塞人工样本。

### 4.4 UI 与文件输出矩阵

- 导航：iWork 品牌、历史搜索、侧栏收起/展开、功能图标与名称、设置菜单、会话列表折叠。
- 会话：摘要来源、连续上下文、重命名、归档/恢复、分页、当前会话切换和重启恢复。
- 对话：GFM、代码块、流式文本、最终结果位于调用链后、整体/单步折叠、ASK/AUTO、取消/重试/Steering。
- 常规：默认工作区选择与重启生效、最大迭代 1～50、配置损坏回退、旧 grant 失效。
- 输出：DOCX/XLSX/HTML/Markdown 卡片、打开/定位/另存、右侧预览、收起、普通展开、覆盖对话区展开且不覆盖侧栏。
- 环境：100%/125%/150%/200% DPI、常见窄窗口、长中文/英文文件名、空格/中文 workspace、浅色内容区与深色导航对比。

## 5. Windows、安装与网络覆盖

### 5.1 设备环境

最低覆盖：

1. 一类干净 Windows 10 x64 普通用户环境（仅在项目方仍支持该系统时）。
2. 一类干净 Windows 11 x64 普通用户环境。
3. 一类企业镜像或带代理/终端防护的软件环境；若不可获得，标记为发布风险而不是伪造通过。

每个环境记录匿名 `environmentId`、OS 大版本/构建桶、CPU 架构、内存档位、DPI、代理类型、候选 SHA-256，不记录用户名、主机名、SID、绝对路径或安全软件详情。

### 5.2 安装生命周期

每个发布候选至少执行：

```text
全新安装 0.1.1 candidate
  -> 首次启动与默认目录
  -> 新建会话/Provider/MCP/Skill/Approval/Artifact smoke
  -> 正常退出与重启恢复

previous 0.1.0
  -> 写入 V1～V7 代表性数据
  -> 升级到 candidate
  -> 数据、safeStorage credentialRef、workspace/MCP/Skill 配置恢复
  -> V8 迁移和完整性检查

candidate 卸载
  -> appHome/workspace 保留
  -> 重装 candidate
  -> 会话与配置恢复，未完成 Tool 不重放
```

如果产品身份、appId 或 Publisher 在候选期变化，必须重新执行完整生命周期，不得沿用旧证据。

### 5.3 路径、磁盘与网络异常

- workspace 覆盖默认路径、空格、中文、较长但合法路径、只读目录、被移动目录、符号链接/联接和磁盘空间不足。
- 数据库覆盖 WAL、busy、磁盘满、备份失败、V1～V7→V8、过高 schema、损坏副本 fail closed。
- 网络覆盖离线启动、运行中断网、恢复联网、DNS/TLS/代理认证失败；不在日志中记录代理凭据。
- 文件输出覆盖目标存在、`overwrite=false`、父目录不存在、写后替换、文件被外部应用占用；不增加删除/移动能力。

## 6. 8 小时以上长稳设计

### 6.1 负载模型

`verify-week9.ps1 -Profile soak` 连续运行不少于 8 小时，并完成不少于 500 个确定性 Turn；若 8 小时到达时 Turn 数不足则继续到 500 个，若 500 个先完成则继续到 8 小时。

负载比例：

| 场景 | 比例/下限 |
| --- | --- |
| 纯流式 Chat 与连续上下文 | 30% |
| MCP Tool（两类 Transport 均有） | 20% |
| workspace/Skill 脚本和输出物 | 15%，PS/Python/JS 均至少 20 次 |
| ASK/AUTO Approval | 15%，ALLOW_ONCE/THREAD/DENY/过期均覆盖 |
| 取消/重试/Steering | 15%，不同安全点分布 |
| 故障注入与恢复 | 5%，Provider 断流、MCP 退出、Renderer reload、Host 单次重启 |

最多同时运行 4 个不同 Thread；同一 Thread 仍只有一个 active Turn。每 30 分钟打开/收起/展开至少一种输出物预览，验证长时间 UI 使用不会积累 iframe、listener 或大 Buffer。

### 6.2 采样与阈值

每 5 分钟采集 Electron Main/Renderer、Java Host、MCP/Skill 子进程的 RSS、Java heap、线程、句柄、子进程数、SQLite 连接、SSE subscriber、pending Approval、secret session、active Turn 和预览对象计数。

通过条件：

1. warm-up 后合计 RSS 增长不超过 `max(384 MiB, 30%)`，最后 2 小时无持续单调增长；任何明显泄漏趋势必须人工复核。
2. Java heap 在强制空闲窗口后可回落，最后 2 小时基线增长不超过 20%；线程和句柄不持续增加。
3. 取消进入 `CANCELLING` p95 < 500 ms，协作终结 p95 < 2 s，最大 10 s；Approval 自动/人工决定后 p95 1 s 内恢复事件流（不含外部 Tool 时延）。
4. 结束时 active Turn、pending Approval、secret session、SSE subscriber 和应用拥有的 MCP/Skill 子进程均为 0。
5. SQLite `integrity_check=ok`，Item sequence 连续，无重复终态；所有 Turn 均处于允许的终态。
6. 8 小时期间不得通过定时重启 Host/Main/Renderer 规避资源增长；仅计划内故障注入允许重启并单独记账。
7. 真实 Provider/MCP 外部时延和配额不计入应用性能阈值，但不得造成 UI 永久运行、无限自动重试或 Tool 重放。

### 6.3 故障留证

失败时保存不含用户正文的采样 CSV/JSON、候选 hash、fixture seed、故障注入步骤、稳定错误码和进程清点。禁止自动保存 heap dump、原始网络 body、MCP stderr、聊天、Skill/Tool 内容或绝对路径；需要额外敏感诊断时必须另行由开发人员在隔离环境人工执行。

## 7. 安全与隐私一期总回归

### 7.1 必测攻击面

1. **Renderer/IPC**：无 Node、fs、process、shell、通用 HTTP/secret IPC；旧 sender、非主窗口、畸形 DTO、绝对路径和任意 URL 均拒绝。
2. **Host/loopback**：只绑定 `127.0.0.1` 随机端口；错误 token/协议/Origin/secret session/provider 绑定失败。
3. **Provider secret**：safeStorage 密文、一次性 TTL session、终态/取消/异常清除；日志、SQLite、命令行、环境、诊断、安装介质无哨兵。
4. **权限**：prompt injection、Skill `allowed-tools`、MCP 描述和 Tool result 不能生成 permit；AUTO 仍只 `ALLOW_ONCE`。
5. **文件根**：绝对/遍历、链接、junction、父目录/目标替换、TOCTOU、appHome 保护、只读根和 workspace 变更 grant 失效。
6. **脚本**：模型不能指定 executable/env/shell；hash、Skill generation、root identity、解释器路径在审批后变化均 `STALE`；取消只终止应用拥有进程。
7. **预览**：ZIP bomb、超大 XML、路径穿越 entry、恶意 HTML/脚本/外链、损坏 Office、被替换文件和旧 XLS。
8. **内容泄漏**：reasoning/thinking、聊天/steering、system prompt、Skill 正文、Tool 参数/结果、私有路径不进入 UI 非必要区域、日志或诊断包。

### 7.2 安全不变量

兼容修复不得改变：

- Renderer 仅传安全 ID/relativePath DTO；Main/Host 持有操作系统和密钥能力。
- 文件写入、脚本执行、未知/副作用 MCP Tool 默认 `ASK`，硬拒绝优先于任何 UI 模式。
- 每次 Tool 在执行前重新准备并比较 source/invocation fingerprint；permit 单次消费。
- Host/Main/Renderer/MCP/Skill 失败后只恢复已持久化事实，绝不恢复 secret、grant、pending Approval 或在途副作用。
- 诊断包字段 allowlist 生成并由用户预览后本地导出，无自动上传。

## 8. 内测扩大与反馈闭环

### 8.1 人群与数据边界

目标 8～15 名受控测试者，至少包含开发、测试和 2 名目标业务用户。所有人使用测试 Provider key、测试 MCP、脱敏/模拟业务数据和独立 workspace；不得接入生产账号、生产组合数据或未经批准的企业 Server。

### 8.2 测试脚本

每名测试者至少完成：

1. 安装、首启、iWork 导航、常规设置和重启。
2. 新建两轮连续会话，第二轮使用“上面内容”指代；搜索、摘要、重命名、归档/恢复。
3. ASK 与 AUTO 各一次，包含批准、拒绝或取消。
4. 一个 STDIO/HTTP MCP 只读调用和一次断线恢复。
5. indicator-extraction 或同类 Skill 的执行前 reference；一个 Python/JavaScript 文档 Skill 生成 Word/Excel。
6. 检查最终 Markdown、调用链折叠、输出物卡片、打开方式和右侧预览的收起/覆盖展开。
7. 模拟断网、关闭 MCP 或取消脚本，确认无挂死、无重放。
8. 预览诊断包并确认无正文、路径、密钥；记录候选 hash 和步骤编号。

### 8.3 缺陷闭环

缺陷记录必须包含：`candidateSha256`、`environmentId`、`scriptStepId`、预期/实际、稳定错误码、复现率、诊断包 hash、严重级别、负责人、目标版本和验证证据。

| 等级 | 定义 | W9 处理 |
| --- | --- | --- |
| P0 | 数据不可恢复、安全绕过、密钥泄漏、错误副作用 | 停止分发，修复后全门禁重跑 |
| P1 | 核心启动/会话/Provider/MCP/Skill/取消/输出不可用且无规避 | 必须修复，阻塞 W9 完成 |
| P2 | 有明确安全规避的兼容/功能问题 | 修复或形成 owner+期限+发布接受决定 |
| P3 | 体验、布局、文案问题 | 评审排序，可进入后续体验清单 |

候选冻结后任何 P0/P1 修复、schema/IPC/Catalog/权限/安装资源变化都必须生成新候选 hash，并重跑受影响矩阵及 Week 8→3 回归。不得在证据文档中手工替换 hash。

## 9. 数据、API、配置与候选冻结

### 9.1 数据库

第 9 周默认不新增业务 schema，继续使用 V8。若修复 P0/P1 必须迁移数据：

1. 单独提交 V9 migration 和回滚/前向恢复说明，不修改既有 migration。
2. 覆盖 V1～V8→V9、V9 幂等重启、过高 schema、备份失败、磁盘满和真实 previous-release 数据。
3. migration 不得存密钥、绝对路径的新副本、聊天摘要正文副本、preview 内容或审批 grant。
4. 一旦 RC 冻结，不再接受非阻塞 schema 变化。

### 9.2 API/IPC

原则上冻结第 8 周 Host API、AG-UI envelope、preload 白名单和 `OutputArtifact` DTO。兼容修复只能：

- 增加向后兼容的可选字段或稳定错误码；
- 收紧验证而不扩大 Renderer 能力；
- 保持旧 snapshot 和旧安装数据可读取；
- 更新 `protocolVersion` 时必须同步 Main/Host/preload 并增加不匹配 fail-closed 测试。

禁止新增通用 `readFile/writeFile/exec/request/openExternal/secret` IPC。

### 9.3 配置冻结

- `provider-catalog.json`、Skill 内置资源、MCP fixture、release manifest、JRE 归档/hash、appId/productName/Publisher/channel/signed 状态均进入候选清单。
- `settings.json` 只保留 `defaultWorkspacePath` 与 `maxIterations`，未知字段忽略，非法值安全回退。
- 正式生产身份未落实时，不把 internal 的 appId/Publisher/unsigned 身份当作可升级生产基线。

## 10. 发布候选与决策输出

### 10.1 候选产物

每个 RC 输出：

```text
dist-release/
  agent-desktop-<version>-windows-x64.exe
  win-unpacked/
  release-manifest.json
  release-artifacts.json
evidence/week9/<candidate-sha>/
  week9-verification.json
  compatibility-matrix.json
  soak-summary.json
  install-matrix.json
  security-scan.json
  beta-feedback-summary.md
  known-issues.md
  release-recommendation.md
```

证据目录不进入安装包。所有摘要只使用安全字段；候选 SHA-256、source revision、manifest hash、channel、signed 和 test profile 必须相互一致。

### 10.2 发布建议

最终建议只有三种：

- `GO_PRODUCTION`：一期与生产待办全部通过，签名/身份/法务/真实安装证据完整。
- `GO_INTERNAL_ONLY`：一期功能与安全通过，但签名、身份、法务或生产安装材料未关闭；只允许受控 internal/unsigned。
- `NO_GO`：存在 P0/P1、基础/长稳/安全/安装门禁失败或关键外部验收无证据。

不得用“功能基本可用”“仅体验问题”替代上述可审计结论。

## 11. 实施工作包与顺序

| 工作包 | 主要内容 | 完成门 |
| --- | --- | --- |
| W9-A 基线对账 | W8 未完成项、候选身份、外部输入、矩阵设备锁定 | 所有阻塞项有 owner/status |
| W9-B 兼容夹具 | Provider/MCP/Skill/UI 扩面 fixture 和稳定错误契约 | commit profile 可重复通过 |
| W9-C Windows/安装 | 干净系统、企业镜像、路径/DPI、升级卸载重装 | install-matrix 完整、无 skip |
| W9-D 交互与工件 | 上下文/摘要/导航/授权/设置/预览端到端 | installed Renderer smoke 通过 |
| W9-E 安全总回归 | IPC、secret、root、script、preview、reasoning/正文扫描 | 0 高危、哨兵扫描通过 |
| W9-F 长稳 | 500 Turn、8h、4 Thread、故障注入、资源采样 | 阈值、无孤儿、DB 完整 |
| W9-G 扩大内测 | 8～15 人脚本、反馈、缺陷分级 | P0/P1=0，P2/P3 有决定 |
| W9-H RC 冻结 | manifest/hash、schema/API/config、已知问题 | 候选可追溯且无漂移 |
| W9-I 一期总验收 | `verify-week9 beta`、Week 8→3 回归、发布建议 | W9-A01～A14 全部有证据 |

依赖顺序：`W9-A -> W9-B -> W9-C/W9-D/W9-E -> W9-F -> W9-G -> W9-H -> W9-I`。W9-B～E 可并行准备；W9-F 必须使用通过 commit/security 门禁的同一构建；W9-G 只能使用通过无 skip 安装和 2 小时基础 soak 的候选；冻结后变更必须重新计算依赖门禁。

## 12. 自动化与验证脚本

### 12.1 Java

1. Conversation history 角色/终态/排除当前 Turn/消息与字符预算/Unicode 截断。
2. Thread preview 查询、旧库兼容、分页、归档/恢复和 20,000 Item 上限。
3. Skill package 三层预算、reference 调用顺序、PS/Python/JS 解释器/hash/root/环境/取消。
4. OutputArtifact 只由成功文件操作产生，路径与类型安全，失败不伪造工件。
5. Provider/MCP 扩面合同、错误归一、取消、重连不重放、reasoning 过滤。
6. V1～V8 migration、数据库 busy/满/损坏与恢复一致性。

### 12.2 Electron/Renderer

1. iWork 导航、搜索、侧栏和会话列表折叠、菜单、归档/恢复、设置入口。
2. ASK/AUTO 多 Approval 竞态、跨 Thread、刷新、过期、取消和硬拒绝。
3. 常规设置原子持久化、脱敏路径、重启提示、最大迭代边界。
4. 最终 GFM 与调用链排序、整体/单步折叠、snapshot 恢复。
5. 输出卡片、四类预览、收起/展开/覆盖对话区且侧栏可见、打开/定位/另存。
6. 恶意 HTML/Office、超限、路径替换和非主窗口 sender 的负向测试。

### 12.3 `verify-week9.ps1`

```text
commit:
  mvn clean verify
  npm ci + typecheck + build
  Provider/MCP/Skill/UI expanded fixtures
  conversation/artifact/approval/settings/security regressions
  verify-week8.ps1 -Profile commit

matrix:
  clean package + manifest/content verification
  installed smoke on declared Windows environments
  previous-release upgrade/uninstall/reinstall
  Provider/MCP/Skill real sample evidence

soak:
  exact commit candidate
  >= 8 hours and >= 500 Turns
  resource samples + fault injection + final integrity/orphan scan

beta:
  commit + matrix + soak
  expanded controlled beta summary
  secret/reasoning/path/content sentinel scan
  Week 8 -> Week 3 complete regression
  produce immutable evidence index and release recommendation
```

任何 `-Skip*` 都把 `releaseGate` 标记为 `development-only`，不能满足 W9-A14。真实 Provider key 仍只通过应用设置页录入，不成为脚本参数、环境变量或证据字段。

## 13. 验收矩阵

| 编号 | 验收主题 | 通过条件 |
| --- | --- | --- |
| W9-A01 | W8 基线继承 | W8-A01～A14 全部通过，补充 W8-OA01～OA10 纳入回归 |
| W9-A02 | Windows/安装矩阵 | 至少两类干净 Windows x64 + 可用企业镜像证据；全新/升级/卸载/重装无 skip |
| W9-A03 | Provider 扩面 | DeepSeek/Qwen 真实最小验收与区域/账号/网络/错误矩阵通过，无静默替换 |
| W9-A04 | MCP 扩面 | 两 Transport 各 fixture+真实样本，schema/结果/故障/取消/重连且零重放 |
| W9-A05 | Skill 扩面 | 多 scope、导入、reference、PS/Python/JS 和 Word/Excel 工件端到端通过 |
| W9-A06 | Chat/上下文 | 连续指代、摘要、搜索、归档、重试/Steering、Markdown/调用链顺序与恢复通过 |
| W9-A07 | 审批/设置 | ASK/AUTO 安全语义、工作区变更和 maxIterations 边界/生效时机通过 |
| W9-A08 | 输出物/预览 | 四类输出、打开方式、收起/覆盖展开、DPI/窄屏与恶意输入安全通过 |
| W9-A09 | 8h 长稳 | >=500 Turn、>=8h、资源阈值、无孤儿/挂起、DB 完整、终态唯一 |
| W9-A10 | 扩大内测 | 8～15 人完成脚本；P0/P1=0；P2/P3 有 owner、规避和决定 |
| W9-A11 | 安全隐私 | Renderer/secret/root/script/preview/reasoning/诊断总回归及 sentinel scan 通过 |
| W9-A12 | 候选可追溯 | package/asar/manifest/evidence hash 同源，schema/API/config/身份冻结 |
| W9-A13 | 一期总验收 | 总体方案 6 条完成条件逐条有自动化或人工证据，无未解释例外 |
| W9-A14 | 最终门禁 | `verify-week9.ps1 -Profile beta` 和 Week 8→3 回归返回 0，输出三态发布建议 |

## 14. 建议日程

| 工作日 | 重点 | 当日退出条件 |
| --- | --- | --- |
| Day 1 | W8 对账、矩阵/设备/样本锁定、扩面 fixture | commit profile 可运行，外部阻塞有 owner |
| Day 2 | Windows 安装、Provider/MCP/Skill/UI 矩阵 | matrix 首轮完成，P0/P1 已分级 |
| Day 3 | 安全回归和缺陷修复、RC1 | 安全门禁 0 高危，RC1 hash 冻结 |
| Day 4 | 8 小时长稳（可跨夜）与扩大内测 | soak 完成，反馈达到最小人数 |
| Day 5 | 缺陷复验、总回归、证据冻结和发布建议 | W9-A01～A14 结论完整 |

若 8 小时 soak 或扩大内测无法在自然周内完成，应顺延状态而不是缩短门禁或伪造通过。

## 15. 风险与控制

| 风险 | 控制 |
| --- | --- |
| W8 尚有外部阻塞却提前宣布一期完成 | W9-A01 强制继承全部 W8 门禁，`BLOCKED_EXTERNAL` 不算通过 |
| 设备/真实 MCP 样本差异引发范围膨胀 | 只修适配层和稳定错误，不新增协议/Provider/市场能力 |
| 长稳时间不足或公网波动污染结果 | >=8h 且 >=500 Turn；fixture 做阈值，真实服务只抽样 |
| AUTO 被误解为永久无人值守 | 仅提交 Turn、仅 ALLOW_ONCE、刷新不恢复、硬拒绝优先、审计保留 |
| Python/Node 兼容修复扩大命令执行面 | 应用选解释器、参数数组、hash/root/identity 重验、最小环境、无 shell |
| Office/HTML 预览成为攻击入口 | Main 有界读取、ZIP/XML 限额、iframe sandbox+CSP、不执行宏/公式/外链 |
| workspace 变更导致旧 grant 或旧路径继续可用 | 重启生效、更新 root identity/version、旧 grant/fingerprint 失效 |
| 会话历史把失败输出或系统审计送回模型 | 只选 user + COMPLETE assistant，可见时间线，有界预算和测试 |
| RC 修复后证据与安装器不一致 | 每次变更重建 manifest/hash/evidence，冻结后不得手工替换 |
| 正式身份/签名仍未落实 | 功能验收与 production 建议分离，只能 `GO_INTERNAL_ONLY` 或 `NO_GO` |

## 16. Definition of Done

- 所有 W8 基础门禁和 W9-A01～W9-A14 均有与同一候选 SHA-256 绑定的证据。
- DeepSeek/Qwen、STDIO/Streamable HTTP、四类 Skill 来源、PowerShell/Python/JavaScript、连续上下文和四类输出预览完成既定兼容矩阵。
- 至少两类干净 Windows x64 环境完成普通用户全新安装、previous-release 升级、卸载保留和重装恢复；企业镜像不可得时必须在发布建议中显式列为风险。
- 不少于 8 小时且不少于 500 Turn 的长稳满足资源、取消、终态、SQLite 完整性和无孤儿阈值。
- 8～15 名受控测试者完成核心脚本，P0/P1 为 0，P2/P3 均有 owner、规避与发布决定。
- Renderer/IPC、Provider secret、权限、文件根、脚本、工件预览、reasoning/正文/路径和诊断隐私总回归通过。
- schema、API/IPC、Catalog、安装身份、JRE、manifest 和候选 hash 已冻结，证据不含密钥、正文、私有路径或原始 reasoning。
- `verify-week9.ps1 -Profile beta` 与 Week 8→3 完整回归返回 0，并明确输出 `GO_PRODUCTION`、`GO_INTERNAL_ONLY` 或 `NO_GO`。
- 未引入总体方案明确排除的一期外能力。

满足上述条件后，阶段一可以形成最终验收结论；只有同时关闭生产身份、签名、法务、JDK lock 和真实安装待办时，才可建议 production 发布。否则即使功能和安全门禁通过，也必须保持 internal/unsigned 受控使用。
