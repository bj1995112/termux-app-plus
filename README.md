# Termux App Plus

**Termux App Plus** 是基于 [Termux](https://github.com/termux/termux-app) 二次开发的 Android 终端应用。

在保留 Termux 核心终端与 Linux 环境能力的基础上，本项目针对 **MCP、AI Agent、远程控制与 Android 集成场景**进行了扩展。

## 项目特点

### Android 终端环境

提供 Android 终端环境，可以在手机上直接运行 Linux 命令、脚本以及各种开发工具。

支持：

- Shell / Bash
- Linux 命令行工具
- Git
- Python
- Node.js
- 包管理
- 文件操作
- 脚本自动化

### 内置 MCP Server

项目集成了 MCP（Model Context Protocol）服务能力。

MCP Server 直接运行在 Android 应用内部，不需要额外启动独立的 Termux MCP 服务。

主要用于：

- AI 远程操作 Android 终端
- 文件管理
- Shell 命令执行
- Python 脚本执行
- Git 仓库管理
- 日志分析
- 自动化任务
- Android 设备管理

MCP 服务采用 **Streamable HTTP** 通信方式，并针对 Android 环境进行了适配。

### OpenAI 安全隧道

支持通过 OpenAI 官方安全隧道将本机 MCP Server 暴露给 ChatGPT 等客户端。

整体通信链路：

```text
ChatGPT
   │
   │ OpenAI 官方安全隧道
   ▼
MCP Tunnel
   │
   ▼
Termux App Plus
   │
   ▼
MCP Server
   │
   ├── Shell
   ├── File
   ├── Python
   ├── Git
   └── 其他工具
```

这样可以让 Android 手机上的 Termux 环境直接成为 AI Agent 的执行环境。

## MCP 工具

当前 MCP 架构主要面向高频系统管理和开发任务。

计划/支持的工具包括：

| 工具 | 功能 |
|---|---|
| `shell` | 执行 Shell 命令 |
| `file` | 文件读取、写入和管理 |
| `python_run` | 执行 Python 脚本并返回 stdout/stderr |
| `git_status` | 查看 Git 仓库状态 |
| `git_pull` | 拉取远程仓库更新 |
| `git_clone` | 克隆 Git 仓库 |
| `git` | 执行 Git 操作 |
| `pm2` | 管理后台服务 |
| `search` | 搜索文件及相关内容 |
| `container` | 容器相关操作 |

工具会根据实际开发进度持续增加。

## 与传统 Termux MCP 的区别

传统方案通常需要：

```text
Android
  ↓
Termux
  ↓
独立 MCP Server
  ↓
网络 / 隧道
  ↓
AI
```

Termux App Plus 则将 MCP 能力进一步集成到 Android 应用内部：

```text
Android App
 ├── Terminal
 ├── Linux Environment
 ├── MCP Server
 └── Tunnel
        ↓
      AI
```

减少额外服务和端口依赖，使 MCP 更适合直接运行在移动设备上。

## 项目状态

本项目处于持续开发阶段。

当前重点开发方向：

- MCP Server
- Streamable HTTP
- OpenAI 安全隧道
- Android 与 MCP 集成
- Shell 工具
- 文件工具
- Python 工具
- Git 工具
- 服务进程管理
- MCP 日志与调试
- Android 环境适配

部分功能仍可能存在兼容性问题。

## 安装

### APK

APK 请前往项目的 GitHub Releases 获取：

**[Releases](https://github.com/bj1995112/termux-app-plus/releases)**

建议根据设备架构选择对应版本。

## 注意事项

### 1. 与官方 Termux 不一定兼容

本项目属于 Termux 的二次开发版本，与官方 Termux 的 APK 签名、包名或运行环境可能存在差异。

不要随意混装不同来源的 Termux 及其插件。

### 2. MCP 需要网络环境支持

使用 OpenAI 官方安全隧道时，需要确保：

- `api.openai.com` 可以正常访问
- 当前代理节点能够稳定连接 OpenAI
- DNS 解析正常
- HTTPS 连接正常
- Android VPN / 代理软件没有拦截相关连接

不同代理软件、节点和路由规则可能产生不同结果。

### 3. MCP 服务端口

MCP Server 使用项目内部配置的端口运行。

如果通过本地网络访问 MCP，请以应用当前配置和日志显示的端口为准。

## 调试

项目提供 MCP 日志，用于排查：

- MCP 初始化
- Streamable HTTP 请求
- 客户端握手
- 协议版本
- 工具调用
- HTTP 状态
- Tunnel 连接
- Tunnel 轮询
- 网络错误
- DNS 错误
- 代理连接问题
- 服务端异常

遇到 MCP 无法连接时，建议首先查看应用日志。

## 开发

项目基于 Termux 开源项目进行二次开发。

主要模块包括：

```text
app/
terminal-emulator/
terminal-view/
termux-shared/
```

构建环境使用 Gradle / Android SDK。

开发者可以根据项目源码进行修改和重新构建。

## 致谢

本项目基于以下开源项目：

- [Termux](https://github.com/termux/termux-app)
- [Termux Packages](https://github.com/termux/termux-packages)
- [Model Context Protocol](https://modelcontextprotocol.io/)

感谢 Termux 及相关开源社区提供的基础设施。

## 许可证

本项目遵循原 Termux 项目及各依赖组件对应的开源许可证。

使用、修改和重新分发代码时，请遵守相应许可证要求。

---

## 项目定位

> **让 Android 手机上的 Termux 环境直接成为 AI Agent 的执行终端。**

Termux App Plus 不只是一个 Android 终端应用，同时也是一个面向 **AI + MCP + Linux + Android** 场景的移动端运行环境。
