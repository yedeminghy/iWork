# Agent Desktop

一期实现中的本地优先 AI Agent 桌面应用。当前代码完成 Java Host 的首个可运行闭环：安全 bootstrap、loopback HTTP 鉴权、SQLite 持久化、Mock Provider 的 AG-UI/SSE 流式输出与取消。

设计基线见 [`docs/phase-1/PHASE_1_DETAILED_DESIGN.md`](docs/phase-1/PHASE_1_DETAILED_DESIGN.md)，第 6 周执行设计见 [`docs/phase-1/WEEK_6_DETAILED_DESIGN.md`](docs/phase-1/WEEK_6_DETAILED_DESIGN.md)。

## 构建

要求 JDK 21 与 Maven 3.9+：

```powershell
$env:JAVA_HOME = 'C:\Program Files\Java\jdk21.0.10'
$env:Path = "$env:JAVA_HOME\bin;D:\03.Softwares\apache-maven-3.9.16\bin;$env:Path"
mvn clean verify
```

任何 Provider API Key 都不得放入仓库、命令行、环境变量或普通配置文件。

## 一键验证 Host 闭环

Maven 中央仓库或企业镜像可访问时运行：

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\verify-phase1.ps1
```

脚本会在临时目录启动 Host，并依次验证 bootstrap/ready 握手、Bearer 与协议版本鉴权、Mock AG-UI/SSE 流、SQLite 时间线和优雅关闭；结束后清理其创建的临时目录。脚本不读取 Provider API Key。

## 验证桌面端

```powershell
npm install
npm run typecheck
npm run build
```

生产构建完成后，可执行完整桌面链路冒烟；脚本会以隐藏窗口启动 Electron，并验证 Renderer、preload IPC、Java Host、AG-UI/SSE 与 React 流式展示：

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\verify-desktop-smoke.ps1 -SkipBuild
```

开发运行前先完成 Java Host 打包，然后在两个终端中运行：

```powershell
# 终端 1
npm run dev --workspace desktop-ui

# 终端 2
$env:AGENT_DESKTOP_DEV_URL = 'http://127.0.0.1:5173'
$env:AGENT_DESKTOP_JAVA = 'C:\Program Files\Java\jdk21.0.10\bin\java.exe'
$env:AGENT_DESKTOP_HOST_JAR = (Resolve-Path '.\agent-host\target\agent-host-0.1.0-SNAPSHOT.jar')
npx electron desktop-shell/dist/main.js
```

Electron 首次安装需要访问其官方二进制发布地址；企业网络应配置受信代理或内部镜像。

## 验证第 4 周 Skill 闭环

以下命令一次完成 42 项 Java 测试、TypeScript、生产构建、真实 Renderer 两轮验收、敏感内容扫描和第 3 周回归：

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\verify-week4.ps1
```

手工界面复核时，先按上面的开发运行方式启动应用，然后：

1. 打开 `Skills`，切换来源和状态筛选，点击“刷新”，确认结果统计稳定显示。
2. 打开一个 Skill，确认 `SKILL.md`、HTML 标签及资源都以纯文本展示；点击“停用/启用”验证状态变化。
3. 回到会话，在输入区选择 Skill，确认 chip 出现；发送后确认“已显式启用”并且 chip 清空。
4. 不选择 Skill，输入与其 description 明确匹配的请求，确认出现“已隐式启用”。
5. 请求某个引用资源，确认只展示/加载目标资源；重启应用后确认启停状态和显式/隐式激活摘要仍存在。

## 验证第 5 周 Skill 导入与安装

以下命令运行 Java/TypeScript/生产构建、两轮 Electron 导入与重启验收、来源与敏感信息扫描，并串行回归第 4、3 周：

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\verify-week5.ps1
```

Skills 页面支持从本地目录或公开 GitHub 仓库检查 Skill。检查只生成预览；用户二次确认后才原子安装到 USER scope，新 Skill 默认停用。由本应用管理的安装可删除，并可在一次成功替换后回滚最近版本。导入不会执行脚本、安装 MCP Server 或授予 Tool 权限。
