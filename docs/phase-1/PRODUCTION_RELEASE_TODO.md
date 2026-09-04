# 生产发布资料与门禁待办

> 状态：正式身份与图标已冻结；发布通道已选择 `beta`，签名推迟到二期，当前仅允许受控 `beta/unsigned`  
> 建立日期：2026-08-25  
> 适用范围：第 8～9 周可使用 `internal/unsigned` 内测基线；本清单全部关闭前不得生成或声明 `production` 发布包。

## 1. 已确认的临时决策

1. 2026-09-01 项目负责人确认正式身份：产品名 `iWork`、`appId=com.candlefish.iwork`、Publisher `杭州烛光鱼有限公司`、版本 `1.0.0`。
2. 发布通道已确定为 `beta`；在 Authenticode 推迟到二期的前提下，当前构建必须保持 `beta/unsigned`，不得声明 production。
3. 品牌图标固定为 `build/icon.ico`；EULA 与隐私文本分别从 `packaging/legal/EULA_zh-CN.txt`、`packaging/legal/PRIVACY_zh-CN.md` 进入安装资源并纳入发布清单 hash。
4. 项目负责人确认 DeepSeek、Qwen、Windows 安装矩阵、真实 MCP、真实 Skill 以及 `iWork 1.0.0` 新身份安装复核已通过。
5. 代码签名与集中内测反馈转入后续阶段；这两项缺失时最终建议最多为 `GO_INTERNAL_ONLY`。

## 2. 待办清单

| 编号 | 待办 | 需要提供或确认的内容 | 关闭条件 | 阻塞范围 |
| --- | --- | --- | --- | --- |
| PR-01 | 正式发布身份 | 已确认 `iWork`、`com.candlefish.iwork`、`杭州烛光鱼有限公司`、`1.0.0`；已在安装器、manifest 与 About 落地 | 二期证书主体必须与 Publisher 匹配 | production |
| PR-02 | 品牌图标 | `build/icon.ico` 已入库、绑定 electron-builder 并完成候选包内容检查 | 在真实安装复核中确认安装器、可执行文件和快捷方式显示一致 | production |
| PR-03 | EULA | 已确认并定稿 `EULA_zh-CN.txt`，已接入 NSIS；草案标记和联系信息占位符会阻断构建 | 已关闭；后续文本变更仍需重新确认并重建候选 | 已关闭 |
| PR-04 | 隐私文本 | 已确认并定稿与当前真实数据流一致的 `PRIVACY_zh-CN.md` | 已关闭；新增遥测、云端能力或第三方数据流时必须重新评估 | 已关闭 |
| PR-05 | Authenticode | 暂未采购，明确转入二期 | CI/离线签名流程完成；安装器和主程序签名链、时间戳均验证成功 | production |
| PR-06 | JDK 官方归档锁 | Temurin 21.0.12+8 Windows x64 URL/hash 已再次对照官方 `.sha256.txt` 并写入 | 已关闭；下一候选构建仍执行 archive hash 与 jlink smoke | 已关闭 |
| PR-07 | 真实安装证据 | 项目负责人已确认 `iWork 1.0.0` 新身份复核无问题 | 已关闭；后续每个新 beta hash 仍需安装 smoke | 已关闭 |
| PR-08 | 生产重建与回归 | 使用冻结身份、官方 JDK 锁和证书重新构建 | 新 manifest/hash/signature、N→N+1 升级及 W8/W9 回归全部通过 | production |

2026-09-02 法律文本确认并包含取消竞态修复的 beta 候选：`iWork-1.0.0-windows-x64.exe`，SHA-256 `546354edd199669f0c75da2897fe64fa9d6a546c0a855a98edca6f1eca74b546`，Authenticode 状态 `NotSigned`，候选仅可用于受控 beta；旧候选不得用于最终 Week 9 证据。

## 3. 已预选的 JDK 归档值

第 8 周第一次重建内部候选时，优先使用以下已选值补齐 `packaging/runtime-lock.json`，无需等待 Publisher。归档 hash 已于 2026-08-25 对照 Eclipse Temurin 官方同名 `.sha256.txt` 复核：

```text
downloadUrl = https://github.com/adoptium/temurin21-binaries/releases/download/jdk-21.0.12%2B8/OpenJDK21U-jdk_x64_windows_hotspot_21.0.12_8.zip
archiveSha256 = 9ba963ee2371874a74185d18bc7bb2ab9407df7683300855ed7606e0662321d0
```

落库前构建脚本必须再次读取同版本[官方 `.sha256.txt`](https://github.com/adoptium/temurin21-binaries/releases/download/jdk-21.0.12%2B8/OpenJDK21U-jdk_x64_windows_hotspot_21.0.12_8.zip.sha256.txt) 并进行精确十六进制比较；任何重定向到非 HTTPS、文件名变化或 hash 不符均 fail closed。该值写入后会改变源码 revision，现有 `0.1.1` 内部安装器不能作为新 revision 的构建证据，必须重建并更新 `release-artifacts.json`。

## 4. 正式身份冻结后的影响检查

正式 `appId`、产品名或 Publisher 任一变化，都必须检查：

- 安装目录、卸载注册项、快捷方式和单实例标识是否发生变化；
- Electron `userData`/`safeStorage` 路径是否变化，旧密文能否读取；
- `appHome`、SQLite、MCP、Skill、文件根和历史会话是否仍被原位识别；
- 旧内部身份是否需要显式迁移或并存，禁止静默创建第二份用户数据；
- previous-release fixture、manifest、安装截图、隐私文本和支持文档是否全部更新。

若正式身份导致数据路径变化，必须单独设计显式迁移，不得把复制用户目录写进 NSIS 的无提示步骤。
