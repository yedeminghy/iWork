# 07 干净环境 2（CLEAN_WINDOWS_X64）安装生命周期 — 填写示例

> 用途：第二台干净环境留档。命名前缀必须是 `env-clean-2-*`。
> 流程与干净环境 1 完全相同（见 `06-env-clean-1-example.md` 的五项记录格式）。

## 与环境 1 的差异要求

1. **必须使用不同的 isolation 来源**：例如环境 1 用 VM 快照、环境 2 用一次性本地用户，避免同源污染。
2. **升级路径可不同**：环境 1 从 A 版本升级、环境 2 从 B 版本升级，覆盖面更广。
3. **环境 ID 必须重新生成**，不得复用环境 1 的值：

```powershell
'env_' + [guid]::NewGuid().ToString('N').Substring(0,12)
```

## 留档正文示例

```
环境：CLEAN_WINDOWS_X64  env_b2c3d4e5f6a1（一次性本地用户隔离）
2026-09-XX 10:00  freshInstall：通过。
2026-09-XX 10:04  firstStart：通过。
2026-09-XX 10:15  upgrade：通过（从另一前序版本升级）。
2026-09-XX 10:28  uninstallRetention：通过。
2026-09-XX 10:38  reinstallRecovery：通过。
结论：PASSED（5/5）
```

## 对应 user-evidence.json 片段

```json
{
  "environmentId": "env_b2c3d4e5f6a1",
  "kind": "CLEAN_WINDOWS_X64",
  "lifecycle": {
    "freshInstall": true,
    "firstStart": true,
    "upgrade": true,
    "uninstallRetention": true,
    "reinstallRecovery": true
  },
  "evidenceId": "ev_f08a3c6e1297"
}
```

> 导入器要求至少 2 个 `CLEAN_WINDOWS_X64` + 1 个 `MANAGED_WINDOWS_X64`，ID 全局唯一。
