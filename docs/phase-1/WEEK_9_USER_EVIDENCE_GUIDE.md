# 第 9 周用户侧证据提供指南

> 最终候选：`iWork 1.0.0 beta/unsigned`  
> 安装器 SHA-256：`546354edd199669f0c75da2897fe64fa9d6a546c0a855a98edca6f1eca74b546`  
> 填写模板：[`templates/week9-user-evidence.template.json`](templates/week9-user-evidence.template.json)

## 一、提交方式

1. 复制模板为 `evidence/week9/user-evidence.json`。
2. 保持 `candidateSha256` 不变，只替换测试结果、匿名 ID 和证据 ID。
3. 不要写入 API Key、Bearer Token、Cookie、聊天正文、推理内容、用户名、计算机名或绝对路径。
4. 截图、录屏和日志可由你本地留档；JSON 中只写一个可追溯但不含个人信息的 `evidenceId`。
5. 填写完成后只需告诉我文件路径，我会运行导入校验并生成三个正式证据文件。

匿名环境 ID 可在 PowerShell 中生成：

```powershell
'env_' + [guid]::NewGuid().ToString('N').Substring(0,12)
```

匿名测试者 ID 可生成：

```powershell
'tester_' + [guid]::NewGuid().ToString('N').Substring(0,12)
```

## 二、Windows 安装矩阵

必须提供三个互不重复的环境：

- 两个 `CLEAN_WINDOWS_X64`：干净 Windows x64 VM、一次性本地用户或等价隔离环境。
- 一个 `MANAGED_WINDOWS_X64`：企业镜像、域策略设备或具有企业安全策略的受管环境。

每个环境使用本候选安装器完成并记录：

1. `freshInstall`：非管理员当前用户首装成功，安装路径和快捷方式符合预期。
2. `firstStart`：首次启动 Host/Renderer 正常，无永久加载或崩溃循环。
3. `upgrade`：从指定前序版本升级，历史会话、配置和数据库迁移正常。
4. `uninstallRetention`：卸载后应用数据按产品设计保留。
5. `reinstallRecovery`：重装后能够恢复保留数据，MCP 不重复初始化，用户删除项不复活。

五项全部通过后填 `true`。任何一项失败都先提供复现步骤，不要把 JSON 改成通过。

## 三、真实 Provider、MCP 和 Skill

### Provider

在最终候选内分别对 DeepSeek、Qwen 执行：

- 正常流式 Chat；
- 一次 Tool Calling；
- 取消与重试；
- 错误凭据或不可用模型的稳定错误分类；
- 确认界面、日志和诊断中没有出现密钥。

全部通过后填写 `status: "passed"`。密钥只在 iWork 设置页录入，不得写入模板或聊天。

### MCP

分别提供一个真实 STDIO 和一个真实 Streamable HTTP 样本，验证：

- 连接和 Tool Catalog；
- 至少一次成功调用；
- 取消或超时；
- 断开后重连；
- 失败调用不自动重放，`replayedCalls` 必须为 `0`。

### Skill

确认一期保留的真实 Skill 能在最终候选中发现、激活、按需读取资源并遵守审批边界，填写 `realSamples.skills`。

## 四、8～15 人受控 Beta

每名测试者使用统一脚本，至少覆盖：安装/启动、Chat、Provider、MCP、Skill、审批、取消/重试和诊断预览。模板中的 `testers` 需要扩展为 8～15 条，每人使用匿名 `testerId` 和唯一 `evidenceId`。

`issues` 只填写验收结束时仍需版本决策的 P2/P3 问题；每项必须有匿名 `ownerId` 和 `targetVersion`。P0/P1 必须修复并回归通过后才能提交最终材料，其历史可保存在项目问题系统中，不要把包含人员姓名或内部地址的详情复制到证据 JSON。

## 五、长稳运行授权

长稳由我执行，但需要你明确提供机器窗口。直接回复下面这句话即可：

> 允许终止本轮由项目 `node_modules/electron/dist/electron.exe` 启动的 Week 9 测试残留进程，并从现在开始连续运行 8 小时；期间测试机不休眠、不重启。

该授权只覆盖本项目本轮测试进程，不覆盖其他 Electron 应用。长稳使用 fixture，不需要向我提供 Provider 密钥；如决定同时跑真实服务，应先在应用设置页完成配置并另行说明。

## 六、导入和验收

收到材料后，我会执行：

```powershell
node scripts/import-week9-user-evidence.mjs evidence/week9/user-evidence.json 546354edd199669f0c75da2897fe64fa9d6a546c0a855a98edca6f1eca74b546
```

导入器会拒绝候选 hash 不一致、环境不足、生命周期不完整、测试者不足、P0/P1 未清零、P2/P3 无负责人/版本决定以及包含密钥或私有路径的数据。
