# 10 P2/P3 遗留问题（beta.issues）— 填写示例

> 用途：演示验收结束时**仍需版本决策**的遗留问题怎么填。
> 写入 `user-evidence.json` 的 `beta.issues` 数组。

## 填写规则（导入器硬校验）

| 规则 | 说明 |
|---|---|
| 只填 P2/P3 | P0/P1 必须修复并回归后才能提交，历史放项目问题系统，不进 JSON |
| `status` 不能是 `open` | 必须已有决策（如 `accepted_with_decision` / `deferred`） |
| P2/P3 必须有 `ownerId` | 匹配 `^[a-z][a-z0-9_-]{2,63}$`（匿名负责人，不写真实姓名） |
| P2/P3 必须有 `targetVersion` | 匹配 `^[0-9][0-9A-Za-z._-]{0,31}$`，如 `1.0.1` |
| 详情脱敏 | 不写人员姓名、内部地址；问题描述一句话概括即可 |

## 示例（两条可直接参照）

```json
{
  "issues": [
    {
      "issueId": "issue_001",
      "severity": "P2",
      "status": "accepted_with_decision",
      "summary": "深色主题下诊断预览滚动条对比度不足",
      "ownerId": "ui_owner_01",
      "targetVersion": "1.0.1"
    },
    {
      "issueId": "issue_002",
      "severity": "P3",
      "status": "deferred",
      "summary": "Skill 激活音效在静音模式下仍然播放",
      "ownerId": "ux_owner_02",
      "targetVersion": "1.1.0"
    }
  ]
}
```

## 「无遗留问题」的写法

```json
"issues": []
```

## 常见错误对照

| ❌ 错误写法 | ✅ 正确写法 | 原因 |
|---|---|---|
| `"severity": "P1"` | 移出 JSON，修复回归后再提交 | P0/P1 必须清零 |
| `"status": "open"` | `"status": "deferred"` | open 状态会被拒绝 |
| `"ownerId": "张三"` | `"ownerId": "ui_owner_01"` | 匿名 ID，正则限制 |
| `"targetVersion": "TBD"` | `"targetVersion": "1.0.1"` | 必须以数字开头且已定版 |
