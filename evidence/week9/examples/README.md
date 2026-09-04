# Week 9 用户侧证据填写示例目录

> 本目录只用于**了解填写格式**，所有文件均为格式演示，不进入证据链、不可用于正式导入。
> 正式材料按 `evidence/week9/user-evidence.json` + `archive/` 留档目录两条线走。

## 文件清单

| 文件 | 对应项 | 真实留档命名前缀 |
|---|---|---|
| `01-deepseek-example.md` | DeepSeek Provider 真实样本 | `deepseek-*` |
| `02-qwen-example.md` | Qwen Provider 真实样本 | `qwen-*` |
| `03-mcp-stdio-example.md` | STDIO MCP 真实样本 | `mcp-stdio-*` |
| `04-mcp-http-example.md` | Streamable HTTP MCP 真实样本 | `mcp-http-*` |
| `05-skill-example.md` | Skill 真实验证 | `skill-*` |
| `06-env-clean-1-example.md` | 干净环境 1 安装生命周期 | `env-clean-1-*` |
| `07-env-clean-2-example.md` | 干净环境 2 安装生命周期 | `env-clean-2-*` |
| `08-env-managed-1-example.md` | 受管环境安装生命周期 | `env-managed-1-*` |
| `09-beta-tester-example.md` | 单名 Beta 测试者执行记录 | `beta-01-*` ~ `beta-15-*` |
| `10-issues-example.md` | P2/P3 遗留问题填写 | （写入 JSON 的 `beta.issues`） |
| `10-json-fill-example.json` | `user-evidence.json` 完整填写示例 | — |

## 正式工作流（示例目录不参与）

1. 执行测试 → 截图/录屏/日志按命名前缀放入 `evidence/week9/archive/`。
2. 运行 `node scripts/derive-week9-evidence-ids.mjs evidence/week9/archive`，得到 `evidence-id-map.json`。
3. 把 map 文件路径发给 Buddy → 填 `user-evidence.json` → 跑导入器 → 产出三份正式证据文件。

## 命名约定（导入与派生脚本依赖）

```
deepseek-*        qwen-*        mcp-stdio-*    mcp-http-*
skill-*           env-clean-1-* env-clean-2-*  env-managed-1-*
beta-01-* ~ beta-15-*   （beta 编号决定测试者计数，需 8~15 人）
```

## 三条硬规则

1. **evidenceId 一律由派生脚本生成**（`ev_` + 留档文件 SHA-256 前 12 位），示例中的 `ev_3f2a1b9c4d5e` 之类仅为演示格式。
2. **任何一项测试失败时**：提供复现步骤，不要把 JSON 改成通过。
3. **不得写入**：API Key、Bearer Token、Cookie、聊天正文、推理内容、用户名、计算机名、绝对路径（导入器会自动拒绝）。
