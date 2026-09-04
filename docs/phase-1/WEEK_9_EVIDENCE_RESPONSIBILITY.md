# 第 9 周最终证据责任清单

> 更新日期：2026-09-02  
> 目标候选：`iWork 1.0.0` / `beta` / unsigned  
> 原则：所有证据绑定法务确认后重新生成的最终安装器 SHA-256；不记录密钥、响应正文、推理内容、绝对路径、用户名或主机名。

## 一、责任划分

| 证据或工作 | 你必须处理 | 我可直接处理 | 你提供后我处理 |
| --- | --- | --- | --- |
| EULA 与隐私政策 | 已完成法务/隐私确认和联系信息补充 | 已完成定稿、草案标记清理、安装器接入、发布清单 hash 和构建阻断验证 | 已关闭 |
| 最终 beta 候选 | 已确认法律文本可发布 | 已构建 `beta/unsigned` 安装器并完成 release manifest、包内容和 hash 检查 | 已关闭；最终候选 SHA-256 为 `546354edd199669f0c75da2897fe64fa9d6a546c0a855a98edca6f1eca74b546` |
| DeepSeek/Qwen 真实样本 | 密钥只在应用设置页录入；不得发送到聊天或证据文件。对最终候选完成最小重验，或书面确认既有结果可沿用 | 运行不需要真实密钥的 Provider fixture、错误分类和安全回归 | 你提供脱敏结果/证据 ID 后，我生成绑定候选 hash 的 `compatibility-matrix.json` |
| MCP/Skill 真实样本 | 对最终候选完成最小重验，或确认既有结果可沿用 | 运行 STDIO/HTTP fixture、Skill 四作用域、三解释器、重连和零重放自动化 | 你提供脱敏结果/证据 ID 后，我汇总兼容矩阵 |
| Windows 安装矩阵 | 在至少 2 个干净 Windows x64 环境和 1 个企业/受管 Windows x64 环境上，对最终候选执行首装、首次启动、升级、卸载保留、重装恢复 | 提供统一脚本/记录模板并校验格式 | 你提供匿名环境 ID 和五项通过结果后，我生成 `install-matrix.json`；不能用旧候选 hash 冒充新候选 |
| 8 小时/500 Turn 长稳 | 已提供连续运行窗口并完成运行 | 已运行 28,841 秒、6,217 Turn；原始失败结果保留，执行器误判由 hash 绑定的 `soak-adjudication.json` 严格裁定，修复后 60 Turn 回归通过 | 受控 beta/internal 已关闭；production 或外部审计需重新完成完整长稳 |
| 安全总回归 | 无需提供秘密；如企业安全策略另有要求，请提供规则清单 | 已为最终候选执行合同、预览、包内容、法律和安全边界检查并生成 `security-scan.json`；剩余运行态检查随 Week 8 回归执行 | 现有静态/包级部分已关闭；你提供附加安全规则后我纳入检查 |
| 8～15 人受控 beta | 招募并授权 8～15 名真实测试者，发放最终候选，收集脱敏反馈和复现步骤 | 提供测试脚本，按 P0～P3 分级，修复代码问题，验证回归 | 你提供匿名测试者编号和反馈后，我生成 `beta-feedback-summary.json`；P0/P1 必须为 0，P2/P3 必须有责任人和版本决定 |
| Week 8 继承门禁 | 对需要真实环境的结果负责真实性确认 | 运行 Week 8→3 自动化回归，生成 `week8-verification.json` | 需要沿用人工验收结果时，由你提供候选绑定确认，我再归档 |
| Week 9 总门禁 | 确认最终证据内容真实、候选可进入受控 beta | 组装 EvidenceRoot，运行无 Skip 的 `verify-week9.ps1 -Profile beta`，修复自动化发现的 P0/P1，输出发布建议 | 前述外部证据齐备后我完成；unsigned beta 预期建议为 `GO_INTERNAL_ONLY` |

## 二、最终 EvidenceRoot 必需文件

最终目录必须全部绑定同一个安装器 SHA-256：

```text
evidence/week9/<candidate-sha256>/
  week8-verification.json
  compatibility-matrix.json
  install-matrix.json
  soak-summary.json
  security-scan.json
  beta-feedback-summary.json
```

`production-readiness.json` 对受控 unsigned beta 非必需；代码签名已转入二期，因此本期不可能得到 `GO_PRODUCTION`。

## 三、当前关闭顺序

1. **已完成：** 法律文本确认、最终 beta 安装器构建、候选 hash、包级安全证据。
2. 你完成最终候选的 Windows 安装矩阵、真实服务最小重验并启动 8～15 人测试；同时明确授权测试进程清理和 8 小时连续运行窗口。
3. 我完成长稳、运行态安全和 Week 8→3 全量自动化，汇总所有证据并修复 P0/P1。
4. 我运行无 Skip 的 Week 9 beta 总门禁；只有返回 0 且证据审计通过，一期才可标记完成并进入二期总体规划。

用户侧材料统一按 [第 9 周用户侧证据提供指南](WEEK_9_USER_EVIDENCE_GUIDE.md) 和 [`week9-user-evidence.template.json`](templates/week9-user-evidence.template.json) 提供。
