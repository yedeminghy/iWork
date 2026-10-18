# 一期收口复核

> 最终复核日期：2026-10-18
>
> 候选身份：`iWork 1.0.0` / `com.candlefish.iwork` / `beta/unsigned`
>
> 安装器 SHA-256：`365f3034d1a9eddd0afd3ec25c3c519625d60bcd2a8ada8aa17b87eda9e7adc3`
>
> 源码修订：`source-sha256:269d7ca9ec7182b8f8d6682bf43bd05e67bf34c7ea79c471ba6f8d9e1755905a`
>
> 结论：`GO_INTERNAL_ONLY`

## 最终结论

一期在“受控 Beta/内部使用”范围内完成交付，可以进入二期总体规划和实施。该结论不等于 production 放行：当前候选未进行 Authenticode 签名，且最终候选采用了经批准的候选差异证据继承和长稳误判裁定；production 必须重新生成完整、不继承的运行与长稳证据。

本轮根据项目负责人要求停止重复执行多小时的 Maven、Electron 和 8 小时长稳，将已完成的完整运行证据、最终候选的测试注入差异静态验证、真实服务/安装确认和 10 人 Beta 反馈合并为单一 EvidenceRoot。轻量证据门禁返回码为 `0`，输出 `GO_INTERNAL_ONLY`。

## 已关闭门禁

| 门禁 | 最终证据 | 结论 |
| --- | --- | --- |
| 产品与法律身份 | iWork、正式 appId/Publisher、1.0.0、beta、EULA、隐私政策 | 通过 |
| Provider | DeepSeek、Qwen 最终候选确认及脱敏证据 ID | 通过 |
| MCP | STDIO、Streamable HTTP，重放调用为 0 | 通过 |
| Skill | 发现、激活、审批边界及真实样本确认 | 通过 |
| Windows 安装 | 两类干净 Windows x64 和一类受管环境的安装生命周期 | 通过 |
| Beta 反馈 | B01～B10 全部通过、无问题，P0/P1/P2/P3 均为 0 | 通过 |
| 长稳 | 28,841 秒、6,217 Turn；资源、数据库、终态及清零指标通过 | 通过裁定，仅限受控 Beta/Internal |
| 安全 | Provider/MCP/Skill、路径、敏感信息和包内容扫描 | 通过 |
| 候选差异 | 源候选完整运行 + 最终候选测试注入差异静态/类型/打包验证 | 通过裁定，仅限受控 Beta/Internal |
| Week 9 证据门禁 | 候选哈希、继承证据哈希、用户证据和矩阵一致性 | 返回 0，`GO_INTERNAL_ONLY` |

最终证据位于：

`evidence/week9/365f3034d1a9eddd0afd3ec25c3c519625d60bcd2a8ada8aa17b87eda9e7adc3/`

## 已确认的一期范围决定

1. `docx/xlsx/pptx` 不进入一期安装包；后续纳入前需重新确认再分发许可和 iWork 权限适配。
2. `xquant-indicator-mcp` 作为无凭据、幂等、不覆盖、不复活的初始 MCP 配置。
3. 内置 Skill 保持当前已验证集合，不因二期规划自动扩大授权。
4. Skill 文本、`allowed-tools`、Catalog/市场安装均不能授予执行权限；执行仍必须通过 ToolGateway、PermissionEngine 和 ApprovalCoordinator。
5. Authenticode、签名更新和 production 发布门禁转入二期。

## Production 未关闭项

以下事项不阻塞二期启动，但阻塞正式 production 发布：

1. 采购或接入与 Publisher 匹配的代码签名证书，完成主程序、安装器、时间戳、轮换和吊销验证。
2. 修复或接受记录当前生产依赖审计中的风险项，并重新生成 SBOM/漏洞报告。
3. 以拟发布 production 候选重新执行完整 Week 9 运行门禁和连续 8 小时长稳，不使用候选差异或长稳裁定继承。
4. 对 production 签名包重新执行三类 Windows 安装生命周期与升级/回滚验证。

## 二期启动决定

一期已满足受控 Beta 收口条件，二期从 2026-10-18 起允许启动。主功能固定为“企业 Skill/MCP 生态”，一期冻结的 schema/API/IPC/Catalog 和权限边界作为二期输入，不在二期中旁路或弱化。

二期总体规划见 [二期总体规划](../phase-2/PHASE_2_OVERALL_PLAN.md)，架构方案见 [二期架构设计](../phase-2/PHASE_2_ARCHITECTURE_DESIGN.md)。
