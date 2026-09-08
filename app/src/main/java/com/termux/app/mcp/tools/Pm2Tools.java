package com.termux.app.mcp.tools;

import android.content.Context;

import com.termux.app.mcp.TermuxMcpManager;
import com.termux.shared.termux.TermuxConstants;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.Locale;

/**
 * PM2 进程守护管理工具集合：
 * 统一管理 Termux 原生环境与 Ubuntu proot 环境内的后台守护进程与后台服务。
 */
public class Pm2Tools {

    private static final String PROOT_UBUNTU_PATH = TermuxConstants.TERMUX_PREFIX_DIR_PATH + "/var/lib/proot-distro/installed-rootfs/ubuntu";

    /**
     * 判断是否在 Termux 中安装了 pm2
     */
    public static boolean isTermuxPm2Available() {
        File pm2Bin = new File(TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH, "pm2");
        return pm2Bin.exists();
    }

    /**
     * 判断是否在 proot-distro 中安装了 ubuntu
     */
    public static boolean isUbuntuInstalled() {
        File ubuntuDir = new File(PROOT_UBUNTU_PATH);
        return ubuntuDir.exists() && ubuntuDir.isDirectory();
    }

    /**
     * 针对指定环境执行 pm2 子命令
     * @param pm2SubCmd 如 "jlist" 或 "stop 0" 或 "save"
     * @param target "termux" 或 "ubuntu"
     * @param timeoutMs 超时毫秒
     */
    public static ShellTool.CommandResult runPm2(String pm2SubCmd, String target, int timeoutMs) {
        long startTime = System.currentTimeMillis();
        boolean useUbuntu = "ubuntu".equalsIgnoreCase(target);

        if (useUbuntu) {
            if (!isUbuntuInstalled()) {
                return new ShellTool.CommandResult(
                    false, "",
                    "Ubuntu proot-distro 环境未安装（路径不存在: " + PROOT_UBUNTU_PATH + "）。请先在 Termux 中运行 'proot-distro install ubuntu'。",
                    -1, System.currentTimeMillis() - startTime
                );
            }
            // 在 ubuntu proot 内部执行
            String command = "proot-distro login ubuntu -- bash -c \"export PATH=\\$PATH:/usr/local/bin:/usr/bin:/bin; pm2 " + pm2SubCmd.replace("\"", "\\\"") + "\"";
            return ShellTool.run(command, TermuxConstants.TERMUX_HOME_DIR_PATH, timeoutMs);
        } else {
            // Termux 原生环境
            if (!isTermuxPm2Available()) {
                return new ShellTool.CommandResult(
                    false, "",
                    "Termux 原生环境中未安装 PM2。如需使用请在终端运行 'pkg install nodejs && npm install -g pm2'；若 PM2 部署在 Ubuntu proot 内，请在参数中指定 target='ubuntu'。",
                    -1, System.currentTimeMillis() - startTime
                );
            }
            String command = "pm2 " + pm2SubCmd;
            return ShellTool.run(command, TermuxConstants.TERMUX_HOME_DIR_PATH, timeoutMs);
        }
    }

    /**
     * 1. pm2_list
     */
    public static class Pm2ListTool implements McpTool {

        @Override
        public String getName() {
            return "pm2_list";
        }

        @Override
        public String getDescription() {
            return "查看 PM2 托管的所有进程清单与运行状态（包含状态、内存占用、CPU、重启次数），支持 Termux 原生与 Ubuntu 环境。";
        }

        @Override
        public String getPreferenceFilterKey() {
            return TermuxMcpManager.PREF_KEY_TOOL_PM2;
        }

        @Override
        public JSONObject getInputSchema() {
            try {
                JSONObject schema = new JSONObject();
                schema.put("type", "object");
                schema.put("$schema", "http://json-schema.org/draft-07/schema#");

                JSONObject properties = new JSONObject();
                JSONObject targetProp = new JSONObject();
                targetProp.put("type", "string");
                targetProp.put("description", "目标环境：'all'（汇总全部）、'termux'（仅 Termux 原生）、'ubuntu'（仅 Ubuntu proot），默认 'all'");
                properties.put("target", targetProp);

                schema.put("properties", properties);
                return schema;
            } catch (Exception e) {
                return new JSONObject();
            }
        }

        @Override
        public String execute(JSONObject args, Context context) throws Exception {
            String target = args.optString("target", "all").toLowerCase(Locale.ROOT);
            int defaultTimeoutMs = TermuxMcpManager.getInstance().getExecTimeoutSec(context) * 1000;

            JSONObject result = new JSONObject();
            JSONArray processList = new JSONArray();
            StringBuilder errors = new StringBuilder();
            long totalStart = System.currentTimeMillis();

            if ("all".equals(target) || "termux".equals(target)) {
                ShellTool.CommandResult termuxRes = runPm2("jlist", "termux", defaultTimeoutMs);
                parseAndAddProcesses(termuxRes, "termux", processList, errors);
            }

            if ("all".equals(target) || "ubuntu".equals(target)) {
                ShellTool.CommandResult ubuntuRes = runPm2("jlist", "ubuntu", defaultTimeoutMs);
                parseAndAddProcesses(ubuntuRes, "ubuntu", processList, errors);
            }

            result.put("success", true);
            result.put("processes", processList);
            result.put("count", processList.length());
            if (errors.length() > 0) {
                result.put("notices", errors.toString().trim());
            }
            long totalDuration = System.currentTimeMillis() - totalStart;
            result.put("duration", String.format(Locale.US, "%.3fs", totalDuration / 1000.0));

            return result.toString();
        }

        private void parseAndAddProcesses(ShellTool.CommandResult res, String envName, JSONArray outputList, StringBuilder errors) {
            if (!res.success) {
                if (errors.length() > 0) errors.append(" | ");
                errors.append("[").append(envName).append("]: ").append(res.stderr.isEmpty() ? res.stdout : res.stderr);
                return;
            }

            String stdout = res.stdout.trim();
            int jsonStart = stdout.indexOf('[');
            int jsonEnd = stdout.lastIndexOf(']');
            if (jsonStart >= 0 && jsonEnd > jsonStart) {
                try {
                    String jsonArrStr = stdout.substring(jsonStart, jsonEnd + 1);
                    JSONArray arr = new JSONArray(jsonArrStr);
                    for (int i = 0; i < arr.length(); i++) {
                        JSONObject proc = arr.optJSONObject(i);
                        if (proc == null) continue;

                        JSONObject item = new JSONObject();
                        item.put("id", proc.optInt("pm_id", i));
                        item.put("name", proc.optString("name", "unknown"));
                        item.put("env", envName);

                        JSONObject pm2Env = proc.optJSONObject("pm2_env");
                        if (pm2Env != null) {
                            item.put("status", pm2Env.optString("status", "unknown"));
                            item.put("restarts", pm2Env.optInt("restart_time", 0));
                            item.put("uptime_ms", pm2Env.optLong("pm_uptime", 0));
                            item.put("exec_mode", pm2Env.optString("exec_mode", "fork_mode"));
                        } else {
                            item.put("status", "unknown");
                            item.put("restarts", 0);
                        }

                        JSONObject monit = proc.optJSONObject("monit");
                        if (monit != null) {
                            item.put("cpu_percent", monit.optDouble("cpu", 0.0));
                            long memBytes = monit.optLong("memory", 0);
                            double memMb = Math.round((memBytes / 1024.0 / 1024.0) * 10.0) / 10.0;
                            item.put("memory_mb", memMb);
                        } else {
                            item.put("cpu_percent", 0.0);
                            item.put("memory_mb", 0.0);
                        }

                        outputList.put(item);
                    }
                    return;
                } catch (Exception e) {
                    // 解析 JSON 失败，进入 fallback 记录
                }
            }

            // Fallback: 若未提取到标准 JSON 数组，但执行成功
            if (!stdout.isEmpty()) {
                if (errors.length() > 0) errors.append(" | ");
                errors.append("[").append(envName).append(" 原始输出]: ").append(stdout);
            }
        }
    }

    /**
     * 2. pm2_start
     */
    public static class Pm2StartTool implements McpTool {

        @Override
        public String getName() {
            return "pm2_start";
        }

        @Override
        public String getDescription() {
            return "通过 PM2 启动并持久化守护一个新的应用程序或脚本（支持 Node.js、Python、Shell 等）。";
        }

        @Override
        public String getPreferenceFilterKey() {
            return TermuxMcpManager.PREF_KEY_TOOL_PM2;
        }

        @Override
        public JSONObject getInputSchema() {
            try {
                JSONObject schema = new JSONObject();
                schema.put("type", "object");
                schema.put("$schema", "http://json-schema.org/draft-07/schema#");

                JSONObject properties = new JSONObject();
                JSONObject scriptProp = new JSONObject();
                scriptProp.put("type", "string");
                scriptProp.put("description", "启动入口文件或命令（必填，如 app.js、server.py 或 npm -- start）");
                properties.put("script", scriptProp);

                JSONObject nameProp = new JSONObject();
                nameProp.put("type", "string");
                nameProp.put("description", "为该进程指定的 PM2 实例别名（可选，如 my-api）");
                properties.put("name", nameProp);

                JSONObject argsProp = new JSONObject();
                argsProp.put("type", "string");
                argsProp.put("description", "传递给脚本的运行参数（可选，如 --port 8080）");
                properties.put("args", argsProp);

                JSONObject cwdProp = new JSONObject();
                cwdProp.put("type", "string");
                cwdProp.put("description", "脚本启动工作目录（可选）");
                properties.put("cwd", cwdProp);

                JSONObject targetProp = new JSONObject();
                targetProp.put("type", "string");
                targetProp.put("description", "执行目标环境：'termux'（默认）或 'ubuntu'");
                properties.put("target", targetProp);

                schema.put("properties", properties);
                JSONArray req = new JSONArray();
                req.put("script");
                schema.put("required", req);
                return schema;
            } catch (Exception e) {
                return new JSONObject();
            }
        }

        @Override
        public String execute(JSONObject args, Context context) throws Exception {
            String script = args.optString("script", "").trim();
            if (script.isEmpty()) {
                JSONObject err = new JSONObject();
                err.put("success", false);
                err.put("stdout", "");
                err.put("stderr", "错误：必须提供 script（启动文件或命令）");
                err.put("exit_code", -1);
                err.put("duration", "0.000s");
                return err.toString();
            }

            String name = args.optString("name", "").trim();
            String scriptArgs = args.optString("args", "").trim();
            String cwd = args.optString("cwd", "").trim();
            String target = args.optString("target", "termux").trim();

            StringBuilder cmd = new StringBuilder("start \"").append(script).append("\"");
            if (!name.isEmpty()) {
                cmd.append(" --name \"").append(name).append("\"");
            }
            if (!cwd.isEmpty()) {
                cmd.append(" --cwd \"").append(cwd).append("\"");
            }
            if (!scriptArgs.isEmpty()) {
                cmd.append(" -- ").append(scriptArgs);
            }

            int defaultTimeoutMs = TermuxMcpManager.getInstance().getExecTimeoutSec(context) * 1000;
            ShellTool.CommandResult res = runPm2(cmd.toString(), target, defaultTimeoutMs);
            return res.toJsonString();
        }
    }

    /**
     * 3. pm2_stop
     */
    public static class Pm2StopTool implements McpTool {

        @Override
        public String getName() {
            return "pm2_stop";
        }

        @Override
        public String getDescription() {
            return "停止指定的 PM2 守护进程（通过进程名称、ID 或 'all' 全部停止）。";
        }

        @Override
        public String getPreferenceFilterKey() {
            return TermuxMcpManager.PREF_KEY_TOOL_PM2;
        }

        @Override
        public JSONObject getInputSchema() {
            try {
                JSONObject schema = new JSONObject();
                schema.put("type", "object");
                schema.put("$schema", "http://json-schema.org/draft-07/schema#");

                JSONObject properties = new JSONObject();
                JSONObject procProp = new JSONObject();
                procProp.put("type", "string");
                procProp.put("description", "要停止的进程 ID、名称或 'all'（必填）");
                properties.put("target_process", procProp);

                JSONObject targetProp = new JSONObject();
                targetProp.put("type", "string");
                targetProp.put("description", "执行目标环境：'termux'（默认）或 'ubuntu'");
                properties.put("target", targetProp);

                schema.put("properties", properties);
                JSONArray req = new JSONArray();
                req.put("target_process");
                schema.put("required", req);
                return schema;
            } catch (Exception e) {
                return new JSONObject();
            }
        }

        @Override
        public String execute(JSONObject args, Context context) throws Exception {
            String targetProcess = args.optString("target_process", "").trim();
            if (targetProcess.isEmpty()) {
                JSONObject err = new JSONObject();
                err.put("success", false);
                err.put("stdout", "");
                err.put("stderr", "错误：必须指定 target_process（进程 ID 或名称）");
                err.put("exit_code", -1);
                err.put("duration", "0.000s");
                return err.toString();
            }

            String target = args.optString("target", "termux").trim();
            int defaultTimeoutMs = TermuxMcpManager.getInstance().getExecTimeoutSec(context) * 1000;

            ShellTool.CommandResult res = runPm2("stop \"" + targetProcess + "\"", target, defaultTimeoutMs);
            return res.toJsonString();
        }
    }

    /**
     * 4. pm2_restart
     */
    public static class Pm2RestartTool implements McpTool {

        @Override
        public String getName() {
            return "pm2_restart";
        }

        @Override
        public String getDescription() {
            return "重启指定的 PM2 守护进程（通过进程名称、ID 或 'all' 全部重启）。";
        }

        @Override
        public String getPreferenceFilterKey() {
            return TermuxMcpManager.PREF_KEY_TOOL_PM2;
        }

        @Override
        public JSONObject getInputSchema() {
            try {
                JSONObject schema = new JSONObject();
                schema.put("type", "object");
                schema.put("$schema", "http://json-schema.org/draft-07/schema#");

                JSONObject properties = new JSONObject();
                JSONObject procProp = new JSONObject();
                procProp.put("type", "string");
                procProp.put("description", "要重启的进程 ID、名称或 'all'（必填）");
                properties.put("target_process", procProp);

                JSONObject targetProp = new JSONObject();
                targetProp.put("type", "string");
                targetProp.put("description", "执行目标环境：'termux'（默认）或 'ubuntu'");
                properties.put("target", targetProp);

                schema.put("properties", properties);
                JSONArray req = new JSONArray();
                req.put("target_process");
                schema.put("required", req);
                return schema;
            } catch (Exception e) {
                return new JSONObject();
            }
        }

        @Override
        public String execute(JSONObject args, Context context) throws Exception {
            String targetProcess = args.optString("target_process", "").trim();
            if (targetProcess.isEmpty()) {
                JSONObject err = new JSONObject();
                err.put("success", false);
                err.put("stdout", "");
                err.put("stderr", "错误：必须指定 target_process（进程 ID 或名称）");
                err.put("exit_code", -1);
                err.put("duration", "0.000s");
                return err.toString();
            }

            String target = args.optString("target", "termux").trim();
            int defaultTimeoutMs = TermuxMcpManager.getInstance().getExecTimeoutSec(context) * 1000;

            ShellTool.CommandResult res = runPm2("restart \"" + targetProcess + "\"", target, defaultTimeoutMs);
            return res.toJsonString();
        }
    }

    /**
     * 5. pm2_logs
     */
    public static class Pm2LogsTool implements McpTool {

        @Override
        public String getName() {
            return "pm2_logs";
        }

        @Override
        public String getDescription() {
            return "拉取 PM2 进程的最新运行日志，已强制附加 --nostream 避免连接挂死。";
        }

        @Override
        public String getPreferenceFilterKey() {
            return TermuxMcpManager.PREF_KEY_TOOL_PM2;
        }

        @Override
        public JSONObject getInputSchema() {
            try {
                JSONObject schema = new JSONObject();
                schema.put("type", "object");
                schema.put("$schema", "http://json-schema.org/draft-07/schema#");

                JSONObject properties = new JSONObject();
                JSONObject procProp = new JSONObject();
                procProp.put("type", "string");
                procProp.put("description", "要拉取日志的进程 ID、名称或留空查看全部日志");
                properties.put("target_process", procProp);

                JSONObject linesProp = new JSONObject();
                linesProp.put("type", "integer");
                linesProp.put("description", "拉取日志的最新行数（可选，默认 50，上限 200）");
                properties.put("lines", linesProp);

                JSONObject targetProp = new JSONObject();
                targetProp.put("type", "string");
                targetProp.put("description", "执行目标环境：'termux'（默认）或 'ubuntu'");
                properties.put("target", targetProp);

                schema.put("properties", properties);
                return schema;
            } catch (Exception e) {
                return new JSONObject();
            }
        }

        @Override
        public String execute(JSONObject args, Context context) throws Exception {
            String targetProcess = args.optString("target_process", "").trim();
            int lines = args.optInt("lines", 50);
            if (lines <= 0) lines = 50;
            if (lines > 200) lines = 200;

            String target = args.optString("target", "termux").trim();

            StringBuilder cmd = new StringBuilder("logs");
            if (!targetProcess.isEmpty()) {
                cmd.append(" \"").append(targetProcess).append("\"");
            }
            cmd.append(" --lines ").append(lines).append(" --nostream");

            int defaultTimeoutMs = TermuxMcpManager.getInstance().getExecTimeoutSec(context) * 1000;
            ShellTool.CommandResult res = runPm2(cmd.toString(), target, defaultTimeoutMs);
            return res.toJsonString();
        }
    }

    /**
     * 6. pm2_save
     */
    public static class Pm2SaveTool implements McpTool {

        @Override
        public String getName() {
            return "pm2_save";
        }

        @Override
        public String getDescription() {
            return "保存当前 PM2 进程运行列表状态配置，以便在系统重启或服务重启后自动恢复。";
        }

        @Override
        public String getPreferenceFilterKey() {
            return TermuxMcpManager.PREF_KEY_TOOL_PM2;
        }

        @Override
        public JSONObject getInputSchema() {
            try {
                JSONObject schema = new JSONObject();
                schema.put("type", "object");
                schema.put("$schema", "http://json-schema.org/draft-07/schema#");

                JSONObject properties = new JSONObject();
                JSONObject targetProp = new JSONObject();
                targetProp.put("type", "string");
                targetProp.put("description", "执行目标环境：'termux'（默认）或 'ubuntu'");
                properties.put("target", targetProp);

                schema.put("properties", properties);
                return schema;
            } catch (Exception e) {
                return new JSONObject();
            }
        }

        @Override
        public String execute(JSONObject args, Context context) throws Exception {
            String target = args.optString("target", "termux").trim();
            int defaultTimeoutMs = TermuxMcpManager.getInstance().getExecTimeoutSec(context) * 1000;

            ShellTool.CommandResult res = runPm2("save", target, defaultTimeoutMs);
            return res.toJsonString();
        }
    }
}
