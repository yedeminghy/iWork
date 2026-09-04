# iWork

本地优先的 AI Agent 桌面应用。iWork 由 Electron/React 桌面端与 Java 21 Host 组成：Host 仅在本机 loopback 地址上运行，负责模型、工具、Skill、权限和 SQLite 持久化；桌面端提供会话、设置、审批和输出物预览界面。

> 当前版本面向内部受控使用。正式生产发布仍需完成代码签名、正式产品身份、法务材料以及干净 Windows 环境的安装/升级验收。

## 功能概览

- 流式对话与持久化会话：支持新建、搜索、重命名、归档、恢复和重启后历史恢复。
- Provider 与 MCP：支持受控 Provider 配置、STDIO 与 Streamable HTTP MCP，以及结构化 Tool Card 时间线。
- Skill：支持内置、用户和工作区三类 scope；可显式/隐式激活、按需读取资源，并从本地目录或公开 GitHub 仓库预览后安装。
- 权限与审批：文件、Skill 脚本和高风险 MCP 操作统一经过权限引擎，可单次允许、当前会话允许或拒绝。
- 工作区文件与输出物：受限根目录下的文本读写；DOCX、XLSX、PDF 和 HTML/Markdown 输出物卡片与本地预览。
- Windows 交付：提供 Electron 打包、发布清单和升级/恢复相关自动化验证。

## 安全边界

- Java Host 只监听随机 loopback 端口，并校验 Bearer token 与应用协议版本。
- Renderer 不直接获得 Node.js、文件系统、Shell 或通用网络能力；所有 IPC 均为受限接口。
- Provider 密钥只经应用设置录入和本地安全存储管理；禁止提交到仓库、命令行、环境变量、日志或测试证据。
- Skill 的脚本、`allowed-tools` 与 MCP 声明不会自动授予执行权限；副作用操作默认需审批。

## 项目结构

| 目录 | 说明 |
| --- | --- |
| `agent-host` | Spring Boot Host、HTTP API、应用安全边界与生命周期管理 |
| `agent-runtime` | 模型、工具、权限、Skill 与工作区运行时 |
| `agent-mcp` | MCP 传输、客户端管理与结果脱敏 |
| `agent-persistence` | SQLite/Flyway 持久化 |
| `desktop-shell` | Electron Main、preload 与桌面集成 |
| `desktop-ui` | React/Vite Renderer |
| `skills` | 内置 Skill 包 |
| `scripts` | 构建、打包和分周验证脚本 |
| `docs/phase-1` | 架构、设计、验收与发布文档 |

## 环境要求

- Windows 10/11 x64
- JDK 21
- Maven 3.9+
- Node.js 与 npm（推荐使用项目锁定的依赖版本）

## 安装依赖与构建

```powershell
npm install
mvn clean verify
npm run typecheck
npm run build
```

`npm run build` 会先准备 Office 预览运行时，再构建 Renderer 与 Electron Shell。构建产物、依赖、安装包和本地数据库均由 `.gitignore` 排除。

## 本地开发

先打包 Java Host：

```powershell
mvn -pl agent-host -am package
```

在一个终端启动 Renderer：

```powershell
npm run dev
```

再在另一终端启动 Electron：

```powershell
$env:AGENT_DESKTOP_DEV_URL = 'http://127.0.0.1:5173'
$env:AGENT_DESKTOP_JAVA = 'C:\Program Files\Java\jdk-21\bin\java.exe'
$env:AGENT_DESKTOP_HOST_JAR = (Resolve-Path '.\agent-host\target\agent-host-0.1.0-SNAPSHOT.jar')
npx electron desktop-shell/dist/main.js
```

请按本机 JDK 实际安装位置调整 `AGENT_DESKTOP_JAVA`。

## 验证

日常完整回归入口：

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\verify-week4.ps1
```

分阶段验收脚本位于 `scripts/verify-week2.ps1` 至 `scripts/verify-week9.ps1`。第 9 周脚本的 `commit`、`matrix`、`soak` 与 `beta` profile 分别覆盖提交门禁、安装矩阵、长稳运行和最终受控内测门禁；使用任何 `-Skip*` 参数都会将结果标记为 `development-only`。

## Windows 打包

```powershell
npm run package:win
```

产物位于 `dist-release/`，其中包括安装器、`win-unpacked/`、发布清单和产物摘要。当前内部候选包未签名，不能作为正式生产发布依据。

## 文档

- [一期总体设计](docs/phase-1/PHASE_1_DETAILED_DESIGN.md)
- [实施状态](docs/phase-1/IMPLEMENTATION_STATUS.md)
- [第 9 周发布门禁](docs/phase-1/WEEK_9_DETAILED_DESIGN.md)
- [生产发布待办](docs/phase-1/PRODUCTION_RELEASE_TODO.md)
