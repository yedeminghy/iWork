# 09 Beta 测试者执行记录 — 填写示例（单名）

> 用途：演示每名 Beta 测试者的留档格式。命名前缀必须是 `beta-<两位编号>-*`，
> 如 `beta-01-*.png`、`beta-01-script.md`。总人数 8~15，编号从 `beta-01` 连续排。

## 测试者 ID 生成（PowerShell）

```powershell
'tester_' + [guid]::NewGuid().ToString('N').Substring(0,12)
# 输出形如：tester_11a2b3c4d5e6
```

## 统一脚本覆盖项（每人必须全部执行）

| # | 脚本项 | 结果 | 记录要点 |
|---|---|---|---|
| 1 | 安装/启动 | ✅ | 首装+首启正常 |
| 2 | Chat | ✅ | 基本对话 |
| 3 | Provider | ✅ | 至少一家真实调用 |
| 4 | MCP | ✅ | 至少一次成功调用 |
| 5 | Skill | ✅ | 发现+激活 |
| 6 | 审批 | ✅ | 允许/拒绝各一次 |
| 7 | 取消/重试 | ✅ | 状态归位 |
| 8 | 诊断预览 | ✅ | 可打开且无敏感信息 |

## 留档正文示例（beta-01）

```
测试者：tester_11a2b3c4d5e6（匿名，不记录姓名）
环境类型：个人 Windows x64 设备
2026-09-XX 19:00  安装/启动：通过。
2026-09-XX 19:05  Chat：通过。
2026-09-XX 19:10  Provider：DeepSeek 流式对话通过。
2026-09-XX 19:14  MCP：STDIO 工具调用通过。
2026-09-XX 19:16  Skill：发现与激活通过。
2026-09-XX 19:18  审批：允许一次、拒绝一次，行为符合预期。
2026-09-XX 19:20  取消/重试：通过。
2026-09-XX 19:22  诊断预览：可打开，未见密钥/路径明文。
结论：PASSED（8/8）
```

## 对应 user-evidence.json 片段（testers 数组中的每一条）

```json
{
  "testerId": "tester_11a2b3c4d5e6",
  "result": "passed",
  "evidenceId": "ev_a02b5d8c31f7"
}
```

> ⚠️ 硬校验：
> - `testers` 数组长度必须在 **8~15** 之间；
> - `testerId` 匹配 `tester_[0-9a-f]{12,64}` 且互不重复；
> - 每条 `result` 必须为 `passed`（未完成脚本的测试者不计数，补跑后再加）。
