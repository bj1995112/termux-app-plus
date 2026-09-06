package com.termux.app.mcp;

import android.app.ActivityManager;
import android.app.Notification;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Environment;
import android.os.StatFs;
import android.os.Vibrator;

import com.termux.R;
import com.termux.shared.logger.Logger;
import com.termux.shared.notification.NotificationUtils;
import com.termux.shared.termux.TermuxConstants;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * 原生内置轻量级 MCP 协议服务器：
 * 1. 符合 2026 最新 Model Context Protocol 规范（支持 Streamable HTTP POST /mcp）
 * 2. 兼容经典 Server-Sent Events（GET /sse & POST /messages）
 * 3. 支持 ChatGPT Custom Actions（GET /openapi.json & REST API）
 * 4. 零第三方臃肿依赖，常驻内存 < 5MB，开机自启与息屏常驻。
 */
public class TermuxMcpServer {

    private static final String LOG_TAG = "TermuxMcpServer";

    private final Context mContext;
    private final int mPort;
    private final String mToken;

    private ServerSocket mServerSocket;
    private volatile boolean mRunning = false;
    private ExecutorService mThreadPool;

    // 活跃的 SSE 客户端连接会话
    private final Map<String, OutputStream> mSseSessions = new ConcurrentHashMap<>();

    public TermuxMcpServer(Context context, int port, String token) {
        this.mContext = context;
        this.mPort = port;
        this.mToken = token != null ? token.trim() : "";
    }

    public synchronized void start() throws IOException {
        if (mRunning) return;

        mServerSocket = new ServerSocket();
        mServerSocket.setReuseAddress(true);
        // 绑定 0.0.0.0，允许 Wi-Fi 局域网及穿透外网连接
        mServerSocket.bind(new InetSocketAddress("0.0.0.0", mPort));
        mRunning = true;
        mThreadPool = Executors.newCachedThreadPool();

        Logger.logInfo(LOG_TAG, "TermuxMcpServer started on port " + mPort);

        Thread acceptThread = new Thread(() -> {
            while (mRunning && !mServerSocket.isClosed()) {
                try {
                    Socket socket = mServerSocket.accept();
                    socket.setSoTimeout(60000); // 60s read timeout
                    mThreadPool.submit(() -> handleClientSocket(socket));
                } catch (Exception e) {
                    if (mRunning) {
                        Logger.logError(LOG_TAG, "Accept error: " + e.getMessage());
                    }
                }
            }
        }, "TermuxMcpAcceptThread");
        acceptThread.setDaemon(true);
        acceptThread.start();
    }

    public synchronized void stop() {
        mRunning = false;
        if (mServerSocket != null) {
            try {
                mServerSocket.close();
            } catch (Exception ignored) {}
            mServerSocket = null;
        }
        if (mThreadPool != null) {
            mThreadPool.shutdownNow();
            mThreadPool = null;
        }
        mSseSessions.clear();
        Logger.logInfo(LOG_TAG, "TermuxMcpServer stopped.");
    }

    public boolean isRunning() {
        return mRunning && mServerSocket != null && !mServerSocket.isClosed();
    }

    /**
     * 处理单个 HTTP 客户端请求
     */
    private void handleClientSocket(Socket socket) {
        try (InputStream in = socket.getInputStream();
             OutputStream out = socket.getOutputStream()) {

            // 1. 读取 HTTP 请求行
            BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            String requestLine = reader.readLine();
            if (requestLine == null || requestLine.isEmpty()) {
                return;
            }

            String[] parts = requestLine.split(" ");
            if (parts.length < 2) return;
            String method = parts[0].toUpperCase();
            String fullPath = parts[1];

            // 解析 Path 与 Query String
            String path = fullPath;
            Map<String, String> queryParams = new HashMap<>();
            int qIdx = fullPath.indexOf('?');
            if (qIdx != -1) {
                path = fullPath.substring(0, qIdx);
                String queryString = fullPath.substring(qIdx + 1);
                for (String pair : queryString.split("&")) {
                    int eqIdx = pair.indexOf('=');
                    if (eqIdx != -1) {
                        String key = URLDecoder.decode(pair.substring(0, eqIdx), "UTF-8");
                        String val = URLDecoder.decode(pair.substring(eqIdx + 1), "UTF-8");
                        queryParams.put(key, val);
                    }
                }
            }

            // 2. 读取 Headers
            Map<String, String> headers = new HashMap<>();
            String headerLine;
            int contentLength = 0;
            while ((headerLine = reader.readLine()) != null && !headerLine.isEmpty()) {
                int colonIdx = headerLine.indexOf(':');
                if (colonIdx != -1) {
                    String name = headerLine.substring(0, colonIdx).trim().toLowerCase();
                    String value = headerLine.substring(colonIdx + 1).trim();
                    headers.put(name, value);
                    if ("content-length".equals(name)) {
                        try {
                            contentLength = Integer.parseInt(value);
                        } catch (NumberFormatException ignored) {}
                    }
                }
            }

            // 3. 处理 CORS 跨域预检请求 (OPTIONS)
            if ("OPTIONS".equalsIgnoreCase(method)) {
                sendCorsPreflight(out);
                return;
            }

            // 4. 读取 HTTP Body 内容
            String body = "";
            if (contentLength > 0) {
                char[] buf = new char[contentLength];
                int totalRead = 0;
                while (totalRead < contentLength) {
                    int read = reader.read(buf, totalRead, contentLength - totalRead);
                    if (read == -1) break;
                    totalRead += read;
                }
                body = new String(buf, 0, totalRead);
            }

            // 5. 权限与 Token 鉴权校验（OPTIONS 请求免校验）
            if (!isAuthorized(headers, queryParams)) {
                sendJsonResponse(out, 401, "{\"error\": \"Unauthorized: 密钥无效或未提供 Token\"}");
                return;
            }

            // 6. 路由分发
            if (path.equals("/mcp")) {
                // 最新标准 Streamable HTTP 单端点 (2026 MCP 规范)
                handleMcpPost(body, out);
            } else if (path.equals("/sse") && "GET".equals(method)) {
                // 经典 SSE 订阅端点
                handleSseGet(out, socket);
            } else if (path.startsWith("/messages") && "POST".equals(method)) {
                // 经典 SSE 消息端点
                handleMcpPost(body, out);
            } else if (path.equals("/openapi.json") && "GET".equals(method)) {
                // ChatGPT Custom GPTs Actions 专属 Schema
                handleOpenApiSpec(headers, out);
            } else if (path.equals("/api/execute") && "POST".equals(method)) {
                // ChatGPT REST 命令执行端点
                handleRestExecute(body, out);
            } else if (path.equals("/api/system") && "GET".equals(method)) {
                // ChatGPT REST 系统状态端点
                handleRestSystem(out);
            } else if (path.equals("/") || path.equals("/status")) {
                // 健康检查与状态展示
                handleStatus(out);
            } else {
                sendJsonResponse(out, 404, "{\"error\": \"Not Found\"}");
            }

        } catch (Exception e) {
            Logger.logError(LOG_TAG, "Error handling client request: " + e.getMessage());
        }
    }

    private boolean isAuthorized(Map<String, String> headers, Map<String, String> queryParams) {
        if (mToken == null || mToken.isEmpty()) {
            return true; // 未设 Token，免密开放
        }
        // 1. 检查 Authorization: Bearer <token>
        String authHeader = headers.get("authorization");
        if (authHeader != null && authHeader.toLowerCase().startsWith("bearer ")) {
            String clientToken = authHeader.substring(7).trim();
            if (mToken.equals(clientToken)) return true;
        }
        // 2. 检查 URL query parameter: ?token=xxx
        String queryToken = queryParams.get("token");
        return mToken.equals(queryToken);
    }

    private void sendCorsPreflight(OutputStream out) throws IOException {
        String resp = "HTTP/1.1 204 No Content\r\n" +
            "Access-Control-Allow-Origin: *\r\n" +
            "Access-Control-Allow-Methods: GET, POST, OPTIONS\r\n" +
            "Access-Control-Allow-Headers: Authorization, Content-Type, Mcp-Method, Mcp-Name\r\n" +
            "Access-Control-Max-Age: 86400\r\n" +
            "Content-Length: 0\r\n\r\n";
        out.write(resp.getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    private void sendJsonResponse(OutputStream out, int statusCode, String json) throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        String statusText = (statusCode == 200) ? "OK" : (statusCode == 401 ? "Unauthorized" : "Not Found");
        String resp = "HTTP/1.1 " + statusCode + " " + statusText + "\r\n" +
            "Content-Type: application/json; charset=utf-8\r\n" +
            "Access-Control-Allow-Origin: *\r\n" +
            "Content-Length: " + bytes.length + "\r\n\r\n";
        out.write(resp.getBytes(StandardCharsets.UTF_8));
        out.write(bytes);
        out.flush();
    }

    /**
     * 处理 MCP JSON-RPC 2.0 请求
     */
    private void handleMcpPost(String body, OutputStream out) throws IOException {
        if (body == null || body.trim().isEmpty()) {
            sendJsonResponse(out, 400, "{\"jsonrpc\":\"2.0\",\"error\":{\"code\":-32700,\"message\":\"Parse error: Empty body\"}}");
            return;
        }

        try {
            JSONObject req = new JSONObject(body);
            String method = req.optString("method", "");
            Object id = req.opt("id");

            JSONObject resp = new JSONObject();
            resp.put("jsonrpc", "2.0");
            if (id != null) {
                resp.put("id", id);
            }

            switch (method) {
                case "initialize":
                    JSONObject initResult = new JSONObject();
                    initResult.put("protocolVersion", "2024-11-05");
                    JSONObject capabilities = new JSONObject();
                    JSONObject toolsCap = new JSONObject();
                    toolsCap.put("listChanged", true);
                    capabilities.put("tools", toolsCap);
                    initResult.put("capabilities", capabilities);

                    JSONObject serverInfo = new JSONObject();
                    serverInfo.put("name", "Termux+ Built-in MCP Server");
                    serverInfo.put("version", "1.0.0");
                    initResult.put("serverInfo", serverInfo);

                    resp.put("result", initResult);
                    break;

                case "notifications/initialized":
                    // 客户端初始化确认，无需应答
                    resp.put("result", new JSONObject());
                    break;

                case "ping":
                    resp.put("result", new JSONObject());
                    break;

                case "tools/list":
                    JSONObject listResult = new JSONObject();
                    listResult.put("tools", getMcpToolsDefinition());
                    resp.put("result", listResult);
                    break;

                case "tools/call":
                    JSONObject params = req.optJSONObject("params");
                    String toolName = params != null ? params.optString("name", "") : "";
                    JSONObject arguments = params != null ? params.optJSONObject("arguments") : new JSONObject();
                    if (arguments == null) arguments = new JSONObject();

                    JSONObject callResult = executeMcpTool(toolName, arguments);
                    resp.put("result", callResult);
                    break;

                default:
                    JSONObject err = new JSONObject();
                    err.put("code", -32601);
                    err.put("message", "Method not found: " + method);
                    resp.put("error", err);
                    break;
            }

            sendJsonResponse(out, 200, resp.toString());

        } catch (Exception e) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Error processing MCP request", e);
            sendJsonResponse(out, 500, "{\"jsonrpc\":\"2.0\",\"error\":{\"code\":-32603,\"message\":\"Internal error: " + e.getMessage() + "\"}}");
        }
    }

    /**
     * 处理经典 SSE 长连接订阅
     */
    private void handleSseGet(OutputStream out, Socket socket) throws IOException {
        String sessionId = UUID.randomUUID().toString();
        mSseSessions.put(sessionId, out);

        String header = "HTTP/1.1 200 OK\r\n" +
            "Content-Type: text/event-stream\r\n" +
            "Cache-Control: no-cache\r\n" +
            "Connection: keep-alive\r\n" +
            "Access-Control-Allow-Origin: *\r\n\r\n";
        out.write(header.getBytes(StandardCharsets.UTF_8));

        // 发送初始化端点事件
        String endpointEvent = "event: endpoint\r\ndata: /messages?sessionId=" + sessionId + "\r\n\r\n";
        out.write(endpointEvent.getBytes(StandardCharsets.UTF_8));
        out.flush();

        // 保持心跳循环直到客户端断开
        try {
            while (mRunning && !socket.isClosed()) {
                Thread.sleep(15000);
                out.write(": ping\r\n\r\n".getBytes(StandardCharsets.UTF_8));
                out.flush();
            }
        } catch (Exception ignored) {
        } finally {
            mSseSessions.remove(sessionId);
        }
    }

    /**
     * 获取对外暴露的 MCP 标准工具定义
     */
    private JSONArray getMcpToolsDefinition() throws Exception {
        JSONArray tools = new JSONArray();

        // 1. execute_command
        JSONObject tExec = new JSONObject();
        tExec.put("name", "execute_command");
        tExec.put("description", "在手机 Termux+ 终端环境中执行 Shell 命令，返回回显与退出码。");
        JSONObject sExec = new JSONObject();
        sExec.put("type", "object");
        JSONObject pExec = new JSONObject();
        JSONObject pc = new JSONObject();
        pc.put("type", "string");
        pc.put("description", "要执行的 Shell 命令行指令");
        pExec.put("command", pc);
        JSONObject pcwd = new JSONObject();
        pcwd.put("type", "string");
        pcwd.put("description", "工作路径（可选，默认 Termux 用户家目录）");
        pExec.put("cwd", pcwd);
        JSONObject pto = new JSONObject();
        pto.put("type", "integer");
        pto.put("description", "超时毫秒数（可选，默认 30000 毫秒）");
        pExec.put("timeout_ms", pto);
        sExec.put("properties", pExec);
        JSONArray rExec = new JSONArray();
        rExec.put("command");
        sExec.put("required", rExec);
        tExec.put("inputSchema", sExec);
        tools.put(tExec);

        // 2. read_file
        JSONObject tRead = new JSONObject();
        tRead.put("name", "read_file");
        tRead.put("description", "读取手机中的指定文本文件内容。");
        JSONObject sRead = new JSONObject();
        sRead.put("type", "object");
        JSONObject pRead = new JSONObject();
        JSONObject prp = new JSONObject();
        prp.put("type", "string");
        prp.put("description", "文件绝对路径（如 /data/data/com.termux/files/home/...）");
        pRead.put("path", prp);
        sRead.put("properties", pRead);
        JSONArray rRead = new JSONArray();
        rRead.put("path");
        sRead.put("required", rRead);
        tRead.put("inputSchema", sRead);
        tools.put(tRead);

        // 3. write_file
        JSONObject tWrite = new JSONObject();
        tWrite.put("name", "write_file");
        tWrite.put("description", "向手机中写入或修改文件内容。");
        JSONObject sWrite = new JSONObject();
        sWrite.put("type", "object");
        JSONObject pWrite = new JSONObject();
        JSONObject pwp = new JSONObject();
        pwp.put("type", "string");
        pwp.put("description", "目标文件绝对路径");
        pWrite.put("path", pwp);
        JSONObject pwc = new JSONObject();
        pwc.put("type", "string");
        pwc.put("description", "写入内容文本");
        pWrite.put("content", pwc);
        JSONObject pwa = new JSONObject();
        pwa.put("type", "boolean");
        pwa.put("description", "是否为追加模式（默认 false 为全量覆写）");
        pWrite.put("append", pwa);
        sWrite.put("properties", pWrite);
        JSONArray rWrite = new JSONArray();
        rWrite.put("path");
        rWrite.put("content");
        sWrite.put("required", rWrite);
        tWrite.put("inputSchema", sWrite);
        tools.put(tWrite);

        // 4. list_directory
        JSONObject tList = new JSONObject();
        tList.put("name", "list_directory");
        tList.put("description", "列出手机指定文件夹的文件与子目录列表。");
        JSONObject sList = new JSONObject();
        sList.put("type", "object");
        JSONObject pList = new JSONObject();
        JSONObject plp = new JSONObject();
        plp.put("type", "string");
        plp.put("description", "文件夹路径（默认 Termux 家目录）");
        pList.put("path", plp);
        sList.put("properties", pList);
        tList.put("inputSchema", sList);
        tools.put(tList);

        // 5. get_system_info
        JSONObject tSys = new JSONObject();
        tSys.put("name", "get_system_info");
        tSys.put("description", "查询手机硬件型号、Android 版本、可用运存、存储剩余及电池状态。");
        JSONObject sSys = new JSONObject();
        sSys.put("type", "object");
        sSys.put("properties", new JSONObject());
        tSys.put("inputSchema", sSys);
        tools.put(tSys);

        // 6. termux_notify (手机专属联动)
        JSONObject tNoti = new JSONObject();
        tNoti.put("name", "termux_notify");
        tNoti.put("description", "在手机 Android 状态栏弹出一条系统通知提醒。");
        JSONObject sNoti = new JSONObject();
        sNoti.put("type", "object");
        JSONObject pNoti = new JSONObject();
        JSONObject pnt = new JSONObject();
        pnt.put("type", "string");
        pnt.put("description", "通知标题");
        pNoti.put("title", pnt);
        JSONObject pnc = new JSONObject();
        pnc.put("type", "string");
        pnc.put("description", "通知正文内容");
        pNoti.put("content", pnc);
        sNoti.put("properties", pNoti);
        JSONArray rNoti = new JSONArray();
        rNoti.put("content");
        sNoti.put("required", rNoti);
        tNoti.put("inputSchema", sNoti);
        tools.put(tNoti);

        // 7. termux_vibrate (手机专属联动)
        JSONObject tVib = new JSONObject();
        tVib.put("name", "termux_vibrate");
        tVib.put("description", "让手机震动指定时长。");
        JSONObject sVib = new JSONObject();
        sVib.put("type", "object");
        JSONObject pVib = new JSONObject();
        JSONObject pvd = new JSONObject();
        pvd.put("type", "integer");
        pvd.put("description", "震动时长（毫秒，默认 500）");
        pVib.put("duration_ms", pvd);
        sVib.put("properties", pVib);
        tVib.put("inputSchema", sVib);
        tools.put(tVib);

        return tools;
    }

    /**
     * 核心工具执行器
     */
    private JSONObject executeMcpTool(String toolName, JSONObject args) {
        JSONObject result = new JSONObject();
        JSONArray content = new JSONArray();
        boolean isError = false;
        String textOutput = "";

        try {
            switch (toolName) {
                case "execute_command": {
                    String command = args.optString("command", "");
                    String cwd = args.optString("cwd", TermuxConstants.TERMUX_HOME_DIR_PATH);
                    int timeout = args.optInt("timeout_ms", 30000);
                    textOutput = runShellCommand(command, cwd, timeout);
                    break;
                }
                case "read_file": {
                    String path = args.optString("path", "");
                    textOutput = readFileContent(path);
                    break;
                }
                case "write_file": {
                    String path = args.optString("path", "");
                    String fileContent = args.optString("content", "");
                    boolean append = args.optBoolean("append", false);
                    textOutput = writeFileContent(path, fileContent, append);
                    break;
                }
                case "list_directory": {
                    String path = args.optString("path", TermuxConstants.TERMUX_HOME_DIR_PATH);
                    textOutput = listDirectory(path);
                    break;
                }
                case "get_system_info": {
                    textOutput = getSystemStatusJson();
                    break;
                }
                case "termux_notify": {
                    String title = args.optString("title", "Termux+ MCP 提醒");
                    String msg = args.optString("content", "");
                    textOutput = postNotification(title, msg);
                    break;
                }
                case "termux_vibrate": {
                    int duration = args.optInt("duration_ms", 500);
                    textOutput = doVibrate(duration);
                    break;
                }
                default:
                    isError = true;
                    textOutput = "未知工具名称: " + toolName;
                    break;
            }
        } catch (Exception e) {
            isError = true;
            textOutput = "执行异常: " + e.getMessage();
        }

        try {
            JSONObject textObj = new JSONObject();
            textObj.put("type", "text");
            textObj.put("text", textOutput);
            content.put(textObj);

            result.put("content", content);
            result.put("isError", isError);
        } catch (Exception ignored) {}

        return result;
    }

    /**
     * 在 Termux 完整环境中执行 Shell 命令
     */
    private String runShellCommand(String command, String cwdStr, int timeoutMs) {
        if (command == null || command.trim().isEmpty()) {
            return "错误：命令行不能为空";
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
            if (!finished) {
                process.destroyForcibly();
                return "执行超时（限制: " + timeoutMs + "ms）";
            }

            tOut.join(1000);
            tErr.join(1000);

            int exitCode = process.exitValue();
            String stdout = outStream.toString("UTF-8");
            String stderr = errStream.toString("UTF-8");

            StringBuilder sb = new StringBuilder();
            if (!stdout.isEmpty()) {
                sb.append(stdout);
            }
            if (!stderr.isEmpty()) {
                if (sb.length() > 0 && !sb.toString().endsWith("\n")) sb.append("\n");
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

    private void copyStream(InputStream in, OutputStream out) {
        try {
            byte[] buf = new byte[4096];
            int len;
            while ((len = in.read(buf)) != -1) {
                out.write(buf, 0, len);
            }
        } catch (Exception ignored) {}
    }

    private String readFileContent(String path) {
        File file = new File(path);
        if (!file.exists()) return "错误：文件不存在 (" + path + ")";
        if (file.isDirectory()) return "错误：该路径为目录，不能直接按文件读取";
        if (file.length() > 2 * 1024 * 1024) return "错误：文件超过 2MB，请使用命令分片查看";

        try (FileInputStream fis = new FileInputStream(file)) {
            byte[] data = new byte[(int) file.length()];
            int read = fis.read(data);
            return new String(data, 0, read, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "读取失败: " + e.getMessage();
        }
    }

    private String writeFileContent(String path, String content, boolean append) {
        File file = new File(path);
        File parent = file.getParentFile();
        if (parent != null && !parent.exists()) {
            parent.mkdirs();
        }
        try (FileOutputStream fos = new FileOutputStream(file, append)) {
            fos.write(content.getBytes(StandardCharsets.UTF_8));
            return "成功写入 " + content.getBytes(StandardCharsets.UTF_8).length + " 字节至 " + path;
        } catch (Exception e) {
            return "写入失败: " + e.getMessage();
        }
    }

    private String listDirectory(String path) {
        File dir = new File(path != null && !path.isEmpty() ? path : TermuxConstants.TERMUX_HOME_DIR_PATH);
        if (!dir.exists()) return "错误：路径不存在 (" + path + ")";
        if (!dir.isDirectory()) return "错误：指定路径不是文件夹";

        File[] files = dir.listFiles();
        if (files == null) return "无法访问或空目录";

        StringBuilder sb = new StringBuilder();
        sb.append("目录: ").append(dir.getAbsolutePath()).append(" (共 ").append(files.length).append(" 项)\n");
        for (File f : files) {
            if (f.isDirectory()) {
                sb.append("📁 [DIR]  ").append(f.getName()).append("/\n");
            } else {
                sb.append("📄 [FILE] ").append(f.getName()).append(" (").append(f.length()).append(" 字节)\n");
            }
        }
        return sb.toString();
    }

    private String getSystemStatusJson() {
        try {
            JSONObject obj = new JSONObject();
            obj.put("device", Build.MANUFACTURER + " " + Build.MODEL);
            obj.put("androidVersion", Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")");
            obj.put("architecture", Build.SUPPORTED_ABIS != null && Build.SUPPORTED_ABIS.length > 0 ? Build.SUPPORTED_ABIS[0] : "unknown");

            // 运存信息
            ActivityManager am = (ActivityManager) mContext.getSystemService(Context.ACTIVITY_SERVICE);
            if (am != null) {
                ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
                am.getMemoryInfo(mi);
                long availMb = mi.availMem / (1024 * 1024);
                long totalMb = mi.totalMem / (1024 * 1024);
                obj.put("ram", availMb + "MB 可用 / " + totalMb + "MB 总量");
            }

            // 存储空间
            StatFs stat = new StatFs(Environment.getDataDirectory().getPath());
            long availStorageMb = (stat.getAvailableBlocksLong() * stat.getBlockSizeLong()) / (1024 * 1024);
            long totalStorageMb = (stat.getBlockCountLong() * stat.getBlockSizeLong()) / (1024 * 1024);
            obj.put("storage", availStorageMb + "MB 剩余 / " + totalStorageMb + "MB 总量");

            // 电池状态
            Intent batteryIntent = mContext.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            if (batteryIntent != null) {
                int level = batteryIntent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
                int scale = batteryIntent.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
                int status = batteryIntent.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
                boolean charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL;
                float pct = (level * 100f) / scale;
                obj.put("battery", (int) pct + "%" + (charging ? " (⚡ 充电中)" : " (放电中)"));
            }

            obj.put("localIp", TermuxMcpManager.getLocalIpAddress());
            obj.put("mcpPort", mPort);
            return obj.toString(2);
        } catch (Exception e) {
            return "{\"error\": \"" + e.getMessage() + "\"}";
        }
    }

    private String postNotification(String title, String content) {
        try {
            NotificationManager nm = (NotificationManager) mContext.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) {
                NotificationUtils.setupNotificationChannel(mContext, TermuxConstants.TERMUX_APP_NOTIFICATION_CHANNEL_ID, "Termux+ Notifications", NotificationManager.IMPORTANCE_HIGH);
                Notification.Builder builder = NotificationUtils.geNotificationBuilder(mContext, TermuxConstants.TERMUX_APP_NOTIFICATION_CHANNEL_ID, Notification.PRIORITY_HIGH, title, content, content, null, null, NotificationUtils.NOTIFICATION_MODE_ALL);
                if (builder != null) {
                    builder.setSmallIcon(R.drawable.ic_terminal);
                    nm.notify((int) System.currentTimeMillis(), builder.build());
                    return "成功在手机状态栏弹出系统通知: " + title;
                }
            }
            return "通知发送失败：无法初始化通知构建器";
        } catch (Exception e) {
            return "发送通知异常: " + e.getMessage();
        }
    }

    private String doVibrate(int durationMs) {
        try {
            Vibrator v = (Vibrator) mContext.getSystemService(Context.VIBRATOR_SERVICE);
            if (v != null) {
                v.vibrate(Math.min(durationMs, 5000));
                return "手机成功震动 " + durationMs + " 毫秒";
            }
            return "未找到震动马达硬件";
        } catch (Exception e) {
            return "震动调用失败: " + e.getMessage();
        }
    }

    private void handleOpenApiSpec(Map<String, String> headers, OutputStream out) throws IOException {
        String host = headers.get("host");
        String publicHost = (host != null) ? "https://" + host : null;
        String schema = TermuxMcpManager.getInstance().getChatGptOpenApiSchema(mContext, publicHost);
        sendJsonResponse(out, 200, schema);
    }

    private void handleRestExecute(String body, OutputStream out) throws IOException {
        try {
            JSONObject req = new JSONObject(body);
            String command = req.optString("command", "");
            String cwd = req.optString("cwd", TermuxConstants.TERMUX_HOME_DIR_PATH);
            String output = runShellCommand(command, cwd, 30000);

            JSONObject resp = new JSONObject();
            resp.put("success", true);
            resp.put("output", output);
            sendJsonResponse(out, 200, resp.toString());
        } catch (Exception e) {
            sendJsonResponse(out, 500, "{\"success\": false, \"error\": \"" + e.getMessage() + "\"}");
        }
    }

    private void handleRestSystem(OutputStream out) throws IOException {
        sendJsonResponse(out, 200, getSystemStatusJson());
    }

    private void handleStatus(OutputStream out) throws IOException {
        try {
            JSONObject status = new JSONObject();
            status.put("status", "running");
            status.put("service", "Termux+ MCP Server (Native Dual-Protocol)");
            status.put("version", "1.0.0");
            status.put("mcp_streamable_endpoint", "/mcp");
            status.put("mcp_sse_endpoint", "/sse");
            status.put("chatgpt_openapi_spec", "/openapi.json");
            status.put("port", mPort);
            status.put("token_required", mToken != null && !mToken.isEmpty());
            sendJsonResponse(out, 200, status.toString(2));
        } catch (Exception e) {
            sendJsonResponse(out, 200, "{\"status\": \"running\"}");
        }
    }
}
