# 08 受管环境 1（MANAGED_WINDOWS_X64）安装生命周期 — 填写示例

> 用途：企业受管环境留档。命名前缀必须是 `env-managed-1-*`。
> 环境要求：企业镜像、域策略设备，或具有企业安全策略（如 EDR/强制策略）的受管环境。

## 受管环境额外关注点

| 关注点 | 检查方法 | 示例结果 |
|---|---|---|
| 安装器不被安全策略拦截 | 观察安装过程有无 EDR 拦截提示 | ✅ 未拦截 |
| 首启不触发告警 | 检查安全软件是否有新增告警 | ✅ 无告警 |
| 自更新/升级在策略下可用 | 执行升级路径 | ✅ 迁移正常 |
| 卸载与数据保留 | 同标准五项 | ✅ |
| 重装恢复 | 同标准五项 | ✅ |

## 留档正文示例

```
环境：MANAGED_WINDOWS_X64  env_c3d4e5f6a1b2（企业镜像 + 域策略）
2026-09-XX 11:00  freshInstall：非管理员安装成功，EDR 未拦截。
2026-09-XX 11:05  firstStart：首启正常，安全软件无新增告警。
2026-09-XX 11:18  upgrade：升级迁移正常。
2026-09-XX 11:30  uninstallRetention：数据按设计保留。
2026-09-XX 11:40  reinstallRecovery：恢复正常，MCP 不重复初始化。
结论：PASSED（5/5）
```

## 对应 user-evidence.json 片段

```json
{
  "environmentId": "env_c3d4e5f6a1b2",
  "kind": "MANAGED_WINDOWS_X64",
  "lifecycle": {
    "freshInstall": true,
    "firstStart": true,
    "upgrade": true,
    "uninstallRetention": true,
    "reinstallRecovery": true
  },
  "evidenceId": "ev_6d94b0c81e23"
}
```

> ⚠️ `kind` 只允许 `CLEAN_WINDOWS_X64` / `MANAGED_WINDOWS_X64` 两个枚举值，写错直接失败。
