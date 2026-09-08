package com.termux.app.mcp.tools;

import android.content.Context;

import com.termux.app.mcp.TermuxMcpManager;
import com.termux.shared.termux.TermuxConstants;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 终端 Shell 命令执行工具：
 * 注入完整 Termux Linux 运行环境变量（PATH, PREFIX, LD_PRELOAD 等），原生异步收集命令回显。
 */
public class ShellTool implements McpTool {

    @Override
    public String getName() {
        return "execute_command";
    }

    @Override
    public String getDescription() {
        return "在 Termux 终端环境中执行 Shell 命令（具备完整 Linux 命令行环境与包管理器）。支持 cwd 指定工作目录与超时限制。";
    }

    @Override
    public String getPreferenceFilterKey() {
        return TermuxMcpManager.PREF_KEY_TOOL_EXEC_CMD;
    }

    @Override
    public JSONObject getInputSchema() {
        try {
            JSONObject schema = new JSONObject();
            schema.put("type", "object");
            schema.put("$schema", "http://json-schema.org/draft-07/schema#");

            JSONObject properties = new JSONObject();

            JSONObject cmdProp = new JSONObject();
            cmdProp.put("type", "string");
            cmdProp.put("description", "要执行的 Shell 命令（如 ls -la, git status, pkg install -y curl 等）");
            properties.put("command", cmdProp);

            JSONObject cwdProp = new JSONObject();
            cwdProp.put("type", "string");
            cwdProp.put("description", "执行命令的工作目录（可选，默认 Termux 用户家目录 ~）");
            properties.put("cwd", cwdProp);

            JSONObject timeoutProp = new JSONObject();
            timeoutProp.put("type", "integer");
            timeoutProp.put("description", "执行超时时间（毫秒，默认遵循客户端配置，如 60000）");
            properties.put("timeout_ms", timeoutProp);

            schema.put("properties", properties);
            JSONArray required = new JSONArray();
            required.put("command");
            schema.put("required", required);

            return schema;
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    @Override
    public String execute(JSONObject args, Context context) throws Exception {
        String command = args.optString("command", "");
        if (command.isEmpty() && args.has("cmd")) {
            command = args.optString("cmd", "");
        }
        if (command.trim().isEmpty()) {
            return "错误：命令行不能为空";
        }

        String cwdStr = args.optString("cwd", TermuxConstants.TERMUX_HOME_DIR_PATH);
        int defaultTimeoutMs = TermuxMcpManager.getInstance().getExecTimeoutSec(context) * 1000;
        int timeout = args.optInt("timeout_ms", defaultTimeoutMs);
        if (timeout <= 0) {
            timeout = defaultTimeoutMs;
        }

        return runShellCommand(command, cwdStr, timeout);
    }

    public static class CommandResult {
        public final boolean success;
        public final String stdout;
        public final String stderr;
        public final int exitCode;
        public final long durationMs;

        public CommandResult(boolean success, String stdout, String stderr, int exitCode, long durationMs) {
            this.success = success;
            this.stdout = stdout != null ? stdout : "";
            this.stderr = stderr != null ? stderr : "";
            this.exitCode = exitCode;
            this.durationMs = durationMs;
        }

        public JSONObject toJsonObject() {
            JSONObject json = new JSONObject();
            try {
                json.put("success", success);
                json.put("stdout", stdout);
                json.put("stderr", stderr);
                json.put("exit_code", exitCode);
                json.put("duration", String.format(java.util.Locale.US, "%.3fs", durationMs / 1000.0));
            } catch (Exception ignored) {}
            return json;
        }

        public String toJsonString() {
            return toJsonObject().toString();
        }
    }

    public static CommandResult run(String command, String cwdStr, int timeoutMs) {
        long startTime = System.currentTimeMillis();
        if (command == null || command.trim().isEmpty()) {
            return new CommandResult(false, "", "错误：命令行不能为空", -1, 0);
        }

        File cwd = new File(cwdStr != null && !cwdStr.isEmpty() ? cwdStr : TermuxConstants.TERMUX_HOME_DIR_PATH);
        if (!cwd.exists()) {
            cwd = new File(TermuxConstants.TERMUX_HOME_DIR_PATH);
        }

        // 优先使用 Termux 自带的标准 bash，否则兜底 /bin/bash 或 sh
        String shell = TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH + "/bash";
        if (!new File(shell).exists()) {
            shell = "/bin/bash";
            if (!new File(shell).exists()) {
                shell = "sh";
            }
        }

        ProcessBuilder pb = new ProcessBuilder(shell, "-c", command);
        pb.directory(cwd);

        // 注入完整的 Termux 运行时环境变量
        Map<String, String> env = pb.environment();
        env.put("PREFIX", TermuxConstants.TERMUX_PREFIX_DIR_PATH);
        env.put("HOME", TermuxConstants.TERMUX_HOME_DIR_PATH);
        env.put("PATH", TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH + ":" +
            TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH + "/applets:" +
            "/usr/local/bin:/usr/bin:/bin:" + System.getenv("PATH"));
        env.put("LD_PRELOAD", TermuxConstants.TERMUX_PREFIX_DIR_PATH + "/lib/libtermux-exec.so");
        env.put("TMPDIR", TermuxConstants.TERMUX_TMP_PREFIX_DIR_PATH);
        env.put("LANG", "en_US.UTF-8");
        env.put("TERM", "xterm-256color");

        try {
            Process process = pb.start();

            // 异步并发收集 stdout 和 stderr
            ByteArrayOutputStream outStream = new ByteArrayOutputStream();
            ByteArrayOutputStream errStream = new ByteArrayOutputStream();

            Thread tOut = new Thread(() -> copyStream(process.getInputStream(), outStream));
            Thread tErr = new Thread(() -> copyStream(process.getErrorStream(), errStream));
            tOut.start();
            tErr.start();

            boolean finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS);
            long durationMs = System.currentTimeMillis() - startTime;
            if (!finished) {
                process.destroyForcibly();
                return new CommandResult(false, "", "执行超时（限制: " + timeoutMs + "ms）", -1, durationMs);
            }

            try {
                tOut.join(1000);
                tErr.join(1000);
            } catch (InterruptedException ignored) {}

            int exitCode = process.exitValue();
            String stdout = outStream.toString("UTF-8");
            String stderr = errStream.toString("UTF-8");
            boolean success = (exitCode == 0);

            return new CommandResult(success, stdout, stderr, exitCode, durationMs);

        } catch (Exception e) {
            long durationMs = System.currentTimeMillis() - startTime;
            return new CommandResult(false, "", "Shell 执行异常: " + e.getMessage(), -1, durationMs);
        }
    }

    public String runShellCommand(String command, String cwdStr, int timeoutMs) {
        CommandResult result = run(command, cwdStr, timeoutMs);
        if (!result.success && result.exitCode == -1 && result.stdout.isEmpty()) {
            return result.stderr;
        }

        StringBuilder sb = new StringBuilder();
        if (!result.stdout.isEmpty()) {
            sb.append(result.stdout);
        }
        if (!result.stderr.isEmpty()) {
            if (sb.length() > 0 && !sb.toString().endsWith("\n")) sb.append("\n");
            sb.append("[stderr]:\n").append(result.stderr);
        }
        if (result.exitCode != 0) {
            sb.append("\n[进程退出码: ").append(result.exitCode).append("]");
        }
        return sb.toString();
    }

    private static void copyStream(InputStream in, OutputStream out) {
        try {
            byte[] buf = new byte[4096];
            int len;
            while ((len = in.read(buf)) > 0) {
                out.write(buf, 0, len);
            }
            out.flush();
        } catch (Exception ignored) {}
    }
}
