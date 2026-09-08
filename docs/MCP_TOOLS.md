# Termux+ MCP 工具使用说明文档

本文档详细说明 Termux+ 内置 Model Context Protocol (MCP) Server 提供的全套工具规范、参数结构及调用示例。

服务默认运行在 `0.0.0.0:28488`，支持 **Streamable HTTP**、**SSE** 及 **OpenAI 官方安全隧道** 接入。

---

## 目录
1. [控制面板与独立权限开关](#控制面板与独立权限开关)
2. [第一类：Python 工具 (python_run)](#第一类python-工具-python_run)
3. [第二类：Git 版本管理工具](#第二类git-版本管理工具)
4. [第三类：PM2 进程守护管理工具](#第三类pm2-进程守护管理工具)
5. [基础核心工具（Shell / 文件 / 系统 / 硬件）](#基础核心工具shell--文件--系统--硬件)
6. [运行日志规范](#运行日志规范)

---

## 控制面板与独立权限开关

在手机端打开 **Termux+ 设置 -> AI 远程协同与 MCP 服务**，提供了细粒度的工具独立开关：
- **终端命令执行** (`mcp_tool_exec_cmd`)
- **文件管理读写** (`mcp_tool_file_ops`)
- **系统状态查询** (`mcp_tool_system_info`)
- **系统剪贴板交互** (`mcp_tool_clipboard`)
- **闪光灯/手电筒** (`mcp_tool_torch`)
- **扬声器语音朗读** (`mcp_tool_tts`)
- **通知气泡与振动** (`mcp_tool_feedback`)
- **浏览器打开网页** (`mcp_tool_open_url`)
- **高速网络下载** (`mcp_tool_download`)
- **Python 脚本执行** (`mcp_tool_python`) ⭐ 新增
- **Git 版本控制** (`mcp_tool_git`) ⭐ 新增
- **PM2 进程守护** (`mcp_tool_pm2`) ⭐ 新增

> **安全特性**：当某个开关被关闭时，该分类下的所有工具将从 `tools/list` 声明中完全隐藏，且服务端执行时硬拦截拒绝执行。

---

## 第一类：Python 工具 (python_run)

### `python_run`
在 Termux 环境中执行 Python 代码或 Python 脚本文件。

#### 请求参数：
| 字段 | 类型 | 必填 | 描述 |
| :--- | :--- | :--- | :--- |
| `code` | string | 否* | 直接执行的代码字符串（如 `print('hello world')`） |
| `file` | string | 否* | 待执行的 `.py` 脚本文件绝对路径 |
| `args` | string[] | 否 | 传递给脚本的命令行参数列表 |
| `cwd` | string | 否 | 执行工作目录，默认 Termux 用户家目录 `~` |
| `timeout_ms` | int | 否 | 执行超时时间（毫秒），默认 60000ms |

> *注：`code` 与 `file` 至少提供一个。`code` 采用临时文件隔离执行，规避 Shell 引号转义。

#### 返回结构：
```json
{
  "success": true,
  "stdout": "hello world\n",
  "stderr": "",
  "exit_code": 0,
  "duration": "0.045s"
}
```

---

## 第二类：Git 版本管理工具

### 1. `git_status`
获取本地 Git 仓库的分支、改动状态与未跟踪文件。

- **参数**：
  - `path` (string, 可选): Git 仓库本地目录绝对路径，默认 Termux 用户家目录。
- **返回结构**：
  ```json
  {
    "success": true,
    "path": "/data/data/com.termux/files/home/my-project",
    "branch": "main",
    "is_clean": false,
    "raw_status": "On branch main\nChanges not staged for commit: ...",
    "stdout": "...",
    "stderr": "",
    "exit_code": 0,
    "duration": "0.082s"
  }
  ```

### 2. `git_pull`
从远程拉取最新代码并合并。

- **参数**：
  - `path` (string, 可选): 仓库本地目录路径。
  - `remote` (string, 可选): 远程分支源名称（如 `origin`）。
  - `branch` (string, 可选): 要拉取的目标分支（如 `main`）。
- **返回结构**：标准结构化执行结果（含 `success`, `stdout`, `stderr`, `exit_code`, `duration`）。

### 3. `git_clone`
克隆远程仓库到本地目录。

- **参数**：
  - `url` (string, **必填**): Git 仓库远程地址（如 `https://github.com/xxx/yyy.git`）。
  - `path` (string, 可选): 克隆目标目录。
  - `depth` (int, 可选): 克隆深度（如 `1` 表示浅克隆，加速下载）。
- **返回结构**：标准结构化执行结果。

### 4. `git_log`
查看 Git 提交历史。

- **参数**：
  - `path` (string, 可选): 仓库本地目录。
  - `limit` (int, 可选): 提取条数，默认 10，上限 50。
  - `oneline` (boolean, 可选): 是否以单行紧凑模式展示，默认 `false`。
- **返回结构**：标准结构化执行结果。

### 5. `git_diff`
查看未暂存或已暂存的代码修改比对。

- **参数**：
  - `path` (string, 可选): 仓库本地目录。
  - `cached` (boolean, 可选): 是否比对暂存区（`--cached`），默认 `false`。
  - `file` (string, 可选): 仅查看指定文件的 diff。
- **返回结构**：标准结构化执行结果。

---

## 第三类：PM2 进程守护管理工具

PM2 工具统一适配 **Termux 原生环境** 与 **Ubuntu proot-distro 容器**。

### 1. `pm2_list`
查看 PM2 托管的所有进程清单与运行状态。

- **参数**：
  - `target` (string, 可选): 目标环境，可选 `'all'`（默认，汇总 Termux 和 Ubuntu）、`'termux'` 或 `'ubuntu'`。
- **返回结构**：
  ```json
  {
    "success": true,
    "count": 1,
    "processes": [
      {
        "id": 0,
        "name": "my-web-api",
        "env": "termux",
        "status": "online",
        "restarts": 0,
        "uptime_ms": 171800000,
        "exec_mode": "fork_mode",
        "cpu_percent": 0.2,
        "memory_mb": 36.5
      }
    ],
    "duration": "0.280s"
  }
  ```

### 2. `pm2_start`
启动并持久化守护一个新的进程或脚本。

- **参数**：
  - `script` (string, **必填**): 脚本或入口文件路径（如 `server.js`、`app.py`）。
  - `name` (string, 可选): PM2 进程实例别名。
  - `args` (string, 可选): 附加运行参数。
  - `cwd` (string, 可选): 运行目录。
  - `target` (string, 可选): `'termux'`（默认）或 `'ubuntu'`。

### 3. `pm2_stop`
停止指定 PM2 进程。

- **参数**：
  - `target_process` (string, **必填**): 进程名称、ID 或 `'all'`。
  - `target` (string, 可选): `'termux'`（默认）或 `'ubuntu'`。

### 4. `pm2_restart`
重启指定 PM2 进程。

- **参数**：
  - `target_process` (string, **必填**): 进程名称、ID 或 `'all'`。
  - `target` (string, 可选): `'termux'`（默认）或 `'ubuntu'`。

### 5. `pm2_logs`
查看 PM2 进程最新日志输出。
*注：内置强制附加 `--nostream` 选项，杜绝进程挂死。*

- **参数**：
  - `target_process` (string, 可选): 进程名称、ID 或留空查看全部日志。
  - `lines` (int, 可选): 拉取最新行数，默认 50，上限 200。
  - `target` (string, 可选): `'termux'`（默认）或 `'ubuntu'`。

### 6. `pm2_save`
保存当前 PM2 进程清单，确保 Termux 重启或手机开机自启后服务自动恢复。

- **参数**：
  - `target` (string, 可选): `'termux'`（默认）或 `'ubuntu'`。

---

## 基础核心工具（Shell / 文件 / 系统 / 硬件）

| 工具名称 | 分类 | 核心功能 |
| :--- | :--- | :--- |
| `execute_command` | Shell | 在 Termux 终端执行任意命令，支持环境变量注入与超时限制 |
| `file_operation` | 文件系统 | 文件与目录操作统一集合端点 |
| `read_file` | 文件系统 | 按照 UTF-8 编码读取文本文件内容 |
| `write_file` | 文件系统 | 覆盖写入或追加内容至指定文件 |
| `list_directory` | 文件系统 | 列出指定目录下的子文件与目录清单 |
| `get_system_info` | 系统状态 | 读取手机型号、Android版本、电量、内存与磁盘余量 |
| `get_clipboard` | 剪贴板 | 读取手机 Android 系统剪贴板内容 |
| `set_clipboard` | 剪贴板 | 向手机系统剪贴板写入指定内容 |
| `termux_torch` | 硬件外设 | 开启或关闭手机后置闪光灯/手电筒 |
| `termux_tts_speak` | 多媒体 | 通过手机系统扬声器朗读语音 |
| `termux_toast` | 交互通知 | 在手机屏幕底部弹出短暂浮动气泡消息 |
| `termux_notification` | 交互通知 | 在 Android 顶部通知栏发布一条通知 |
| `termux_vibrate` | 硬件外设 | 触发手机振动反馈（毫秒） |
| `open_url` | 网络应用 | 在手机默认浏览器中打开指定网址 |
| `download_file` | 网络应用 | 下载网络文件并保存到 `/sdcard/Download` |

---

## 运行日志规范

每次工具调用均会实时写入内存缓冲区与 `~/mcp_server.log` 文件，日志格式如下：
```text
[2026-09-08 19:30:00] [TOOL] 工具名称 参数 执行结果
```
例如：
```text
[2026-09-08 19:30:01] [TOOL] python_run {"code":"print(1+1)"} [OK] {"success":true,"stdout":"2\n","stderr":"","exit_code":0,"duration":"0.038s"}
[2026-09-08 19:30:05] [TOOL] git_status {"path":"/data/data/com.termux/files/home"} [OK] {"success":true,"branch":"main",...}
[2026-09-08 19:30:10] [TOOL] pm2_list {"target":"all"} [OK] {"success":true,"count":1,...}
```
