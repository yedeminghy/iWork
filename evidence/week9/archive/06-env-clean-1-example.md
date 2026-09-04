# 06 干净环境 1（CLEAN_WINDOWS_X64）安装生命周期 — 填写示例

> 用途：演示安装环境留档格式。命名前缀必须是 `env-clean-1-*`。
> 环境要求：干净 Windows x64 VM、一次性本地用户或等价隔离环境。

## 环境 ID 生成（PowerShell）

```powershell
'env_' + [guid]::NewGuid().ToString('N').Substring(0,12)
# 输出形如：env_a1b2c3d4e5f6
```

## 五项生命周期记录格式

| # | 生命周期项 | 操作 | 结果 | 记录要点 |
|---|---|---|---|---|
| 1 | freshInstall | 非管理员当前用户双击安装器 | ✅ | 安装路径、快捷方式符合预期 |
| 2 | firstStart | 首次启动 Host/Renderer | ✅ | 无永久加载、无崩溃循环 |
| 3 | upgrade | 从指定前序版本升级 | ✅ | 历史会话/配置/数据库迁移正常 |
| 4 | uninstallRetention | 卸载 | ✅ | 应用数据按设计保留 |
| 5 | reinstallRecovery | 重装 | ✅ | 保留数据恢复、MCP 不重复初始化、删除项不复活 |

## 留档正文示例

```
环境：CLEAN_WINDOWS_X64  env_a1b2c3d4e5f6
2026-09-XX 09:00  freshInstall：非管理员用户安装成功，开始菜单+桌面快捷方式出现。
2026-09-XX 09:05  firstStart：首启正常，Host/Renderer 均就绪，无加载循环。
2026-09-XX 09:20  upgrade：从前序版本升级，旧会话与配置完整，数据库版本号已迁移。
2026-09-XX 09:35  uninstallRetention：卸载后用户数据目录按产品设计保留。
2026-09-XX 09:45  reinstallRecovery：重装后数据恢复；MCP 未重复初始化；删除的会话未复活。
结论：PASSED（5/5）
```

## 对应 user-evidence.json 片段

```json
{
  "environmentId": "env_a1b2c3d4e5f6",
  "kind": "CLEAN_WINDOWS_X64",
  "lifecycle": {
    "freshInstall": true,
    "firstStart": true,
    "upgrade": true,
    "uninstallRetention": true,
    "reinstallRecovery": true
  },
  "evidenceId": "ev_e4b19d2c0735"
}
```

> ⚠️ 硬校验：`environmentId` 必须匹配 `env_[0-9a-f]{12,64}` 且三个环境互不重复；
> 五项生命周期任何一项未过就填 `false` 并附复现步骤，不要改成 true。
