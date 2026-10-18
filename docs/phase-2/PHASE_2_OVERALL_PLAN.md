# iWork 二期总体规划

> 规划基线：2026-10-18
>
> 前置状态：一期受控 Beta 已收口，发布建议 `GO_INTERNAL_ONLY`
>
> 二期主线：企业 Skill/MCP 生态 + 可恢复 Agent 工作台 + 可治理项目记忆

## 1. 二期目标

二期把一期已经可导入、可配置、可审批执行的 Skill/MCP，升级为企业可治理、可追溯、可分发、可升级和可撤销的本地优先生态。第一版不建设公共开放市场，也不引入云端多租户；优先支持企业内部 Catalog、离线/受控 HTTPS 分发、组织信任策略和审计闭环。

在不削弱一期安全边界的前提下，二期同时补齐成熟桌面 Agent 已普遍具备的工作流能力：分层项目指令、显式计划与检查点、受控生命周期 Hook、单层隔离 Subagent、会话分叉/恢复，以及默认本地、可检查、可编辑、可删除的项目记忆 MVP。这些能力服务于“任务可持续推进、经验可复用、失败可恢复”，不扩张为任意多 Agent 编排或无人值守自治平台。

二期还要关闭 production 基础能力：Authenticode、签名更新、SBOM/依赖风险处置和 production 全量回归。这些工作与生态、工作台增强并行，但不能通过自动授权、开放 Shell、扩大 Renderer 能力或把不可信记忆当作策略来换取进度。

## 2. 设计原则

1. **内容不等于权限**：Catalog 条目、Skill、MCP Resource/Prompt、项目指令、Hook 输出和记忆全部是不可信上下文，不能授予 Tool、文件、脚本、网络或审批权限。
2. **三类状态分离**：Thread/Turn 历史、任务检查点、跨会话长期记忆分别建模、分别保留和分别删除，禁止把模型摘要直接当作恢复状态或长期事实。
3. **用户可见可控**：自动学习只能生成候选记忆；持久化、修改、合并、过期和删除必须可检查、可追溯、可撤销。
4. **最小自治**：Subagent 只允许单层、有界、可取消的委派；Hook 只允许声明式事件和应用拥有的动作；二者都不形成权限旁路。
5. **本地优先与可迁移**：核心索引、状态、记忆和审计默认存放在本机 SQLite/受管目录；导出格式稳定，不绑定某个模型、向量库或云服务。
6. **渐进披露**：项目规则、Skill 和记忆先注入小型目录或摘要，正文按任务相关性加载，所有注入都有来源、大小和数量预算。
7. **失败关闭、失败可恢复**：签名、哈希、策略、恢复、Hook 或记忆校验失败时，不升级权限、不执行副作用，并保留可诊断的稳定状态。
8. **应用持有状态真相源**：原始 Item 流、压缩快照、检查点和长期记忆均由应用 SQLite/受管工件持有；Provider 或 AgentScope 的原生 session/compaction 只能作为受控适配器，不得形成第二套不可检查状态源。

## 3. 成功标准

1. 管理员可配置受信 Skill/MCP Catalog，终端用户只能在组织策略允许范围内浏览、安装或连接。
2. 每个 Catalog 条目具有稳定身份、版本、发布者、来源、内容哈希、签名状态、风险说明和兼容性信息。
3. Skill 支持安装、升级、回滚、卸载；MCP 支持模板化配置、能力预览、启停、升级和凭据撤销。
4. 市场安装、Skill 声明、MCP Catalog、Hook 和记忆永远不直接授予 Tool、文件、脚本或网络权限。
5. OAuth/Token 由 Electron safeStorage 持有，Renderer、日志、数据库、记忆和诊断包均不可读取明文。
6. 项目/用户/组织指令按确定性优先级合并，用户能看到本次 Turn 实际加载的来源、冲突和截断情况。
7. 用户能创建、查看和恢复任务检查点；恢复后 Tool/Approval 因果关系一致，外部副作用不会因恢复而静默重放。
8. 主 Agent 可委派单层只读或受控执行 Subagent；每个子任务有独立上下文、预算、工具上限、取消链和结果摘要。
9. 项目记忆支持显式记住、候选审核、检索引用、编辑、遗忘、过期、导出和历史追踪；删除后不会从索引、缓存或备份恢复为可用记忆。
10. 长上下文使用模型感知的 token 预算、完整 Turn 裁剪和可持久化压缩快照；压缩、记忆检索和 Subagent 回传均保留来源引用，不保存或展示原始 reasoning。
11. 用户可在 Context Inspector 查看上下文构成、预算、裁剪量、压缩原因与快照版本，并可手动按关注点压缩或回滚上一有效快照。
12. 上下文溢出最多执行一次受控压缩重试；压缩失败、格式错误或校验不通过时安全回退，不循环压缩、不静默丢失当前任务状态。
13. 所有安装、升级、授权、策略命中、Hook、委派、检查点、压缩、记忆读写与撤销形成脱敏审计链。
14. production 候选通过签名、安装、回滚、完整回归和全新 8 小时长稳门禁；新增 P1 能力至少达到受控 Beta 门禁后才默认开启。

## 4. 功能范围

### 4.1 P0：生产发布基础

- Authenticode 签名、可信时间戳、证书轮换/吊销和失败关闭。
- Stable/Beta/Internal 通道、签名更新、升级前备份、失败回滚和降级保护。
- SBOM、依赖漏洞治理、构建物来源追溯和 production 新鲜证据。
- 不支持无人值守安装；涉及系统变化仍需用户或企业部署系统明确确认。

### 4.2 P0：企业 Catalog 与信任

- 企业 Catalog Source：本地离线包、受控 HTTPS 只读源。
- 发布者身份、条目 manifest、内容 SHA-256、签名与组织信任根。
- 分类、搜索、版本、兼容性、风险等级、依赖和权限需求展示。
- 组织白名单、禁用清单、版本锁定、最低安全版本和紧急撤销。

### 4.3 P0：Skill 生命周期

- 预检、下载/复制、隔离 staging、验证、人工确认、原子安装。
- 升级、回滚、卸载、损坏修复和来源追踪。
- Tool/MCP/文件根目录/脚本依赖只做需求声明，不做预授权。
- 企业内部 Skill 的离线分发和灰度版本可见性。

### 4.4 P0：MCP 企业目录

- STDIO/Streamable HTTP 配置模板和 Server 能力协商。
- Resources/Prompts 产品化展示；其内容按不可信输入处理。
- OAuth 登录、刷新、失效、账号切换和删除后的凭据清理。
- 企业代理、证书链和网络策略适配；禁止任意 Header 注入。

### 4.5 P1：项目上下文与可复用工作流

- 支持用户级、工作区级、仓库级和目录级指令，兼容读取 `AGENTS.md`；采用“组织硬约束 > 项目规则 > 用户偏好 > 当前请求”的确定性优先级。
- 提供 Context Inspector，展示本 Turn 加载的指令、Skill、MCP Resource、记忆和压缩摘要的来源、预算及冲突，不展示系统提示词或 reasoning。
- 将常用流程沉淀为 Prompt/Skill 模板；支持显式触发、参数 schema、版本和来源追踪，避免再造一套与 Skill 重叠的命令体系。
- 提供声明式生命周期 Hook：`TurnStart`、`BeforeTool`、`AfterTool`、`BeforeCompact`、`TurnEnd`。首版只允许应用内置动作或已安装受信 Skill 的固定入口，不接受任意 Shell/URL。
- `BeforeTool` Hook 只能返回补充说明、拒绝或要求人工审批；普通 Hook 的“允许”不能越过 PermissionEngine/ApprovalCoordinator。

### 4.6 P1：上下文预算、压缩与摘要

- 使用 `ContextBudgetPlanner` 按模型上下文窗口和 token 计量统一分配 system、Tool Schema、项目规则、Skill/MCP、记忆、压缩快照、最近 Turns、当前输入和输出预留预算；字符数只作为 tokenizer/usage 不可用时的保守降级。
- 历史裁剪以完整 Turn 和完整消息为最小单位，不允许半条字符串、孤立 assistant 消息或拆散 Tool call/result/approval 因果组；优先移除已被有效快照覆盖的旧事件和可重取正文。
- 新增应用拥有的 `CompactionSnapshot`：覆盖原始 Item 区间，保存目标、当前状态、决策、约束、已完成工作、工件/证据引用、未解决问题、pending action/approval、工具因果引用、不确定项和下一步。
- 原始 Item 流继续作为不可变事实源；快照是派生工作上下文，不是 Task Checkpoint 或 Durable Memory。每个快照记录来源区间、源哈希、摘要模型、prompt/schema 版本、触发原因、校验状态和前一快照，可重新生成和回滚。
- 支持软阈值自动压缩、模型上下文硬阈值前强制压缩、`CONTEXT_LIMIT_EXCEEDED` 后最多一次恢复压缩，以及用户手动“立即压缩/重点保留”；P2 再灰度按计划阶段边界主动压缩。
- 压缩先生成候选快照，再执行 schema、来源、因果一致性、敏感信息和“未完成不得写成已完成”校验；校验失败继续使用最近有效快照与有界原始 Turns，不覆盖有效状态。
- 大 Tool Result 进入受管工件存储，模型上下文只保留有界摘要、稳定引用、内容哈希和重取方法；驱逐不能丢失审批、错误状态或完成证据。
- Provider 原生 compaction 通过可选适配器接入，仅保存可合法持久化的 opaque item/元数据并受 Provider 能力检测；跨 Provider 的默认路径始终是应用自有结构化快照。
- AgentScope 仅选择性复用同一 Run 内的压缩/溢出恢复能力，并使用自定义摘要契约、关闭压缩前自动记忆写入；跨 Turn 快照、审计、回滚和预算由应用负责。不得直接开启其默认 Session、Workspace Memory 或后台 Consolidator 形成双状态源或绕过记忆审核。

### 4.7 P1：计划、检查点与恢复

- 结构化任务计划：步骤、状态、依赖、验收条件、阻塞原因和用户 gate；计划是可见工作状态，不是隐藏 reasoning。
- 在高风险编辑、批量文件变更和上下文压缩前创建应用检查点；检查点包含会话状态、工作区变更清单、冻结的工具因果引用和必要的文件快照元数据。
- 支持会话分叉、从检查点恢复、恢复前差异预览和恢复后验证；外部消息、远端写入、安装和已完成 Tool 副作用默认不回放。
- 崩溃/重启后恢复到最近一致状态；无法证明幂等的 pending Tool 一律转为需要用户处理，不自动重试。

### 4.8 P1：受控 Subagent

- 首版只支持主 Agent → 单层 Subagent，不支持 Subagent 再委派、任意 A2A、动态拓扑或循环 Graph。
- 子任务使用独立上下文窗口，只接收最小任务包；回传结构化摘要、证据引用、产物和未解决问题，不把完整内部轨迹灌回主上下文。
- 每个 Subagent 显式声明角色、可用 Tool/MCP/Skill、读写根、模型档位、token/时间/并发预算和终止条件；实际权限取主任务权限、组织策略与子任务声明的交集。
- 默认提供只读 `researcher`、`reviewer`、`verifier` 三类模板；写操作仍逐项经过统一 ToolGateway 和审批，不能共享或继承一次性批准。
- 主任务取消、策略变化、会话关闭和应用退出必须向所有子任务传播取消，并形成可恢复的终态。

### 4.9 P1：可治理项目记忆 MVP

- 详见第 8 节。首版聚焦“项目事实、架构决策、用户纠正、工作偏好和已验证操作经验”，不保存密钥、原始 Tool 输出、完整文件正文、健康/财务等敏感画像或模型 reasoning。
- 支持显式“记住/忘记”、Turn 结束候选、人工批量审核、引用式召回、冲突提示、有效期和项目导出。
- 默认只启用 SQLite FTS5 + 元数据/时间/重要度重排；向量嵌入和图记忆保留扩展接口，但不作为二期默认依赖或 production 前置条件。

### 4.10 P1：治理、诊断与评测

- 策略模拟、冲突解释、批量禁用、灰度升级和回滚。
- 本地诊断中心、脱敏证据导出、错误码知识库和兼容性报告。
- Agent 运行观测：模型/Tool/Skill/MCP/Hook/Subagent/记忆检索的结构化 trace、耗时、token 和取消原因；不记录正文和 reasoning。
- 建立任务恢复、上下文压缩、摘要漂移、记忆召回/误召回、越权拒绝和提示注入回归集。
- 可选匿名遥测保持默认关闭，启用前必须单独取得明确同意。

### 4.11 P2：可延期增强，不阻塞二期 production

- 语义向量检索、可插拔 embedding、实体链接和图记忆实验。
- 后台记忆整理/“dreaming”实验，但必须先生成变更集并经用户审核，不能静默改写有效记忆。
- 在计划步骤完成、检索转实现、实现转验证或 Subagent 回传等安全边界尝试主动压缩；压缩前必须落盘检查点，禁止在未完成 Tool/编辑/验证中途触发。
- 任务队列和定时触发的设计预研；只做只读或草稿型任务，不在二期开放无人值守外部写入。
- 多工作树并行执行和远程沙箱预研；不进入二期默认安装包。

## 5. 明确非目标

- 公共开放市场、付费结算和第三方开发者门户。
- 多层 Multi-Agent、任意 A2A、复杂 Graph/Swarm、自主递归委派和跨设备 Agent 集群。
- 无人值守外部写入、自动发送消息、自动发布、自动安装或通用计划任务执行器。
- 通用 Shell、浏览器/Computer Use、远程沙箱和云端多租户同步。
- 不可检查的“全自动记忆”、默认上传云端的记忆、无限保留的用户画像和以记忆内容改变授权策略。
- 将完整聊天记录、原始 reasoning 或 Tool 原文整体向量化；本地 RAG/企业知识库仍作为后续独立业务方向。
- macOS/Linux/ARM64 正式发布。

## 6. 外部方案调研与取舍

> 调研快照：2026-10-18。GitHub Star 会持续变化，只用于确认社区关注度，不作为架构正确性的证明。

| 项目/产品 | 调研时关注度与可借鉴设计 | 二期取舍 |
| --- | --- | --- |
| Codex / OpenAI Agent 栈 | 支持 token 阈值自动压缩和显式 compact，压缩产物作为下一窗口的 canonical machine state；长期任务强调在阶段边界保留目标、已完成动作、工具结果、未决事项和下一目标 | 采用 token 预算、自动/手动触发和 canonical snapshot 思路；为支持多 Provider，默认使用应用可检查的结构化快照，不把 opaque Provider 状态当作唯一真相源 |
| Claude Code | 接近窗口上限自动 compact，支持 `/compact [focus]`、`/context`、rewind；压缩后重新注入规则、计划、记忆、最近工作文件等确定性上下文 | 采用带关注点手动压缩、Context Inspector、快照回滚和确定性重注入；不复制其任意命令 Hook，也不把自动记忆直接变成可信事实 |
| [AgentScope Java Harness](https://github.com/agentscope-ai/agentscope-java) | 当前依赖 `1.1.0-RC2` 已提供 Workspace Context、Session Persistence、两层 Memory、Compaction、overflow recovery 和 Tool Result Eviction；默认摘要包含 Intent/Summary/Artifacts/Next Steps，默认阈值为 50 条或约 80k token并保留 20 条 | 不整体打开默认能力。保留应用 SQLite Item 流为唯一事实源；选择性适配同 Run 压缩和大结果驱逐，关闭自动 Memory Flush/Consolidation、默认 Session 与 Workspace 注入，补齐来源、版本、审批因果、校验和回滚 |
| [OpenHands](https://github.com/OpenHands/OpenHands) | 高关注开源软件工程 Agent；项目 Microagent/仓库上下文与多种 condenser 展示了“项目知识”和“上下文压缩”应分层 | 采用项目级上下文、可替换压缩策略和引用保留；不引入其完整运行时/沙箱 |
| [Mem0](https://github.com/mem0ai/mem0) | 调研时约 66.9k Star；按 user/agent/run 分区，提供 add/search/update/delete/history，近期方案使用语义、关键词、实体和时间多信号检索 | 采用稳定 Memory API、scope、变更历史、时间有效性和可插拔检索；二期不直接引入 Python 服务或云端依赖 |
| [LangGraph](https://github.com/langchain-ai/langgraph) | 调研时约 43.2k Star；明确区分 thread-level checkpointer 与跨会话 long-term store，并用 namespace 隔离 | 采用“检查点 ≠ 长期记忆”、namespace 和可插拔 Store；不引入 Graph Runtime |
| [AutoGen](https://github.com/microsoft/autogen) | 调研时约 61k Star，但仓库已进入 maintenance mode；其 `Memory` 协议把 add/query/update_context/clear 与具体存储解耦 | 只借鉴接口解耦和 context injection 边界，不选作新增核心依赖 |
| [Letta Code](https://github.com/letta-ai/letta-code) | 调研时约 3.4k Star，关注度低于上述项目但属于 memory-first Agent；MemFS 使用可检查、可编辑、可版本化的文件记忆，并通过后台整理形成长期经验 | 采用“人可读 + 版本历史 + 整理候选”的思想；二期不接受无审核后台改写，也不依赖 Git 作为唯一存储 |

总体结论：不直接嵌入某个 Python Agent/记忆框架。iWork 使用 Java 接口 + SQLite/Flyway 实现最小闭环，保留外部 Memory Provider SPI；等本地基线通过安全、检索质量和删除语义门禁后，再评估 Mem0/向量库适配器。

## 7. 上下文预算与压缩设计

### 7.1 四类摘要与状态必须分离

| 对象 | 用途 | 是否模型生成 | 是否可恢复任务 | 是否进入长期记忆 |
| --- | --- | --- | --- | --- |
| Thread Preview | 会话列表展示 | 否，取最近可见用户消息 | 否 | 否 |
| Compaction Snapshot | 缩短当前 Thread 工作上下文 | 是 | 只能辅助，不能单独恢复 | 否 |
| Task Checkpoint | 恢复计划、工作区和副作用状态 | 否，以应用状态机/Tool 事件为准 | 是 | 否 |
| Durable Memory | 跨会话复用经治理的事实和经验 | 候选可由模型生成 | 否 | 是，经审核后 |

任何模型摘要都不能直接替代 Checkpoint，也不能自动升级为 Durable Memory。压缩摘要中的“已完成”必须能关联结构化 Tool/Event 或工件证据。

### 7.2 上下文组装路径

```text
SQLite 原始 Item/Event 流
  → ContextAssembler（按完整 Turn/工具因果组投影）
  → ContextBudgetPlanner（模型窗口 - 输出预留 - 固定区预算）
  → 最新 VALID CompactionSnapshot
     + 快照后最近完整 Turns
     + 当前计划/检查点摘要
     + 确定性项目规则
     + 按需 Skill/MCP/Memory/Artifact 引用
     + 当前用户输入
  → Provider Adapter
```

- 固定区先分配组织策略、系统约束、Tool Schema 和输出预留；动态区再按任务相关性分配规则、Skill、记忆、历史和工件。
- 每个注入块携带 `sourceType/sourceRef`、token 估算、优先级、截断状态和内容哈希；Context Inspector 使用同一份 manifest，避免 UI 与实际请求不一致。
- Provider 返回真实 usage 时校准估算器；没有 tokenizer/usage 的 Provider 使用保守系数并预留更高安全余量。

### 7.3 压缩触发与安全边界

- `SOFT_THRESHOLD`：预计下一不可分割步骤和输出预留无法放入剩余窗口时，在当前安全点生成快照。
- `HARD_THRESHOLD`：达到 M0 按模型冻结的最大占用比例后，在继续模型调用前强制生成快照。
- `OVERFLOW_RECOVERY`：Provider 明确返回上下文超限时，最多强制压缩并重试一次；重复失败返回稳定错误。
- `MANUAL`：用户可立即压缩或提供 bounded focus 指令；focus 只能影响内容取舍，不能修改策略、权限或事实校验。
- `PHASE_BOUNDARY`：计划步骤完成后的主动压缩属于 P2 灰度能力；未完成 Tool、编辑、审批、流式响应和验证期间禁止触发。

### 7.4 `CompactionSnapshot` 最小契约

至少包含：`snapshotId`、`threadId`、`coveredFromItemId/coveredToItemId`、`previousSnapshotId`、`sourceHash`、`schemaVersion`、`promptVersion`、`generatorProvider/model`、`trigger`、`createdAt`、`validationStatus`、`tokenBefore/tokenAfter`，以及以下结构化正文：

- `sessionIntent`、`currentState`、`decisions[]`、`constraints[]`；
- `completedWork[]`，每项可选绑定 `evidenceRefs[]`；
- `artifacts[]`，保存稳定引用、hash、状态和用途，不复制大正文；
- `openQuestions[]`、`pendingActions[]`、`pendingApprovals[]`；
- `toolCausalityRefs[]`、`uncertainties[]`、`nextSteps[]`。

状态至少支持 `CANDIDATE`、`VALID`、`SUPERSEDED`、`INVALID`。保存新 `VALID` 快照与更新当前指针必须在同一事务完成；原始 Item 不删除，回滚只切换有效快照指针。

### 7.5 AgentScope 集成边界

当前 `agentscope-harness:1.1.0-RC2` 的内置能力可作为实现参考或受控适配器，但不直接成为应用状态模型：

- `CompactionHook`：可用于同一 Run 内的 token/message 阈值压缩和一次 overflow recovery；必须替换默认 summary prompt，设置 `flushBeforeCompact(false)`，并把跨 Turn 产物回写应用 `CompactionSnapshot` 后才可成为后续上下文。
- `MemoryFlushHook/MemoryConsolidator`：默认会把压缩前事实写入 daily memory 并后台合并 `MEMORY.md`，与“候选审核后持久化”冲突，二期保持关闭。
- `SessionPersistenceHook`：会形成 Harness 自有恢复状态，与 SQLite Item/Checkpoint 双写，二期保持关闭；如未来接入，先实现应用 Session Adapter 和一致性迁移 ADR。
- `WorkspaceContextHook`：会注入 `AGENTS.md/MEMORY.md/KNOWLEDGE.md`，其 `maxContextTokens` 只是 workspace 注入预算而非模型总预算；二期由 `ContextAssembler` 统一处理优先级、scope、来源和 token 预算，默认保持关闭。
- `ToolResultEvictionHook`：只有在存储路径映射到应用受管 Artifact Store、保留 hash/ACL/删除语义且重取继续经过 ToolGateway 时才启用；否则沿用应用工件驱逐实现。
- `memory_search/memory_get/session_search`：不得因 Harness 默认 Toolkit 自动暴露；只有接入应用 Memory/Session API、通过 scope 与权限过滤后才可注册。

### 7.6 校验、观测与评测

- 快照校验：JSON Schema、覆盖区间/sourceHash、引用存在性、Tool/Approval 因果完整、敏感信息过滤、完成状态证据和前后矛盾检查。
- 运行观测：记录触发原因、token before/after、压缩耗时/费用、保留 Turn 数、裁剪块、校验结果、回退原因和后续一次任务成功率；不记录摘要正文和 reasoning 到普通诊断日志。
- 固定评测：长对话连续指代、代码路径/标识符、数字与日期、用户纠正、失败测试、pending approval、重复工具调用、跨 3～5 次压缩的漂移和 Provider 切换。
- 回归门禁：压缩后不得重复已产生副作用；不得把失败/取消/待验证改写为成功；不得丢失当前目标、硬约束、关键工件、未决问题和下一步。

### 7.7 验收指标

- 0 条半消息、孤立 assistant 或拆散的 Tool call/result/approval 因果组进入模型。
- 0 次上下文超限无限重试；每次 overflow 最多一次受控压缩恢复。
- 固定评测集上关键约束、未完成事项、文件/工件引用和用户纠正保留率达到 M0 冻结阈值。
- 连续 5 次压缩后无已完成状态反转、无副作用重复、无跨 Thread/Project 数据混入。
- 任意有效快照可追溯到不可变源区间并回滚；无效快照不覆盖最近有效状态。
- 关闭压缩 feature flag 时仍使用安全的完整 Turn token 裁剪，行为可预测且不产生新的持久化状态。

## 8. 项目记忆 MVP 设计

### 8.1 分层模型

| 层 | 作用域 | 示例 | 写入方式 | 注入方式 |
| --- | --- | --- | --- | --- |
| Working Context | 当前 Turn/Thread | 最近消息、Tool 摘要、当前计划 | Runtime 自动维护 | 最新有效压缩快照 + 快照后完整 Turns |
| Task Checkpoint | task/thread/step | 步骤状态、文件变更、pending approval | 应用状态机 | 仅恢复时读取，不参与普通语义检索 |
| Project Rules | org/workspace/repo/path | 架构约束、构建命令、安全规则 | 人工或受信包维护 | 启动/访问目录时确定性加载 |
| Durable Memory | project/user-local/agent-role | 决策、纠正、偏好、验证经验 | 显式写入或候选审核 | 检索后带引用、按预算注入 |

Project Rules 属于用户指令，不与 Agent 自动记忆混写；Task Checkpoint 用于恢复，不被检索器当作事实；Durable Memory 永远不能覆盖组织策略和当前用户请求。

### 8.2 记忆对象

每条 `MemoryRecord` 至少包含：`memoryId`、`scopeType/scopeId`、`kind`、`title`、`content`、`sourceType/sourceRef`、`confidence`、`importance`、`validFrom/validUntil`、`status`、`createdBy`、`createdAt/updatedAt`、`contentHash` 和 `revision`。

- `kind` 首版限定为 `FACT`、`DECISION`、`PREFERENCE`、`CORRECTION`、`PROCEDURE`、`WARNING`。
- `sourceRef` 只引用 Thread/Turn/用户确认/文件 hash 等稳定证据，不复制敏感正文。
- `status` 支持 `PROPOSED`、`ACTIVE`、`SUPERSEDED`、`EXPIRED`、`DELETED`；冲突默认并存并提示，不由模型静默覆盖。
- 变更使用 append-only revision；删除写 tombstone，并同步清除 FTS、缓存和后续导出。备份恢复必须尊重删除代次。

### 8.3 写入与整理

```text
显式“记住”或 TurnEnd 提取候选
→ secret/PII/正文/绝对路径过滤
→ 去重、冲突、来源和有效期检查
→ 用户审核（接受/编辑/拒绝）
→ ACTIVE revision + FTS 索引
→ 审计事件
```

- 默认只有用户显式“记住”可直接进入审核流；自动提取默认关闭，企业可强制关闭。
- Agent 自己声称完成的动作只有在结构化 Tool/Event 证据可验证时才能形成事实候选。
- 候选批处理有数量、token、时间和模型费用预算；失败只丢弃候选，不影响当前任务。
- 整理只生成 diff：合并重复、标记过期、建议拆分，不直接改变 `ACTIVE` 记录。

### 8.4 检索与上下文注入

1. 先按 `scope`、`kind`、状态、有效期和当前权限做硬过滤。
2. 使用 SQLite FTS5/BM25 召回，结合来源可信度、时间、重要度和近期使用做确定性重排。
3. 对矛盾记录同时返回并标记冲突；不得让“更新”自动掩盖历史决策。
4. 注入内容包含 `memoryId`、标题、摘要、来源和时间，受单条、总条数和总字符预算控制。
5. 用户可从回答中的引用打开、纠正或遗忘对应记忆；无相关结果时不注入占位内容。

向量/实体/图检索通过 `MemoryRetriever` SPI 后续接入。首版必须先建立关键词基线和离线评测，证明语义检索能提高召回且不显著增加误召回、延迟和隐私风险后才能默认启用。

### 8.5 安全、隐私与治理

- Memory 写入前复用 secret-name/value、绝对私有路径和内容大小过滤；发现密钥只记录拒绝原因，不保存原值。
- MCP Resource、网页、Skill 内容和 Tool 输出中的“请记住/忽略规则”不能自动形成记忆候选，除非用户显式确认且保留不可信来源标签。
- 记忆是模型上下文，不是策略。Tool 调用仍完整经过 ToolGateway、PermissionEngine 和 ApprovalCoordinator。
- 提供项目级“一键暂停记忆”“查看本次召回”“导出 Markdown/JSON”“全部遗忘”和保留期设置。
- 诊断包默认只含数量、类型、命中耗时和哈希，不含记忆正文；共享导出前再次执行脱敏预检。

### 8.6 验收指标

- 固定评测集上 `Recall@5`、`Precision@5`、冲突提示率、过期过滤率和引用正确率达到 M0 冻结阈值。
- 0 条跨项目、跨用户、跨 Subagent scope 泄漏；0 条密钥/Token/原始 reasoning 持久化。
- 删除后 FTS、缓存、导出和重启路径均不可检索；备份恢复不会复活已 tombstone 的记录。
- 记忆关闭时结果与一期上下文路径等价；记忆服务失败不阻塞普通对话。
- 1 万条项目记忆规模下，检索延迟和数据库体积满足 M0 性能预算。

## 9. 建议里程碑

| 里程碑 | 建议周期 | 核心交付 | 退出条件 |
| --- | --- | --- | --- |
| M0 基线与 ADR | 2 周 | production 差距清单；Catalog/签名/OAuth/策略；指令/Hook；上下文预算/压缩；检查点/Subagent；Memory ADR、威胁模型与评测集 | 契约、安全边界、迁移方案、P1 feature flag 和指标阈值评审通过 |
| M1 Catalog/信任 MVP | 3 周 | Source、manifest、签名/哈希、搜索与策略预览 | 不可信/篡改/回滚攻击全部失败关闭 |
| M2 Skill 生命周期 | 3 周 | 安装、升级、回滚、卸载、版本锁定、审计 | 原子性、重启恢复、权限不继承通过 |
| M3 MCP 企业目录 | 3 周 | 模板、Resources/Prompts、OAuth、撤销、代理/证书 | Renderer 零明文凭据、删除清理和账号切换通过 |
| M4 Agent 工作台增强 | 4 周 | 分层指令、Context BudgetPlanner、CompactionSnapshot、Context Inspector、计划、Hook、检查点/分叉、单层 Subagent | 完整 Turn 裁剪、压缩校验/回滚、一次 overflow 恢复、权限交集、取消传播和恢复零副作用重放通过 |
| M5 项目记忆 MVP | 3 周 | 候选审核、CRUD/history、FTS 检索、引用、冲突/过期、导出/遗忘 | 评测阈值、scope 隔离、删除语义、提示注入和降级门禁通过 |
| M6 Beta 与 production | 3 周 | 治理 UI、诊断、签名更新、扩大 Beta、production 证据 | P0 清零；默认开启的 P1 清零；签名包完整门禁和 8 小时长稳通过 |

总周期约 21 周。M4/M5 可在 M2 的持久化与安全契约冻结后由独立小组并行，但不得与 Catalog/MCP 各自发明第二套策略、审计、恢复或 secret 存储。详细排期应在 M0 ADR 完成后按实际团队人数冻结。

## 10. 工作流与质量门禁

- 每个里程碑按“设计冻结 → 单元/集成 → Electron 真实路径 → 安全反向用例 → 候选证据”推进。
- 数据库迁移只前进不回写；升级前备份，失败恢复旧版本可读状态。
- 任何新增执行路径必须经过 ToolGateway、PermissionEngine、ApprovalCoordinator；Hook、Subagent、恢复和记忆不得创建旁路。
- 每个候选绑定源码修订、安装器 SHA-256、SBOM、测试和用户证据；不得跨候选无条件复用。
- Beta 可使用明确记录的受控裁定；production 禁止使用候选差异或长稳证据继承。
- P1 能力全部置于独立 feature flag；未达到对应门禁时默认关闭，但不能用“默认关闭”替代安全测试。
- 记忆与 Subagent 增加专项红队：间接提示注入、跨 scope 泄漏、错误记忆放大、审批继承、取消竞态、资源耗尽和恢复重放。
- 上下文压缩增加专项红队：摘要提示注入、伪造完成状态、来源引用替换、跨 scope 混入、审批因果丢失、大 Tool Result 驱逐后越权重取和多次压缩漂移。

## 11. M0 必须新增或更新的 ADR

1. Catalog 索引、Artifact manifest、签名与信任根。
2. Organization Policy 来源、优先级、分发和冲突解释。
3. MCP OAuth、safeStorage 引用、撤销与代理/证书。
4. 分层项目指令、Context Inspector、上下文预算与确定性重注入。
5. CompactionSnapshot schema、触发策略、校验/回滚、Provider/AgentScope 适配和 Tool Result 驱逐。
6. 声明式 Hook 事件模型、允许动作和权限交点。
7. Task/Checkpoint/Workspace Snapshot 的一致性、恢复与副作用去重。
8. Subagent 任务包、权限交集、预算、取消树和结果协议。
9. Memory Store/Revision/Retriever SPI、scope、删除语义、保留期和评测方法。
10. production Authenticode、应用自动更新供应链和新增模块 feature flag 策略。

## 12. 需要产品负责人后续确认

这些决定不阻塞 M0 文档和技术预研，但在对应功能编码前必须冻结：

1. 企业 Catalog 首版只支持“离线包 + HTTPS 静态源”，还是需要对接现有制品库。
2. 组织策略由本机管理员文件导入，还是需要企业管理服务下发。
3. MCP OAuth 首批必须兼容的身份提供方和企业代理/证书环境。
4. 代码签名证书或受控签名服务的采购方案。
5. P1 能力是否跟随 production 默认开启；建议分层指令/检查点默认开启，Hook/Subagent/自动记忆候选默认关闭后灰度。
6. 项目记忆是否允许写入仓库形成团队共享记忆；建议二期只提供显式导出/导入，不自动修改仓库。
7. 记忆默认保留期、允许的敏感信息分类和企业“禁止记忆”策略。
8. Subagent 首批是否只提供只读模板，以及允许的最大并发、单任务时长和模型费用预算。
9. 自动压缩软/硬阈值、手动压缩入口是否默认开放，以及是否允许保存 Provider opaque compaction item；建议应用结构化快照默认开启，Provider 原生路径按模型能力灰度。
10. 二期团队人数与目标发布日期，用于把建议里程碑转换为正式周计划。

## 13. 参考资料

- [OpenAI：Codex 工作流与可复用 Skills](https://developers.openai.com/cookbook/examples/codex/iterating-development-workflows-with-codex)
- [OpenAI：长上下文 Compaction](https://developers.openai.com/api/docs/guides/compaction)
- [OpenAI：可靠 Agent 的 Memory 与 Compaction](https://developers.openai.com/cookbook/examples/agents_sdk/building_reliable_agents_memory_compaction)
- [OpenAI：Skills 与 MCP 的职责边界](https://developers.openai.com/plugins/concepts/skills)
- [Anthropic：Claude Code 项目指令与自动记忆](https://code.claude.com/docs/en/memory)
- [Anthropic：Claude Code Context Window 与 Compaction](https://code.claude.com/docs/en/context-window)
- [Anthropic：Claude Code Subagent](https://code.claude.com/docs/en/sub-agents)
- [Anthropic：Claude Code Hooks](https://code.claude.com/docs/en/hooks-guide)
- [AgentScope Java Harness：Overview](https://github.com/agentscope-ai/agentscope-java/blob/main/docs/v1/en/docs/harness/overview.md)
- [AgentScope Java Harness：Memory 与 Compaction](https://github.com/agentscope-ai/agentscope-java/blob/main/docs/v1/en/docs/harness/memory.md)
- [LangGraph：短期检查点与长期记忆](https://docs.langchain.com/oss/python/langgraph/add-memory)
- [Mem0：开源 Agent Memory Layer](https://github.com/mem0ai/mem0)
- [Letta Code：Memory & Dreaming](https://github.com/letta-ai/letta-docs-md/blob/main/configuration/memory/index.md)
- [AutoGen：Memory 协议](https://microsoft.github.io/autogen/stable/reference/python/autogen_core.memory.html)
