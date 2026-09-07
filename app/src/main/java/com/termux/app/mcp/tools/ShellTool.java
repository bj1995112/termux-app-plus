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
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 终端 Shell 命令执行工具：
 * 1. 注入完整 Termux Linux 运行环境变量（PATH, PREFIX, LD_PRELOAD 等）；
 * 2. 内置执行引擎优化：具备 256KB 有界缓冲区防止超大输出导致内存溢出 (OOM)；
 * 3. 严格的超时中断机制，防止子进程僵死耗尽系统资源。
 */
public class ShellTool implements McpTool {

    public static final int MAX_OUTPUT_BYTES = 256 * 1024; // 最大输出缓冲限制为 256KB

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

    /**
     * 带有限流与截断保护的流拷贝收集器
     */
    private static class BoundedBuffer {
        private final ByteArrayOutputStream baos = new ByteArrayOutputStream();
        private int totalBytesRead = 0;
        private boolean truncated = false;

        public synchronized void write(byte[] b, int off, int len) {
            totalBytesRead += len;
            if (baos.size() < MAX_OUTPUT_BYTES) {
                int toWrite = Math.min(len, MAX_OUTPUT_BYTES - baos.size());
                baos.write(b, off, toWrite);
                if (toWrite < len) {
                    truncated = true;
                }
            } else {
                truncated = true;
            }
        }

        public synchronized String toStringUtf8() {
            String str = new String(baos.toByteArray(), StandardCharsets.UTF_8);
            if (truncated) {
                str += "\n\n[... 警告：命令输出已超过 " + (MAX_OUTPUT_BYTES / 1024) + "KB 限制，剩余输出已被截断以防止 OOM ...]";
            }
            return str;
        }

        public synchronized boolean isEmpty() {
            return baos.size() == 0;
        }
    }

    /**
     * 在 Termux 完整环境中执行 Shell 命令
     */
    public String runShellCommand(String command, String cwdStr, int timeoutMs) {
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

            BoundedBuffer outBuffer = new BoundedBuffer();
            BoundedBuffer errBuffer = new BoundedBuffer();

            Thread tOut = new Thread(() -> readStreamBounded(process.getInputStream(), outBuffer));
            Thread tErr = new Thread(() -> readStreamBounded(process.getErrorStream(), errBuffer));
            tOut.start();
            tErr.start();

            boolean finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS);
            if (!finished) {
                process.destroyForcibly();
                return "执行超时（限制: " + timeoutMs + "ms，已强制终止进程）";
            }

            try {
                tOut.join(1000);
                tErr.join(1000);
            } catch (InterruptedException ignored) {}

            int exitCode = process.exitValue();
            String stdout = outBuffer.toStringUtf8();
            String stderr = errBuffer.toStringUtf8();

            StringBuilder sb = new StringBuilder();
            if (!stdout.isEmpty()) {
                sb.append(stdout);
            }
            if (!stderr.isEmpty()) {
                if (sb.length() > 0 && !sb.toString().endsWith("\n")) {
                    sb.append("\n");
                }
                sb.append("[stderr]:\n").append(stderr);
            }
            if (exitCode != 0) {
                sb.append("\n[进程退出码: ").append(exitCode).append("]");
            }
            return sb.toString();

        } catch (Exception e) {
            return "Shell 执行异常: " + e.getMessage();
        }
    }

    private void readStreamBounded(InputStream in, BoundedBuffer buffer) {
        try {
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) {
                buffer.write(buf, 0, n);
            }
        } catch (Exception ignored) {
        } finally {
            try { in.close(); } catch (Exception ignored) {}
        }
    }
}
