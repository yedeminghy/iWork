# 一期收口复核

> 复核日期：2026-09-02  
> 候选身份：`iWork 1.0.0` / `com.candlefish.iwork` / `beta`  
> 结论：`NO_GO`（尚不能声明一期全部交付）

## 已关闭项及范围决定

1. DeepSeek 真实验收、Qwen 真实验收、Windows 安装矩阵、真实 MCP 和真实 Skill 由项目负责人确认已完成。
2. 产品名、appId、Publisher、版本和图标已冻结并落地。
3. 新产品身份下的首装、旧数据识别、卸载保留和重装恢复由项目负责人确认复核通过。
4. `xquant-indicator-mcp` 已以无凭据、幂等、不覆盖和不复活的方式进入初始 MCP Catalog。
5. `indicator-extraction` 和经 iWork 安全边界适配的 `web-artifacts-builder` 已进入内置 Skill。
6. release manifest 已支持 `internal | beta | production`；`beta` 与 `internal` 必须显式标记为 unsigned，`production` 仍对签名失败关闭。
7. Temurin 21.0.12+8 Windows x64 官方归档 URL 和 SHA-256 已于 2026-09-01 再次对照 Eclipse Adoptium 同名 `.sha256.txt` 并写入 `runtime-lock.json`。
8. 项目负责人于 2026-09-02 明确 `docx/xlsx/pptx` 不进入一期安装包；相关授权和运行边界不再作为一期门禁，后续若重新纳入需单独完成授权与安全适配。
9. EULA 与隐私政策已由项目负责人确认并补充联系信息，正式文本校验通过；安装器已配置展示 EULA，二者均进入 release manifest 防篡改校验。
10. 新的 `beta/unsigned` 候选已构建并通过包内容校验，安装器 SHA-256 为 `546354edd199669f0c75da2897fe64fa9d6a546c0a855a98edca6f1eca74b546`。
11. 候选绑定的自动安全证据 `security-scan.json` 已生成并通过五类检查；Week 9 长稳执行器和用户证据导入器已补齐。
12. 首次正式长稳达到 28,841 秒、6,217 Turn，资源、数据库、终态和最终计数全部通过；唯一 `SOAK_EVENT_TIMEOUT` 已通过槽位计数证明归因于执行器复用 `ALLOW_THREAD`。原始失败汇总保持不变，并由 hash 绑定的 `soak-adjudication.json` 裁定为仅适用于受控 beta/internal。修复后的 60 Turn 回归通过；production 或外部审计仍需重新完成完整长稳。

## 未关闭门禁

| 编号 | 门禁 | 当前证据 | 关闭方式 |
| --- | --- | --- | --- |
| P1-C07 | 8～15 人受控 beta 反馈 | 工作区中无 `beta-feedback-summary.json` | P0/P1 清零，P2/P3 有责任人和版本决定，生成脱敏证据 |
| P1-C08 | 一期 beta 总门禁 | 用户侧证据草稿已建立但仍含 `TODO`，`compatibility-automation.json` 尚未生成 | 补齐 EvidenceRoot 后使 `verify-week9.ps1 -Profile beta` 返回 0 并输出 `GO_INTERNAL_ONLY` |

Authenticode 按项目负责人决定转入二期，它阻塞 production，但不单独阻塞受控 unsigned beta。

第 9 周证据的责任划分和关闭顺序见 [第 9 周最终证据责任清单](WEEK_9_EVIDENCE_RESPONSIBILITY.md)。
用户侧材料的填写方式见 [第 9 周用户侧证据提供指南](WEEK_9_USER_EVIDENCE_GUIDE.md)。

## 二期启动条件

二期主功能方向已决策为“企业 Skill/MCP 生态”。但根据本次复核，一期尚未全部交付，因此暂不生成二期总体方案和详细排期。表中其余门禁关闭后，以已冻结的一期 schema/API/IPC/Catalog 为输入开始二期设计。
