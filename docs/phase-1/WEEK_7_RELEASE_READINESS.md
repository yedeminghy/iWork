# 第 7 周发布就绪清单

更新日期：2026-08-25

## 1. 当前结论

第 7 周当前状态为 **development-only / 未完成**。内部未签名候选包、previous-release 夹具、升级数据库矩阵、崩溃恢复、零重放和历史回归均已实现并通过；真实安装/升级/卸载验收尚未在一次性 Windows 用户或 VM 中执行，生产发布身份与签名材料也未提供。

只有 `verify-week7.ps1` 在不使用 `-SkipPackage`、`-SkipInstallAcceptance` 的情况下返回 0，且 W7-A01～W7-A12 全部具备证据，才允许把本周标记完成。2026-08-25 项目负责人接受 `internal/unsigned` 风险并明确批准先开展第 8 周设计与开发；该例外不改变本周状态，也不允许向内测者分发未通过真实安装门禁的候选包。

## 2. 候选产物与证据

| 项目 | 当前值 |
| --- | --- |
| 当前版本 | `0.1.1` |
| 渠道 | `internal` |
| 签名状态 | `unsigned` |
| 安装器 | `dist-release/agent-desktop-0.1.1-windows-x64.exe` |
| 安装器 SHA-256 | `edda3b71dd35ffcd09d392b76a192feea6c048ef2c7940f5bb37b286595c4424` |
| source revision | `source-sha256:64394358f0cc43ba85b67136ecb6ab25d93ac8332a11093e5755ba1e6243eca0` |
| previous release | `0.1.0` |
| previous SHA-256 | `902b2e1e1d78c24634d385d550a47f9a9c7ad655a5fa2df33693164393371398` |
| 自动化摘要 | `dist-release/week7-verification.json`，`result=passed`、`releaseGate=development-only` |
| 生产依赖审计 | 7 个 production dependencies，0 个已知漏洞 |

`dist-release` 是本地构建输出，不作为源码资产提交；每次重新构建后必须以 `release-artifacts.json` 为准更新本表，不得沿用旧 hash。

## 3. W7-A01～W7-A12 状态

| 验收项 | 状态 | 证据或剩余动作 |
| --- | --- | --- |
| W7-A01 可重复发布物 | 已通过 | 完整构建、版本一致、NSIS/unpacked/manifest/hash 和包内容白名单返回 0 |
| W7-A02 当前用户安装 | 待外部环境 | 一次性非管理员 Windows 用户/VM，断网且无全局 Java/Node/Maven执行首次安装 |
| W7-A03 包内运行时 | 已通过 | 包内 JRE/Host/Skills/Renderer hash 校验；篡改和环境覆盖 fail closed |
| W7-A04 首次启动 | 待外部环境 | 从真实安装目录启动并完成生产 Renderer→Main→Host 会话 |
| W7-A05 显式升级 | 待外部环境 | 真实安装 `0.1.0` 后升级 `0.1.1`，验证 Thread/Item、MCP、密钥密文、Skill、roots 与 appHome |
| W7-A06 数据迁移 | 已通过 | V1～V6→V7、备份 manifest、失败锁定、release state 与保留策略自动化 |
| W7-A07 崩溃恢复 | 已通过 | 最终打包产物验证 Host/Main/Renderer、父进程 EOF、单次恢复和 crash-loop |
| W7-A08 零重放 | 已通过 | workspace 写入、UNKNOWN MCP、Skill 脚本三类待审批副作用均未重放 |
| W7-A09 安全卸载 | 待外部环境 | 真实卸载验证安装资源删除，appHome/workspace/外部 sentinel 保留，随后重装恢复 |
| W7-A10 发布完整性 | 内部渠道通过 | internal unsigned 隔离和 production fail-closed 已通过；生产渠道仍需正式签名材料 |
| W7-A11 隐私诊断 | 已通过自动化部分 | 包/日志/manifest/命令输出 sentinel 扫描通过；安装日志和注册表需随真实安装补证 |
| W7-A12 自动化回归 | 部分通过 | 跳过安装时总门禁和 W6 完整回归返回 0；必须补一次无跳过返回 0 |

## 4. 真实安装验收前置条件

验收环境必须是可丢弃的 Windows x64 VM 或一次性本地用户，不得使用开发者日常账户。环境需满足：

1. 当前用户无管理员权限，未预装本产品，不存在本产品卸载注册项和默认安装目录。
2. 断开网络；PATH 不依赖全局 Java、Node 或 Maven。
3. 设置 `AGENT_DESKTOP_INSTALL_ACCEPTANCE=1`。
4. 设置 `AGENT_DESKTOP_ACCEPTANCE_USER_SID` 为该一次性用户的真实 SID；脚本会拒绝不匹配的用户。
5. 执行 `powershell -ExecutionPolicy Bypass -File .\scripts\verify-week7.ps1`，不得携带任何 skip 参数。
6. 保存 `dist-release/week7-verification.json`、安装日志、卸载注册项摘要与最终退出码；确认摘要为 `releaseGate=week7-complete`。

## 5. 生产发布材料缺口

下列值必须由项目负责人或发布负责人明确提供，不能由开发代理推测：

| 材料 | 当前状态 | 要求 |
| --- | --- | --- |
| 正式 `appId` | 待提供 | 反向域名且归属明确；当前 `dev.agentdesktop.desktop` 仅为内部占位 |
| 正式产品名 | 待提供 | 当前 `agent-desktop` 仅为内部名 |
| Publisher/公司法定名称 | 待提供 | 与代码签名证书主体和安装器显示一致 |
| Windows `.ico` | 方案 C 已入库，待候选包复核 | `build/icon.ico` 包含 16～256 px 多尺寸并已绑定 electron-builder；需在下一候选验证安装器、可执行文件和快捷方式显示 |
| EULA | 待提供/确认不需要 | 若启用必须由法务确认版本与分发方式 |
| 隐私文本/链接 | 待提供 | 与本地数据、诊断和 Provider/MCP 数据流一致 |
| Authenticode 证书 | 待提供 | 证书主体、私钥安全注入方式、时间戳服务器和轮换负责人 |
| 官方 JDK 归档锁 | 已预选，待落库重建 | URL/hash 已在生产待办中复核；`runtime-lock.json` 当前仍为空，第一次 W8 候选构建前必须补齐并验证 |

生产材料补齐后必须使用 `-Channel production` 构建；任一 JDK 归档锁或 Authenticode 校验缺失均应 fail closed。生产签名包不得复用本页记录的 internal unsigned hash。

正式身份、品牌、法务、签名、JDK 锁与生产重建动作已集中登记在 [生产发布资料与门禁待办](PRODUCTION_RELEASE_TODO.md)。正式 Publisher 确定前保持内部占位身份，身份/品牌/法务/签名不阻塞第 8 周开发；JDK 锁则按待办在第一次 W8 内测候选构建前落库。

## 6. 第 8 周例外决策与后续门禁

原判定要求无 skip 的 W7 门禁后才进入第 8 周。项目负责人于 2026-08-25 作出一次性范围例外，现按以下规则执行：

1. 允许创建并实施 [第 8 周详细设计](WEEK_8_DETAILED_DESIGN.md)，范围严格限于总体方案的“兼容性、稳定性与内测”。
2. 第 7 周继续标记 `development-only / 未完成`；W7-A02/A04/A05/A09/A11/A12 状态不做虚假更新。
3. W8 可使用 unpacked 或 internal unsigned 包做开发验证，但向 3～5 名内测者分发前，无 skip 的 W7 门禁必须返回 0。
4. 当前 `0.1.1` 不能批准为 production 或正式 previous-release 基线；补齐官方 JDK 归档锁并重建后，以新 manifest/hash 为候选证据。
5. 正式 Publisher、图标、EULA/隐私和 Authenticode 按生产待办关闭，未关闭前 production 构建继续 fail closed。
