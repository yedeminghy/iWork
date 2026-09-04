# 01 DeepSeek Provider 真实样本 — 填写示例

> 用途：演示 DeepSeek 测试的**留档内容怎么写**。真实留档保存为 `archive/deepseek-<日期>.md`（截图/日志可同名另存），命名前缀必须是 `deepseek-`。

## 测试环境（脱敏后记录）

- 应用：iWork 1.0.0 beta/unsigned
- 候选 SHA-256：`546354edd199669f0c75da2897fe64fa9d6a546c0a855a98edca6f1eca74b546`
- OS：Windows x64（干净/受管环境均可，与安装矩阵独立）
- 密钥录入位置：iWork 设置页（密钥本身不记录、不截图）

## 测试项与结果记录格式

| # | 测试项 | 操作 | 结果 | 记录要点 |
|---|---|---|---|---|
| 1 | 正常流式 Chat | 发送多轮对话，确认逐 token 渲染 | ✅ | 记录轮次数与是否有中断 |
| 2 | Tool Calling | 触发一次工具调用并回填结果 | ✅ | 工具名、调用是否成功 |
| 3 | 取消 | 流式输出中点击停止 | ✅ | 界面状态是否归位 |
| 4 | 重试 | 对失败请求点重试 | ✅ | 重试后是否成功 |
| 5 | 错误凭据分类 | 录入错误密钥发起请求 | ✅ | 错误提示是否稳定、可理解 |
| 6 | 密钥泄漏检查 | 检查界面、日志、诊断导出 | ✅ | 三处均无密钥明文 |

## 留档正文示例（真实执行时替换为实际观察）

```
2026-09-XX 14:02  流式 Chat：连续 5 轮对话，token 逐字渲染，无卡顿。
2026-09-XX 14:05  Tool Calling：调用 web_search 成功，结果正确回填对话。
2026-09-XX 14:07  取消：第 3 轮输出中停止，界面 1s 内归位，无残留占位符。
2026-09-XX 14:09  重试：断网后请求失败，恢复网络点击重试，成功。
2026-09-XX 14:12  错误凭据：故意改错密钥末位，返回 401 分类提示，无堆栈泄漏。
2026-09-XX 14:15  泄漏检查：诊断导出、日志文件、设置界面均未发现 sk- 明文。
结论：PASSED
```

## 对应 user-evidence.json 片段

```json
{
  "providerId": "deepseek",
  "status": "passed",
  "evidenceId": "ev_3f2a1b9c4d5e"
}
```

> `evidenceId` 示例值仅演示格式，正式值 = `ev_` + 留档文件 SHA-256 前 12 位，由
> `node scripts/derive-week9-evidence-ids.mjs evidence/week9/archive` 自动生成。
> 注意：`providerId` 固定小写 `deepseek`，导入器按 `['deepseek','qwen']` 校验。
