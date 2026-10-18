# iWork 二期架构设计：企业 Skill/MCP 生态

> 状态：总体方案，可进入 M0 ADR 和详细设计
>
> 继承基线：Java 21、Spring Boot WebFlux、Electron/React、SQLite/Flyway、AgentScope Harness 适配层、ToolGateway/PermissionEngine/ApprovalCoordinator

## 1. 设计原则

1. 本地优先：Catalog 索引、安装状态、策略和审计默认保存在本机；无云端服务也能使用离线企业包。
2. 内容不等于权限：安装、启用、激活、授权是四个独立状态。
3. 不可信供应链：远端索引、manifest、Skill 文本、MCP Resources/Prompts 和发布者元数据全部按不可信输入处理。
4. 凭据隔离：OAuth refresh token、Bearer token、代理凭据只进入 Electron safeStorage；Host 使用短时一次性 secret session。
5. 失败关闭：签名、哈希、版本、兼容性、策略或迁移校验失败时，不安装、不升级、不连接。
6. 可恢复：下载、staging、安装、升级、回滚和删除均有操作日志，异常退出后可确定性恢复或回退。

## 2. 逻辑架构

```text
Renderer
  └─ restricted preload IPC
       └─ Electron Main
            ├─ safeStorage / OAuth browser flow / native dialogs
            └─ loopback authenticated Host API
                 ├─ Ecosystem Catalog Service
                 ├─ Package Verification & Trust Service
                 ├─ Organization Policy Service
                 ├─ Skill Lifecycle Service ──> existing Skill runtime
                 ├─ MCP Directory/Auth Service ──> existing agent-mcp
                 ├─ Operation Journal & Audit
                 └─ ToolGateway → PermissionEngine → ApprovalCoordinator
```

建议新增 Maven 模块 `agent-ecosystem`，只承载 Catalog、信任、策略、包生命周期和领域契约；MCP 传输/OAuth 连接仍归 `agent-mcp`，实际 Skill 装载/执行仍归 `agent-runtime`，数据库实现仍归 `agent-persistence`。`agent-host` 只做应用编排和 API，不保存 SDK 特有类型。

## 3. 核心领域对象

| 对象 | 关键字段 | 约束 |
| --- | --- | --- |
| CatalogSource | id、kind、endpointRef、trustPolicyId、enabled | endpoint 规范化；不保存凭据明文 |
| CatalogEntry | ecosystemId、type、publisherId、name、versions | Skill/MCP 稳定身份与展示名分离 |
| ArtifactVersion | version、manifestHash、contentHash、signature、compatibility | 不可变；同版本不同 hash 拒绝 |
| TrustedPublisher | publisherId、keyId、publicKey/certRef、status | 支持撤销和生效时间 |
| OrganizationPolicy | source/publisher/entry/version/risk 规则 | deny 优先；规则命中可解释 |
| InstalledArtifact | ecosystemId、version、scope、state、sourceRevision | 安装不代表启用或授权 |
| OperationJournal | operationId、type、phase、old/new version、status | 不记录正文、参数、Token 或绝对私有路径 |
| McpCredentialRef | serverId、accountId、safeStorageRef、scopes、expiresAt | Renderer/SQLite 不得含明文 token |

Flyway 从一期 V8 之后新增迁移，具体表名和索引在 M0 数据 ADR 中冻结。所有审计只保存稳定 ID、策略结果、风险级别和脱敏 fingerprint。

## 4. Catalog 与包协议

### 4.1 企业 manifest

每个版本至少包含：schemaVersion、ecosystemId、type、name、version、publisher、最低 iWork 版本、内容 SHA-256、文件清单、Skill/MCP 元数据、依赖、风险声明和签名块。manifest 先做规范化再验签，禁止未声明文件、路径穿越、链接、大小写碰撞、设备名和超限内容。

签名算法、证书格式和密钥轮换需在 M0 形成 ADR；在 ADR 冻结前不得自行发明兼容格式。Artifact 内容签名与 Windows Authenticode 是两条独立信任链，不能互相替代。

### 4.2 获取流程

```text
刷新索引 → 校验 Source/响应上限 → 解析并净化元数据
→ 策略过滤 → 用户选择版本 → staging 获取
→ manifest/文件/hash/签名/兼容性校验
→ 展示权限与依赖 → 用户确认 → 原子安装 → 刷新运行时
```

HTTPS Source 只允许显式配置的主机和重定向策略；禁止 Cookie jar、任意请求头和从条目内容动态改变 endpoint。下载必须有大小、时间、并发和磁盘预算。

## 5. Skill 生命周期

- 复用一期 `SkillPackageValidator` 与固定 Skill roots，不允许 Catalog 绕开 staging。
- 升级先验证新版本并创建可回滚快照，再原子替换；正在运行的 Turn 使用冻结快照，下一 Turn 才可见新版本。
- 升级、回滚、来源变化或内容 hash 变化使旧的会话授权失效。
- 卸载先停用并阻止新激活；有活动 Turn 时进入 pending removal，完成或取消后再删除受管目录。
- 依赖脚本只能声明受支持的 PowerShell/Python/JavaScript 运行需求；不自动下载安装解释器或运行安装脚本。

## 6. MCP 目录与 OAuth

- MCP 模板只描述 transport、endpoint/command 模板、能力预期、OAuth metadata 和非秘密参数。
- STDIO 继续禁止 shell 拼接，command 必须来自受信模板或已有受控配置；工作目录和环境最小化。
- HTTP endpoint 必须通过协议、主机、证书和企业代理策略校验；不跟随跨主机授权重定向。
- OAuth 使用系统浏览器或受控外部授权窗口 + PKCE + state；回调只绑定 loopback 随机端口和单次会话。
- access/refresh token 只由 Main safeStorage 保存；Host 获得短时、用途绑定的一次性会话，不写 SQLite/日志/诊断包。
- Server 删除、账号切换、授权撤销、scope 缩减和 refresh 失败都必须清理凭据并断开现有连接。
- Resources/Prompts 只作为可选择的不可信上下文，默认不注入会话，也不能触发 Tool 或提升权限。

## 7. 组织策略

策略求值顺序固定为：系统硬拒绝 → 组织 deny → 来源/发布者/版本规则 → 用户可选项 → 一期 PermissionEngine。组织策略可以收紧用户能力，不能把一期 ASK/DENY 提升为自动允许。

首版建议支持：Source 白名单、Publisher 白名单/撤销、条目允许/禁用、版本范围、最低安全版本、风险上限、MCP endpoint 域名、OAuth scope 上限和强制升级提示。每次决定返回可展示的规则 ID 和原因，不暴露策略文件绝对路径。

## 8. Host、IPC 与事件契约

建议 Host API 分组：

- `/api/ecosystem/sources`：Source 增删改、刷新和状态。
- `/api/ecosystem/catalog`：搜索、详情、版本和风险预览。
- `/api/ecosystem/operations`：安装、升级、回滚、卸载和恢复状态。
- `/api/ecosystem/policies`：有效策略、模拟结果和冲突解释。
- `/api/mcp/oauth/sessions`：创建、查询、取消和撤销授权会话；永不返回 token。

长操作使用 operationId 和结构化事件流；事件只携带 ID、阶段、百分比、错误码和脱敏摘要。Renderer IPC 继续使用固定通道和 DTO allowlist，不开放任意文件、URL、Header 或命令参数转发。

## 9. 一致性与恢复

每个变更操作采用可恢复状态机：`PLANNED → STAGED → VERIFIED → COMMITTING → COMMITTED`，失败进入 `ROLLING_BACK → ROLLED_BACK/NEEDS_REPAIR`。文件切换和数据库状态要么通过同卷原子 move + 事务提交完成，要么在启动恢复时依据 journal 回退；任何中间状态都不能被运行时加载。

Catalog 刷新采用 generation swap：新索引全部校验成功后再替换上一代；网络失败保留最后已知安全 generation，并明确展示过期状态。

## 10. 安全与测试门禁

最低反向用例包括：篡改 manifest/内容/签名、同版本换包、Publisher 撤销、降级攻击、路径逃逸、链接与 TOCTOU、压缩炸弹、超限元数据、恶意 Markdown/HTML、OAuth state/PKCE 错误、回调重放、scope 扩张、token 泄漏、MCP 删除后复活、断电式中断和并发升级。

每个里程碑必须完成：

1. Java 单元/集成测试和 Flyway 从 V1/V8/上一版升级测试。
2. TypeScript 类型检查、生产构建和 restricted IPC 静态合同。
3. 真实 Electron 安装/升级/回滚/重启路径。
4. Provider/MCP/Skill 权限、取消、审批和零重放回归。
5. 敏感信息、绝对路径、正文和 token 扫描。
6. 候选 SHA-256、SBOM、签名状态和 EvidenceRoot 绑定。

Production 发布额外要求完整不继承门禁、连续 8 小时长稳、三类 Windows 签名安装矩阵以及 P0/P1 清零。

## 11. M0 必须形成的 ADR

1. Catalog 索引与 Artifact manifest 规范。
2. Artifact 签名算法、信任根、轮换与撤销。
3. Organization Policy 来源、优先级和更新机制。
4. MCP OAuth 流程、回调和 safeStorage 引用协议。
5. 安装/升级 journal、文件原子切换和数据库恢复。
6. AgentScope 稳定版升级评估；SDK 差异只能进入适配层。
7. production Authenticode 与应用自动更新供应链。

上述 ADR 通过后，再按里程碑拆分周级详细设计和代码任务。
