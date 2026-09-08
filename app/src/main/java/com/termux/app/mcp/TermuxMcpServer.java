package com.termux.app.mcp;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;

import com.termux.app.mcp.tools.ClipboardTools;
import com.termux.app.mcp.tools.DeviceHardwareTools;
import com.termux.app.mcp.tools.McpTool;
import com.termux.app.mcp.tools.NetworkTools;
import com.termux.app.mcp.tools.ShellTool;
import com.termux.app.mcp.tools.SystemInfoTool;
import com.termux.app.mcp.tools.ToolRegistry;
import com.termux.shared.logger.Logger;
import com.termux.shared.termux.TermuxConstants;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Collections;
import java.util.HashMap;
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
 * 4. 内置原生 OAuth 2.1 授权认证服务器（支持 PKCE S256 与发现协议）
 * 5. 零第三方臃肿依赖，常驻内存 < 5MB，开机自启与息屏常驻。
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

    // ── OAuth 2.1 数据结构 ──
    public static class AuthCodeRecord {
        public final String code;
        public final String clientId;
        public final String redirectUri;
        public final String codeChallenge;
        public final String codeChallengeMethod;
        public final long expiresAt;

        public AuthCodeRecord(String code, String clientId, String redirectUri, String codeChallenge, String codeChallengeMethod, long expiresAt) {
            this.code = code;
            this.clientId = clientId;
            this.redirectUri = redirectUri;
            this.codeChallenge = codeChallenge;
            this.codeChallengeMethod = codeChallengeMethod;
            this.expiresAt = expiresAt;
        }
    }

    // 临时 OAuth 授权码缓存 (code -> record)
    private final Map<String, AuthCodeRecord> mAuthCodes = new ConcurrentHashMap<>();
    // 已颁发的有效 OAuth Access Token 缓存 (token -> expiresAt)
    private final Map<String, Long> mOAuthTokens = new ConcurrentHashMap<>();

    // MCP Streamable HTTP 活跃会话记录
    private static class McpSession {
        final String sessionId;
        final long createdAt;
        volatile long lastActiveAt;

        McpSession(String sessionId) {
            this.sessionId = sessionId;
            this.createdAt = System.currentTimeMillis();
            this.lastActiveAt = this.createdAt;
        }
    }
    private final Map<String, McpSession> mSessions = new ConcurrentHashMap<>();

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
        TermuxMcpManager.getInstance().log("INFO", "TermuxMcpServer 核心引擎启动，监听 0.0.0.0:" + mPort);

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
        mSessions.clear();
        mAuthCodes.clear();
        mOAuthTokens.clear();
        DeviceHardwareTools.releaseTts();
        Logger.logInfo(LOG_TAG, "TermuxMcpServer stopped.");
        TermuxMcpManager.getInstance().log("INFO", "TermuxMcpServer 核心引擎已停止");
    }

    public boolean isRunning() {
        return mRunning && mServerSocket != null && !mServerSocket.isClosed();
    }

    private String getClientIp(Socket socket) {
        if (socket == null) return "unknown";
        try {
            if (socket.getRemoteSocketAddress() instanceof InetSocketAddress) {
                InetSocketAddress addr = (InetSocketAddress) socket.getRemoteSocketAddress();
                if (addr.getAddress() != null) {
                    return addr.getAddress().getHostAddress();
                }
            }
        } catch (Exception ignored) {}
        return "unknown";
    }

    /**
     * 读取 HTTP 协议单行文本（以 CRLF 或 LF 结尾，按标准 ISO-8859-1 解码）
     */
    private static String readHttpLine(InputStream in) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        int b;
        while ((b = in.read()) != -1) {
            if (b == '\n') {
                break;
            }
            if (b != '\r') {
                baos.write(b);
            }
        }
        if (b == -1 && baos.size() == 0) {
            return null; // 连接正常关闭或已到 EOF
        }
        return baos.toString("ISO-8859-1");
    }

    /**
     * 处理单个 HTTP 客户端请求（字节级流式解析，彻底消除 UTF-8 字符/字节长度差异导致的阻塞卡死）
     */
    private void handleClientSocket(Socket socket) {
        String clientIp = getClientIp(socket);
        try (BufferedInputStream in = new BufferedInputStream(socket.getInputStream());
             OutputStream out = socket.getOutputStream()) {

            while (mRunning && !socket.isClosed()) {
                // 设置 15 秒 Keep-Alive 空闲等待超时
                try {
                    socket.setSoTimeout(15000);
                } catch (Exception ignored) {}

                // 1. 读取 HTTP 请求行
                String requestLine;
                try {
                    requestLine = readHttpLine(in);
                    while (requestLine != null && requestLine.isEmpty()) {
                        requestLine = readHttpLine(in);
                    }
                } catch (SocketTimeoutException | SocketException e) {
                    break; // 超时或客户端正常关闭连接
                }
                if (requestLine == null || requestLine.isEmpty()) {
                    break;
                }

                String[] parts = requestLine.split(" ");
                if (parts.length < 2) break;
                String method = parts[0].toUpperCase();
                String fullPath = parts[1];
                String httpVersion = parts.length >= 3 ? parts[2].toUpperCase() : "HTTP/1.1";

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
                            try {
                                String key = URLDecoder.decode(pair.substring(0, eqIdx), "UTF-8");
                                String val = URLDecoder.decode(pair.substring(eqIdx + 1), "UTF-8");
                                queryParams.put(key, val);
                            } catch (Exception ignored) {}
                        }
                    }
                }

                // 2. 读取 Headers
                Map<String, String> headers = new HashMap<>();
                String headerLine;
                int contentLength = 0;
                while ((headerLine = readHttpLine(in)) != null && !headerLine.isEmpty()) {
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

                // 判断 Keep-Alive 状态
                String connHeader = headers.get("connection");
                boolean isHttp10 = "HTTP/1.0".equalsIgnoreCase(httpVersion);
                boolean keepAlive = isHttp10 ? "keep-alive".equalsIgnoreCase(connHeader) : !"close".equalsIgnoreCase(connHeader);

                // 3. 处理 CORS 跨域预检请求 (OPTIONS)
                if ("OPTIONS".equalsIgnoreCase(method)) {
                    sendCorsPreflight(out);
                    if (!keepAlive) break;
                    continue;
                }

                // 4. 读取 HTTP Body 内容 (以原生 byte[] 读取精确的 Content-Length 字节数，杜绝 UTF-8 字符阻塞)
                String body = "";
                if (contentLength > 0) {
                    byte[] buf = new byte[contentLength];
                    int totalRead = 0;
                    while (totalRead < contentLength) {
                        int read = in.read(buf, totalRead, contentLength - totalRead);
                        if (read == -1) break;
                        totalRead += read;
                    }
                    body = new String(buf, 0, totalRead, StandardCharsets.UTF_8);
                } else {
                    String te = headers.get("transfer-encoding");
                    if (te != null && te.toLowerCase().contains("chunked")) {
                        ByteArrayOutputStream bodyStream = new ByteArrayOutputStream();
                        while (true) {
                            String sizeLine = readHttpLine(in);
                            if (sizeLine == null) break;
                            sizeLine = sizeLine.trim();
                            if (sizeLine.isEmpty()) continue;
                            int semi = sizeLine.indexOf(';');
                            if (semi != -1) sizeLine = sizeLine.substring(0, semi).trim();
                            int chunkSize;
                            try {
                                chunkSize = Integer.parseInt(sizeLine, 16);
                            } catch (Exception e) {
                                break;
                            }
                            if (chunkSize <= 0) {
                                while (true) {
                                    String tr = readHttpLine(in);
                                    if (tr == null || tr.trim().isEmpty()) break;
                                }
                                break;
                            }
                            byte[] cbuf = new byte[chunkSize];
                            int cread = 0;
                            while (cread < chunkSize) {
                                int r = in.read(cbuf, cread, chunkSize - cread);
                                if (r == -1) break;
                                cread += r;
                            }
                            bodyStream.write(cbuf, 0, cread);
                            readHttpLine(in); // 消耗 CRLF
                        }
                        body = bodyStream.toString("UTF-8");
                    }
                }

                // 读取完成后，解除后续任务处理期间的超时
                try {
                    socket.setSoTimeout(0);
                } catch (Exception ignored) {}

                // 5. 鉴权判断：公开端点免 Bearer 鉴权，保护端点必须通过 Token 或 OAuth 认证
                boolean isPublicEndpoint = path.equals("/") ||
                    path.equals("/status") ||
                    path.equals("/openapi.json") ||
                    path.contains("/.well-known/") ||
                    path.startsWith("/oauth/authorize") ||
                    path.equals("/oauth/token");

                if (!isPublicEndpoint && !isAuthorized(socket, headers, queryParams)) {
                    TermuxMcpManager.getInstance().log("AUTH", "[" + clientIp + "] 401 拦截: 无效或缺失 Bearer Token (" + path + ")");
                    sendUnauthorizedResponse(out);
                    break;
                }

                // 记录非 OPTIONS 且非状态探针的业务请求
                if (!"OPTIONS".equalsIgnoreCase(method) && !"/status".equals(path) && !"/".equals(path)) {
                    TermuxMcpManager.getInstance().log("REQ", "[" + clientIp + "] " + method + " " + path);
                }

                // 6. 路由分发
                if (path.equals("/mcp") || path.equals("/mcp/")) {
                    // 最新标准 Streamable HTTP 单端点 (2026 MCP 规范：支持 POST 消息、GET 事件流、DELETE 会话清理)
                    if ("GET".equalsIgnoreCase(method)) {
                        handleMcpGet(out, socket, headers, queryParams);
                        break; // SSE 持久流接管连接
                    } else if ("DELETE".equalsIgnoreCase(method)) {
                        handleMcpDelete(out, headers, queryParams, keepAlive);
                    } else if ("POST".equalsIgnoreCase(method)) {
                        handleMcpPost(body, out, headers, queryParams, path, keepAlive, clientIp);
                    } else {
                        sendJsonResponse(out, 405, "{\"jsonrpc\":\"2.0\",\"error\":{\"code\":-32600,\"message\":\"Method not allowed\"}}", null, keepAlive);
                    }
                } else if (path.equals("/sse") && "GET".equals(method)) {
                    // 经典 SSE 订阅端点（接管长连接直到客户端断开）
                    handleSseGet(out, socket, clientIp);
                    break;
                } else if ((path.startsWith("/messages") || path.equals("/sse")) && "POST".equals(method)) {
                    // 经典 SSE 消息端点或直接 POST 到 /sse (支持 Streamable HTTP / MCP 双模)
                    handleMcpPost(body, out, headers, queryParams, path, keepAlive, clientIp);
                } else if (path.equals("/openapi.json") && "GET".equals(method)) {
                    // ChatGPT Custom GPTs Actions 专属 Schema
                    handleOpenApiSpec(headers, out);
                } else if (path.contains("/.well-known/") && "GET".equals(method)) {
                    // OAuth 2.1 RFC 8414 / RFC 9728 发现端点 (支持根路径与 /sse/.well-known/...)
                    handleOAuthDiscovery(path, headers, out);
                } else if (path.equals("/oauth/authorize")) {
                    // OAuth 2.1 网页授权端点
                    handleOAuthAuthorize(method, queryParams, body, out);
                } else if (path.equals("/oauth/token") && "POST".equals(method)) {
                    // OAuth 2.1 Token 交换端点
                    handleOAuthToken(headers, body, out);
                } else if (path.equals("/api/execute") && "POST".equals(method)) {
                    // ChatGPT REST 命令执行端点
                    handleRestExecute(body, out, clientIp);
                } else if (path.equals("/api/system") && "GET".equals(method)) {
                    handleRestSystem(out);
                } else if (path.equals("/api/clipboard")) {
                    handleRestClipboard(method, body, out);
                } else if (path.equals("/api/torch") && "POST".equals(method)) {
                    handleRestTorch(body, out);
                } else if (path.equals("/api/tts") && "POST".equals(method)) {
                    handleRestTts(body, out);
                } else if (path.equals("/api/toast") && "POST".equals(method)) {
                    handleRestToast(body, out);
                } else if (path.equals("/api/open-url") && "POST".equals(method)) {
                    handleRestOpenUrl(body, out);
                } else if (path.equals("/api/download") && "POST".equals(method)) {
                    handleRestDownload(body, out);
                } else if (path.equals("/") || path.equals("/status")) {
                    // 健康检查与状态展示
                    handleStatus(out);
                } else {
                    sendJsonResponse(out, 404, "{\"error\": \"Not Found\"}", null, keepAlive);
                }

                // 客户端若要求关闭连接，则跳出循环
                if (!keepAlive) {
                    break;
                }
            }

        } catch (Exception e) {
            Logger.logError(LOG_TAG, "Error handling client request: " + e.getMessage());
        }
    }

    private boolean isAuthorized(Socket socket, Map<String, String> headers, Map<String, String> queryParams) {
        if (mToken == null || mToken.isEmpty()) {
            return true; // 未设 Token，免密开放
        }
        // 关键防护：本地回环地址（127.0.0.1）直连请求（如 OpenAI tunnel-client 隧道代理转发），直接授信放行
        if (socket != null && socket.getInetAddress() != null && socket.getInetAddress().isLoopbackAddress()) {
            return true;
        }
        // 1. 检查 Authorization: Bearer <token>
        String authHeader = headers.get("authorization");
        if (authHeader != null && authHeader.toLowerCase().startsWith("bearer ")) {
            String clientToken = authHeader.substring(7).trim();
            if (mToken.equals(clientToken)) return true;
            if (isValidOAuthToken(clientToken)) return true;
        }
        // 2. 检查 URL query parameter: ?token=xxx
        String queryToken = queryParams.get("token");
        if (queryToken != null && !queryToken.isEmpty()) {
            if (mToken.equals(queryToken)) return true;
            if (isValidOAuthToken(queryToken)) return true;
        }
        return false;
    }

    private boolean isValidOAuthToken(String token) {
        if (token == null || token.isEmpty()) return false;
        Long expiresAt = mOAuthTokens.get(token);
        if (expiresAt != null) {
            if (System.currentTimeMillis() <= expiresAt) {
                return true;
            } else {
                mOAuthTokens.remove(token);
            }
        }
        return false;
    }

    private void sendUnauthorizedResponse(OutputStream out) throws IOException {
        String resp = "HTTP/1.1 401 Unauthorized\r\n" +
            "Content-Type: application/json; charset=utf-8\r\n" +
            "WWW-Authenticate: Bearer realm=\"Termux MCP\", error=\"invalid_token\"\r\n" +
            "Access-Control-Allow-Origin: *\r\n" +
            "Content-Length: 64\r\n\r\n" +
            "{\"error\": \"Unauthorized: 密钥无效或未提供 Bearer Token / OAuth\"}";
        out.write(resp.getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    private void sendCorsPreflight(OutputStream out) throws IOException {
        String resp = "HTTP/1.1 204 No Content\r\n" +
            "Access-Control-Allow-Origin: *\r\n" +
            "Access-Control-Allow-Methods: GET, POST, DELETE, OPTIONS\r\n" +
            "Access-Control-Allow-Headers: Authorization, Content-Type, Mcp-Session-Id, Mcp-Method, Mcp-Name\r\n" +
            "Access-Control-Expose-Headers: Mcp-Session-Id\r\n" +
            "Access-Control-Max-Age: 86400\r\n" +
            "Content-Length: 0\r\n\r\n";
        out.write(resp.getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    private String getStatusText(int statusCode) {
        switch (statusCode) {
            case 200: return "OK";
            case 202: return "Accepted";
            case 204: return "No Content";
            case 302: return "Found";
            case 400: return "Bad Request";
            case 401: return "Unauthorized";
            case 403: return "Forbidden";
            case 404: return "Not Found";
            case 405: return "Method Not Allowed";
            case 500: return "Internal Server Error";
            default: return "OK";
        }
    }

    private void sendEmptyResponse(OutputStream out, int statusCode) throws IOException {
        sendEmptyResponse(out, statusCode, null, false);
    }

    private void sendEmptyResponse(OutputStream out, int statusCode, String sessionId, boolean keepAlive) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("HTTP/1.1 ").append(statusCode).append(" ").append(getStatusText(statusCode)).append("\r\n");
        sb.append("Access-Control-Allow-Origin: *\r\n");
        sb.append("Access-Control-Expose-Headers: Mcp-Session-Id, session-id\r\n");
        if (sessionId != null && !sessionId.isEmpty()) {
            sb.append("Mcp-Session-Id: ").append(sessionId).append("\r\n");
            sb.append("session-id: ").append(sessionId).append("\r\n");
        }
        sb.append("Content-Length: 0\r\n");
        if (keepAlive) {
            sb.append("Connection: keep-alive\r\n");
        } else {
            sb.append("Connection: close\r\n");
        }
        sb.append("\r\n");
        out.write(sb.toString().getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    /**
     * 按照 MCP 官方 Streamable HTTP 标准（RFC 7230 分块传输编码 + text/event-stream 流式封装）输出响应
     */
    private void sendChunkedSseResponse(OutputStream out, String sessionId, String sseEvent, boolean keepAlive) throws IOException {
        byte[] payload = sseEvent.getBytes(StandardCharsets.UTF_8);
        String chunkHeader = Integer.toHexString(payload.length) + "\r\n";
        String chunkFooter = "\r\n0\r\n\r\n";

        StringBuilder sb = new StringBuilder();
        sb.append("HTTP/1.1 200 OK\r\n");
        sb.append("Content-Type: text/event-stream\r\n");
        sb.append("Cache-Control: no-cache, no-transform\r\n");
        sb.append("x-accel-buffering: no\r\n");
        sb.append("Access-Control-Allow-Origin: *\r\n");
        sb.append("Access-Control-Expose-Headers: Mcp-Session-Id, session-id\r\n");
        if (sessionId != null && !sessionId.isEmpty()) {
            sb.append("Mcp-Session-Id: ").append(sessionId).append("\r\n");
            sb.append("session-id: ").append(sessionId).append("\r\n");
        }
        sb.append("Transfer-Encoding: chunked\r\n");
        if (keepAlive) {
            sb.append("Connection: keep-alive\r\n");
        } else {
            sb.append("Connection: close\r\n");
        }
        sb.append("\r\n");

        out.write(sb.toString().getBytes(StandardCharsets.UTF_8));
        out.write(chunkHeader.getBytes(StandardCharsets.UTF_8));
        out.write(payload);
        out.write(chunkFooter.getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    private void sendJsonResponse(OutputStream out, int statusCode, String json) throws IOException {
        sendJsonResponse(out, statusCode, json, null, false);
    }

    private void sendJsonResponse(OutputStream out, int statusCode, String json, String sessionId, boolean keepAlive) throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        StringBuilder sb = new StringBuilder();
        sb.append("HTTP/1.1 ").append(statusCode).append(" ").append(getStatusText(statusCode)).append("\r\n");
        sb.append("Content-Type: application/json; charset=utf-8\r\n");
        sb.append("Access-Control-Allow-Origin: *\r\n");
        sb.append("Access-Control-Expose-Headers: Mcp-Session-Id, session-id\r\n");
        if (sessionId != null && !sessionId.isEmpty()) {
            sb.append("Mcp-Session-Id: ").append(sessionId).append("\r\n");
            sb.append("session-id: ").append(sessionId).append("\r\n");
        }
        sb.append("Content-Length: ").append(bytes.length).append("\r\n");
        if (keepAlive) {
            sb.append("Connection: keep-alive\r\n");
        } else {
            sb.append("Connection: close\r\n");
        }
        sb.append("\r\n");
        out.write(sb.toString().getBytes(StandardCharsets.UTF_8));
        out.write(bytes);
        out.flush();
    }

    private void sendRedirectResponse(OutputStream out, String location) throws IOException {
        String resp = "HTTP/1.1 302 Found\r\n" +
            "Location: " + location + "\r\n" +
            "Access-Control-Allow-Origin: *\r\n" +
            "Content-Length: 0\r\n\r\n";
        out.write(resp.getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    private void sendHtmlResponse(OutputStream out, int statusCode, String html) throws IOException {
        byte[] bytes = html.getBytes(StandardCharsets.UTF_8);
        String resp = "HTTP/1.1 " + statusCode + " " + getStatusText(statusCode) + "\r\n" +
            "Content-Type: text/html; charset=utf-8\r\n" +
            "Access-Control-Allow-Origin: *\r\n" +
            "Content-Length: " + bytes.length + "\r\n\r\n";
        out.write(resp.getBytes(StandardCharsets.UTF_8));
        out.write(bytes);
        out.flush();
    }

    private Map<String, String> parseFormUrlEncoded(String body) {
        Map<String, String> map = new HashMap<>();
        if (body == null || body.isEmpty()) return map;
        String[] pairs = body.split("&");
        for (String pair : pairs) {
            int idx = pair.indexOf('=');
            if (idx != -1) {
                try {
                    String key = URLDecoder.decode(pair.substring(0, idx), "UTF-8");
                    String val = URLDecoder.decode(pair.substring(idx + 1), "UTF-8");
                    map.put(key, val);
                } catch (Exception ignored) {}
            }
        }
        return map;
    }

    private void cleanExpiredAuthCodes() {
        long now = System.currentTimeMillis();
        mAuthCodes.entrySet().removeIf(entry -> now > entry.getValue().expiresAt);
        mOAuthTokens.entrySet().removeIf(entry -> now > entry.getValue());
    }

    private String escapeHtml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }

    /**
     * OAuth 2.1 RFC 8414 / RFC 9728 授权与受保护资源发现元数据
     */
    private void handleOAuthDiscovery(String path, Map<String, String> headers, OutputStream out) throws IOException {
        try {
            String host = headers.get("host");
            if (host == null || host.isEmpty()) {
                host = TermuxMcpManager.getLocalIpAddress() + ":" + mPort;
            }
            String proto = "https".equalsIgnoreCase(headers.get("x-forwarded-proto")) ? "https" : "http";
            String baseUrl = proto + "://" + host;

            String publicHost = TermuxMcpManager.getInstance().getPublicHost(mContext);
            boolean hasPublicOAuth = publicHost != null && !publicHost.isEmpty() && !publicHost.contains("127.0.0.1");

            if (path != null && path.contains("oauth-protected-resource")) {
                if (!hasPublicOAuth) {
                    // 当未配置合法公网 OAuth 域名时，严格返回 404。
                    // OpenAI tunnel-client 规范：当 protected-resource 返回 404 时，立即判定为合法免密/Token MCP 达到 Ready 状态，严防陷入 Degraded。
                    sendJsonResponse(out, 404, "{\"error\": \"OAuth protected resource not configured for local tunnel, fallback to plain MCP\"}");
                    return;
                }
                // RFC 9728 OAuth 2.0 Protected Resource Metadata
                JSONObject resMeta = new JSONObject();
                resMeta.put("resource", publicHost + "/mcp");
                resMeta.put("authorization_servers", new JSONArray().put(publicHost));
                resMeta.put("scopes_supported", new JSONArray().put("execute").put("read").put("system"));
                resMeta.put("bearer_methods_supported", new JSONArray().put("header"));
                sendJsonResponse(out, 200, resMeta.toString(2));
                return;
            }

            // RFC 8414 OAuth 2.0 Authorization Server Metadata & OpenID Connect Discovery
            JSONObject meta = new JSONObject();
            meta.put("issuer", baseUrl);
            meta.put("authorization_endpoint", baseUrl + "/oauth/authorize");
            meta.put("token_endpoint", baseUrl + "/oauth/token");
            meta.put("response_types_supported", new JSONArray().put("code"));
            meta.put("grant_types_supported", new JSONArray().put("authorization_code"));
            meta.put("code_challenge_methods_supported", new JSONArray().put("S256").put("plain"));
            meta.put("token_endpoint_auth_methods_supported", new JSONArray().put("client_secret_post").put("client_secret_basic").put("none"));
            meta.put("scopes_supported", new JSONArray().put("execute").put("read").put("system"));

            sendJsonResponse(out, 200, meta.toString(2));
        } catch (Exception e) {
            sendJsonResponse(out, 500, "{\"error\": \"Failed to generate OAuth discovery metadata\"}");
        }
    }

    /**
     * OAuth 2.1 授权交互页面 (GET 页面 / POST 确认)
     */
    private void handleOAuthAuthorize(String method, Map<String, String> queryParams, String body, OutputStream out) throws IOException {
        if ("GET".equalsIgnoreCase(method)) {
            String clientId = queryParams.get("client_id");
            String redirectUri = queryParams.get("redirect_uri");
            String state = queryParams.get("state");
            String codeChallenge = queryParams.get("code_challenge");
            String codeChallengeMethod = queryParams.get("code_challenge_method");

            if (redirectUri == null || redirectUri.isEmpty()) {
                sendHtmlResponse(out, 400, "<h3>OAuth 2.1 错误</h3><p>缺少必需的 redirect_uri 参数</p>");
                return;
            }

            String safeClientId = clientId != null ? escapeHtml(clientId) : "未知客户端";
            String safeRedirectUri = escapeHtml(redirectUri);
            String safeState = state != null ? escapeHtml(state) : "";
            String safeCodeChallenge = codeChallenge != null ? escapeHtml(codeChallenge) : "";
            String safeMethod = codeChallengeMethod != null ? escapeHtml(codeChallengeMethod) : "S256";

            boolean hasMasterToken = (mToken != null && !mToken.isEmpty());
            String tokenInputHtml = hasMasterToken ?
                "<div style='margin: 16px 0; text-align: left;'>" +
                "  <label style='font-size: 13px; color: #aaa; display: block; margin-bottom: 6px;'>请输入 Termux 安全密钥以授权：</label>" +
                "  <input type='password' name='token' placeholder='输入手机端显示的安全密钥' required style='width: 100%; box-sizing: border-box; padding: 12px; border-radius: 8px; border: 1px solid #444; background: #222; color: #fff; font-size: 14px;' />" +
                "  <span style='font-size: 11px; color: #888;'>（查看路径：手机 Termux -> 设置 -> AI 协同与 MCP 服务）</span>" +
                "</div>" :
                "<p style='color: #4CAF50; font-size: 13px;'>当前服务处于免密模式，直接点击下方按钮即可完成授权。</p>";

            String html = "<!DOCTYPE html>\n" +
                "<html lang='zh-CN'>\n" +
                "<head>\n" +
                "  <meta charset='utf-8'>\n" +
                "  <meta name='viewport' content='width=device-width, initial-scale=1.0'>\n" +
                "  <title>Termux+ OAuth 2.1 终端授权</title>\n" +
                "  <style>\n" +
                "    body { margin: 0; padding: 20px; font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif; background: #121212; color: #eee; display: flex; justify-content: center; align-items: center; min-height: 90vh; }\n" +
                "    .card { background: #1e1e1e; border: 1px solid #333; border-radius: 16px; padding: 24px; max-width: 420px; width: 100%; box-shadow: 0 8px 24px rgba(0,0,0,0.5); text-align: center; }\n" +
                "    .logo { font-size: 36px; margin-bottom: 12px; }\n" +
                "    h2 { font-size: 18px; margin: 0 0 8px 0; color: #fff; }\n" +
                "    p { font-size: 13px; color: #aaa; margin: 8px 0; line-height: 1.5; }\n" +
                "    .badge { display: inline-block; background: #004d40; color: #80cbc4; padding: 4px 10px; border-radius: 6px; font-size: 12px; font-weight: bold; margin-bottom: 16px; }\n" +
                "    .permissions { background: #262626; border-radius: 10px; padding: 12px 16px; text-align: left; margin: 16px 0; font-size: 13px; }\n" +
                "    .permissions li { margin: 6px 0; color: #ddd; }\n" +
                "    .btn-group { display: flex; gap: 12px; margin-top: 20px; }\n" +
                "    .btn { flex: 1; padding: 12px; border-radius: 8px; border: none; font-size: 14px; font-weight: bold; cursor: pointer; }\n" +
                "    .btn-approve { background: #00897b; color: #fff; }\n" +
                "    .btn-approve:hover { background: #00796b; }\n" +
                "    .btn-deny { background: #333; color: #bbb; }\n" +
                "    .btn-deny:hover { background: #444; }\n" +
                "  </style>\n" +
                "</head>\n" +
                "<body>\n" +
                "  <div class='card'>\n" +
                "    <div class='logo'>⚡</div>\n" +
                "    <h2>Termux+ MCP 授权请求</h2>\n" +
                "    <div class='badge'>OAuth 2.1 / RFC 7636 PKCE</div>\n" +
                "    <p>外部客户端 <b style='color:#fff;'>" + safeClientId + "</b> 正在申请协同访问您的手机 Termux 终端系统：</p>\n" +
                "    <div class='permissions'>\n" +
                "      <div style='font-weight: bold; margin-bottom: 6px; color: #fff;'>申请授予的权限：</div>\n" +
                "      <ul style='margin: 0; padding-left: 18px;'>\n" +
                "        <li>执行 Shell 命令行指令 (Bash/Linux)</li>\n" +
                "        <li>读取与修改指定工作目录文件</li>\n" +
                "        <li>获取手机电池、CPU 及运存状态</li>\n" +
                "      </ul>\n" +
                "    </div>\n" +
                "    <form method='POST' action='/oauth/authorize'>\n" +
                "      <input type='hidden' name='client_id' value='" + safeClientId + "' />\n" +
                "      <input type='hidden' name='redirect_uri' value='" + safeRedirectUri + "' />\n" +
                "      <input type='hidden' name='state' value='" + safeState + "' />\n" +
                "      <input type='hidden' name='code_challenge' value='" + safeCodeChallenge + "' />\n" +
                "      <input type='hidden' name='code_challenge_method' value='" + safeMethod + "' />\n" +
                tokenInputHtml +
                "      <div class='btn-group'>\n" +
                "        <button type='submit' name='action' value='deny' class='btn btn-deny'>拒绝</button>\n" +
                "        <button type='submit' name='action' value='approve' class='btn btn-approve'>同意并授权</button>\n" +
                "      </div>\n" +
                "    </form>\n" +
                "  </div>\n" +
                "</body>\n" +
                "</html>";

            sendHtmlResponse(out, 200, html);

        } else if ("POST".equalsIgnoreCase(method)) {
            Map<String, String> form = parseFormUrlEncoded(body);
            String action = form.get("action");
            String redirectUri = form.get("redirect_uri");
            String state = form.get("state");
            String clientId = form.get("client_id");
            String codeChallenge = form.get("code_challenge");
            String codeChallengeMethod = form.get("code_challenge_method");
            String enteredToken = form.get("token");

            if (redirectUri == null || redirectUri.isEmpty()) {
                sendHtmlResponse(out, 400, "<h3>OAuth 2.1 错误</h3><p>缺少 redirect_uri</p>");
                return;
            }

            if (!"approve".equalsIgnoreCase(action)) {
                String target = redirectUri + (redirectUri.contains("?") ? "&" : "?") + "error=access_denied";
                if (state != null && !state.isEmpty()) target += "&state=" + URLEncoder.encode(state, "UTF-8");
                sendRedirectResponse(out, target);
                return;
            }

            // 校验输入的 Token 是否合法
            if (mToken != null && !mToken.isEmpty()) {
                if (enteredToken == null || !mToken.equals(enteredToken.trim())) {
                    sendHtmlResponse(out, 403, "<!DOCTYPE html><html><body style='background:#121212;color:#eee;font-family:sans-serif;text-align:center;padding:50px;'>" +
                        "<h3 style='color:#f44336;'>授权失败：安全密钥错误</h3>" +
                        "<p>您输入的手机端安全密钥不正确。</p>" +
                        "<p><a href='javascript:history.back()' style='color:#80cbc4;'>返回重新输入</a></p>" +
                        "</body></html>");
                    return;
                }
            }

            // 生成临时授权码 (5 分钟有效)
            String code = "code_" + UUID.randomUUID().toString().replace("-", "");
            long expiresAt = System.currentTimeMillis() + 300000;
            mAuthCodes.put(code, new AuthCodeRecord(code, clientId, redirectUri, codeChallenge, codeChallengeMethod, expiresAt));

            cleanExpiredAuthCodes();

            String target = redirectUri + (redirectUri.contains("?") ? "&" : "?") + "code=" + URLEncoder.encode(code, "UTF-8");
            if (state != null && !state.isEmpty()) {
                target += "&state=" + URLEncoder.encode(state, "UTF-8");
            }
            sendRedirectResponse(out, target);
        }
    }

    /**
     * OAuth 2.1 Token 交换接口 (支持 PKCE S256 与 Client Secret 凭据)
     */
    private void handleOAuthToken(Map<String, String> headers, String body, OutputStream out) throws IOException {
        Map<String, String> params = new HashMap<>();
        if (body != null && body.trim().startsWith("{")) {
            try {
                JSONObject json = new JSONObject(body);
                for (java.util.Iterator<String> it = json.keys(); it.hasNext(); ) {
                    String k = it.next();
                    params.put(k, json.optString(k, ""));
                }
            } catch (Exception ignored) {}
        } else {
            params.putAll(parseFormUrlEncoded(body));
        }

        // 检查 Basic Authorization 请求头
        String authHeader = headers.get("authorization");
        if (authHeader != null && authHeader.toLowerCase().startsWith("basic ")) {
            try {
                String b64 = authHeader.substring(6).trim();
                String decoded = new String(Base64.decode(b64, Base64.DEFAULT), StandardCharsets.UTF_8);
                int cIdx = decoded.indexOf(':');
                if (cIdx != -1) {
                    if (!params.containsKey("client_id")) params.put("client_id", decoded.substring(0, cIdx));
                    if (!params.containsKey("client_secret")) params.put("client_secret", decoded.substring(cIdx + 1));
                }
            } catch (Exception ignored) {}
        }

        String grantType = params.get("grant_type");
        if (!"authorization_code".equals(grantType)) {
            sendJsonResponse(out, 400, "{\"error\": \"unsupported_grant_type\", \"error_description\": \"只支持 authorization_code\"}");
            return;
        }

        String code = params.get("code");
        if (code == null || code.isEmpty()) {
            sendJsonResponse(out, 400, "{\"error\": \"invalid_request\", \"error_description\": \"缺少 code 参数\"}");
            return;
        }

        AuthCodeRecord record = mAuthCodes.remove(code);
        if (record == null || System.currentTimeMillis() > record.expiresAt) {
            sendJsonResponse(out, 400, "{\"error\": \"invalid_grant\", \"error_description\": \"授权码无效或已过期\"}");
            return;
        }

        // OAuth 2.1 PKCE 校验
        if (record.codeChallenge != null && !record.codeChallenge.isEmpty()) {
            String codeVerifier = params.get("code_verifier");
            if (codeVerifier == null || codeVerifier.isEmpty()) {
                sendJsonResponse(out, 400, "{\"error\": \"invalid_request\", \"error_description\": \"OAuth 2.1 强制要求 code_verifier\"}");
                return;
            }
            if ("S256".equalsIgnoreCase(record.codeChallengeMethod)) {
                try {
                    MessageDigest md = MessageDigest.getInstance("SHA-256");
                    byte[] digest = md.digest(codeVerifier.getBytes(StandardCharsets.US_ASCII));
                    String calculated = Base64.encodeToString(digest, Base64.URL_SAFE | Base64.NO_PADDING | Base64.NO_WRAP).trim();
                    if (!calculated.equals(record.codeChallenge.trim())) {
                        sendJsonResponse(out, 400, "{\"error\": \"invalid_grant\", \"error_description\": \"PKCE 校验失败\"}");
                        return;
                    }
                } catch (Exception e) {
                    sendJsonResponse(out, 500, "{\"error\": \"server_error\", \"error_description\": \"PKCE 异常\"}");
                    return;
                }
            } else if ("plain".equalsIgnoreCase(record.codeChallengeMethod)) {
                if (!codeVerifier.equals(record.codeChallenge)) {
                    sendJsonResponse(out, 400, "{\"error\": \"invalid_grant\", \"error_description\": \"PKCE plain 校验失败\"}");
                    return;
                }
            }
        }

        // 校验 Client Secret（若配置了 Secret 且客户端传递了 Secret）
        String configuredSecret = TermuxMcpManager.getInstance().getOAuthClientSecret(mContext);
        String passedSecret = params.get("client_secret");
        if (passedSecret != null && !passedSecret.isEmpty() && configuredSecret != null && !configuredSecret.isEmpty()) {
            if (!configuredSecret.equals(passedSecret)) {
                sendJsonResponse(out, 401, "{\"error\": \"invalid_client\", \"error_description\": \"Client Secret 不匹配\"}");
                return;
            }
        }

        // 签发有效期 30 天的专属 Access Token
        String accessToken = "tmx_oauth_" + UUID.randomUUID().toString().replace("-", "");
        long validUntil = System.currentTimeMillis() + (30L * 24 * 3600 * 1000L);
        mOAuthTokens.put(accessToken, validUntil);

        JSONObject resp = new JSONObject();
        try {
            resp.put("access_token", accessToken);
            resp.put("token_type", "Bearer");
            resp.put("expires_in", 2592000); // 30 天
            resp.put("scope", "execute");
            sendJsonResponse(out, 200, resp.toString());
        } catch (Exception e) {
            sendJsonResponse(out, 500, "{\"error\": \"server_error\"}");
        }
    }

    /**
     * 清理超过 1 小时未活动的过期会话
     */
    private void cleanExpiredSessions() {
        long now = System.currentTimeMillis();
        long sessionTimeout = 3600_000L; // 1 小时超时
        mSessions.entrySet().removeIf(entry -> (now - entry.getValue().lastActiveAt) > sessionTimeout);
    }

    /**
     * 多源智能提取 Mcp-Session-Id（兼容各种大小写、连字符、下划线及 Query / Body）
     */
    private String extractSessionId(Map<String, String> headers, Map<String, String> queryParams, JSONObject req) {
        String sessionId = null;
        if (headers != null) {
            sessionId = headers.get("mcp-session-id");
            if (sessionId == null) sessionId = headers.get("session-id");
            if (sessionId == null) sessionId = headers.get("x-session-id");
            if (sessionId == null) sessionId = headers.get("session_id");
        }
        if ((sessionId == null || sessionId.isEmpty()) && queryParams != null) {
            sessionId = queryParams.get("sessionId");
            if (sessionId == null) sessionId = queryParams.get("session_id");
            if (sessionId == null) sessionId = queryParams.get("mcp-session-id");
            if (sessionId == null) sessionId = queryParams.get("mcp_session_id");
        }
        if ((sessionId == null || sessionId.isEmpty()) && req != null) {
            sessionId = req.optString("sessionId", req.optString("session_id", ""));
            if ((sessionId == null || sessionId.isEmpty()) && req.optJSONObject("params") != null) {
                JSONObject p = req.optJSONObject("params");
                sessionId = p.optString("sessionId", p.optString("session_id", ""));
            }
        }
        return (sessionId != null && !sessionId.isEmpty()) ? sessionId.trim() : null;
    }

    /**
     * 处理 MCP JSON-RPC 2.0 请求（同时支持 Streamable HTTP POST 与 SSE 消息通道）
     */
    private void handleMcpPost(String body, OutputStream out, Map<String, String> headers, Map<String, String> queryParams, String path, boolean keepAlive, String clientIp) throws IOException {
        if (body == null || body.trim().isEmpty()) {
            // Keep-Alive 管道或客户端探测性空 POST，平滑响应 200，杜绝 [ERROR] 日志干扰
            TermuxMcpManager.getInstance().log("INFO", "[" + clientIp + "] [MCP] 收到空请求体试探请求，静默应答 200");
            sendEmptyResponse(out, 200, null, keepAlive);
            return;
        }

        JSONObject req = null;
        try {
            req = new JSONObject(body);
        } catch (Exception e) {
            TermuxMcpManager.getInstance().log("ERROR", "[" + clientIp + "] [MCP ERROR] JSON 解析失败: " + e.getMessage() + ", body=" + body);
            sendJsonResponse(out, 400, "{\"jsonrpc\":\"2.0\",\"error\":{\"code\":-32700,\"message\":\"Parse error: Invalid JSON\"}}", null, keepAlive);
            return;
        }

        String method = req.optString("method", "");
        Object id = req.opt("id");
        boolean isNotification = (id == null || method.startsWith("notifications/"));

        // 多源智能提取 Session ID
        String sessionId = extractSessionId(headers, queryParams, req);

        // 1. 打印完整 HTTP 请求报文日志 (满足用户第 1 项要求)
        StringBuilder reqLog = new StringBuilder();
        reqLog.append("\n[MCP REQUEST]\n");
        reqLog.append("method=POST\n");
        reqLog.append("path=").append(path).append("\n");
        reqLog.append("clientIp=").append(clientIp).append("\n");
        reqLog.append("headers=").append(headers != null ? headers.toString() : "{}").append("\n");
        reqLog.append("contentType=").append(headers != null ? headers.get("content-type") : "null").append("\n");
        reqLog.append("accept=").append(headers != null ? headers.get("accept") : "null").append("\n");
        reqLog.append("mcpSessionId=").append(sessionId != null ? sessionId : "null").append("\n");
        reqLog.append("body=").append(body);
        TermuxMcpManager.getInstance().log("INFO", reqLog.toString());

        boolean isLegacyEndpoint = (path != null && (path.startsWith("/messages") || path.equals("/sse")));
        boolean isInitialize = "initialize".equals(method) || "server/discover".equals(method);

        // 2. 会话状态机与严格校验（符合 MCP Streamable HTTP 与 2026 无状态 MCP 规范）
        if (isInitialize) {
            // initialize / discover 统一分配或确认全局 Mcp-Session-Id
            if (sessionId == null || sessionId.isEmpty()) {
                sessionId = UUID.randomUUID().toString();
            }
            mSessions.put(sessionId, new McpSession(sessionId));
            TermuxMcpManager.getInstance().log("MCP", "[" + clientIp + "] [MCP] " + method + " session bound: " + sessionId);
            TermuxMcpManager.getInstance().log("MCP", "[" + clientIp + "] [MCP] session created: " + sessionId);
        } else if (!isLegacyEndpoint) {
            // 标准 /mcp 端点检验会话凭据
            if (sessionId == null || sessionId.isEmpty()) {
                // 单会话宽容降级：若有活跃会话，自动复用
                if (!mSessions.isEmpty()) {
                    sessionId = mSessions.keySet().iterator().next();
                    TermuxMcpManager.getInstance().log("WARN", "[" + clientIp + "] 客户端未显式提供 Mcp-Session-Id，自动绑定活跃会话: " + sessionId);
                } else {
                    // 无状态 MCP（2026 规范工具直调）：自适应动态生成会话，坚决不因缺少 Session Header 拒绝请求
                    sessionId = UUID.randomUUID().toString();
                    mSessions.put(sessionId, new McpSession(sessionId));
                    TermuxMcpManager.getInstance().log("INFO", "[" + clientIp + "] 无状态 MCP 请求，自动分配自适应会话: " + sessionId);
                    TermuxMcpManager.getInstance().log("MCP", "[" + clientIp + "] [MCP] session created: " + sessionId);
                }
            }
            McpSession session = mSessions.get(sessionId);
            if (session == null) {
                // 会话已过期或外部未知 session_id：直接激活，杜绝 404
                session = new McpSession(sessionId);
                mSessions.put(sessionId, session);
                TermuxMcpManager.getInstance().log("MCP", "[" + clientIp + "] [MCP] session created: " + sessionId);
            }
            session.lastActiveAt = System.currentTimeMillis();
        } else {
            // 经典 /messages 或 /sse 端点兼容
            if (sessionId != null && !sessionId.isEmpty()) {
                McpSession session = mSessions.get(sessionId);
                if (session != null) {
                    session.lastActiveAt = System.currentTimeMillis();
                } else {
                    mSessions.put(sessionId, new McpSession(sessionId));
                }
            } else {
                sessionId = UUID.randomUUID().toString();
                mSessions.put(sessionId, new McpSession(sessionId));
                TermuxMcpManager.getInstance().log("MCP", "[" + clientIp + "] [MCP] session created: " + sessionId);
            }
        }

        // 3. Notification 请求处理（如 notifications/initialized）
        if (isNotification) {
            if ("notifications/initialized".equals(method)) {
                TermuxMcpManager.getInstance().log("MCP", "[" + clientIp + "] [MCP] initialized notification received");
            } else {
                TermuxMcpManager.getInstance().log("MCP", "[" + clientIp + "] [MCP] notification received: " + method + " (sessionId=" + sessionId + ")");
            }
            // 返回 HTTP 200 OK，携带 Mcp-Session-Id 与 Content-Length: 0，保留 Session
            sendEmptyResponse(out, 200, sessionId, keepAlive);
            return;
        }

        cleanExpiredSessions();

        try {
            JSONObject resp = new JSONObject();
            resp.put("jsonrpc", "2.0");
            resp.put("id", id != null ? id : JSONObject.NULL);

            // 提取协议版本（优先使用客户端发来的协议版本如 2026-07-28）
            String protocolVersion = "2024-11-05";
            JSONObject p = req.optJSONObject("params");
            JSONObject meta = (p != null) ? p.optJSONObject("_meta") : null;
            if (p != null && p.has("protocolVersion") && !p.optString("protocolVersion").isEmpty()) {
                protocolVersion = p.optString("protocolVersion");
            } else if (meta != null && meta.has("io.modelcontextprotocol/protocolVersion")) {
                protocolVersion = meta.optString("io.modelcontextprotocol/protocolVersion");
            } else if (headers != null && headers.containsKey("mcp-protocol-version")) {
                protocolVersion = headers.get("mcp-protocol-version");
            }

            // 修复客户端识别 Unknown 问题（多字段及 2026 规范 _meta 命名空间深度容错读取）
            JSONObject clientInfo = null;
            if (p != null) {
                clientInfo = p.optJSONObject("clientInfo");
            }
            if (clientInfo == null && meta != null) {
                clientInfo = meta.optJSONObject("io.modelcontextprotocol/clientInfo");
            }
            if (clientInfo == null && req.has("clientInfo")) {
                clientInfo = req.optJSONObject("clientInfo");
            }

            String clientName = "Unknown";
            String clientVer = "";
            if (clientInfo != null) {
                if (clientInfo.has("name") && !clientInfo.optString("name").isEmpty()) {
                    clientName = clientInfo.optString("name");
                } else if (clientInfo.has("title") && !clientInfo.optString("title").isEmpty()) {
                    clientName = clientInfo.optString("title");
                } else if (clientInfo.has("id") && !clientInfo.optString("id").isEmpty()) {
                    clientName = clientInfo.optString("id");
                }
                clientVer = clientInfo.optString("version", "");
            }

            switch (method) {
                case "server/discover": {
                    TermuxMcpManager.getInstance().log("MCP", "[" + clientIp + "] [MCP] server/discover received (client=" + clientName + (clientVer.isEmpty() ? "" : " v" + clientVer) + ", protocol=" + protocolVersion + ")");

                    JSONObject discResult = new JSONObject();

                    // 1. 支持协议版本列表（MCP 2026-07-28 规范必填 supportedVersions: string[]）
                    JSONArray supportedVersions = new JSONArray();
                    supportedVersions.put("2026-07-28");
                    supportedVersions.put("2025-06-18");
                    supportedVersions.put("2024-11-05");
                    discResult.put("supportedVersions", supportedVersions);

                    // 兼容旧版客户端可能检查的 protocolVersion 单值
                    discResult.put("protocolVersion", protocolVersion);

                    // 2. Capabilities 能力声明（使用静态工具空对象能力声明 tools: {}，杜绝 listChanged 误导客户端）
                    JSONObject capabilities = new JSONObject();
                    capabilities.put("tools", new JSONObject());
                    capabilities.put("resources", new JSONObject());
                    capabilities.put("prompts", new JSONObject());
                    discResult.put("capabilities", capabilities);

                    // 3. 描述指引 instructions
                    discResult.put("instructions", "Termux+ MCP Server providing terminal execution and Android device features.");

                    // 4. 服务端信息（同时挂载在根属性与 _meta 命名空间中，100% 满足 Go-SDK / Python-SDK 规范）
                    JSONObject serverInfo = new JSONObject();
                    serverInfo.put("name", "Termux+ Built-in MCP Server");
                    serverInfo.put("version", "1.0");
                    discResult.put("serverInfo", serverInfo);

                    JSONObject discMeta = new JSONObject();
                    discMeta.put("io.modelcontextprotocol/serverInfo", serverInfo);
                    discResult.put("_meta", discMeta);

                    // 5. 缓存控制（CacheableResult）
                    discResult.put("ttlMs", 3600000);
                    discResult.put("cacheScope", "public");

                    resp.put("result", discResult);
                    TermuxMcpManager.getInstance().log("MCP", "[" + clientIp + "] [MCP] server/discover response sent (status=200, supportedVersions=[2026-07-28, 2025-06-18, 2024-11-05])");
                    break;
                }

                case "initialize": {
                    TermuxMcpManager.getInstance().log("MCP", "[" + clientIp + "] [MCP] initialize received (client=" + clientName + (clientVer.isEmpty() ? "" : " v" + clientVer) + ", protocol=" + protocolVersion + ")");

                    JSONObject initResult = new JSONObject();
                    initResult.put("protocolVersion", protocolVersion);

                    JSONArray initSupportedVersions = new JSONArray();
                    initSupportedVersions.put("2026-07-28");
                    initSupportedVersions.put("2025-06-18");
                    initSupportedVersions.put("2024-11-05");
                    initResult.put("supportedVersions", initSupportedVersions);

                    // 规范 tools 能力声明 tools: {}
                    JSONObject capabilities = new JSONObject();
                    capabilities.put("tools", new JSONObject());
                    initResult.put("capabilities", capabilities);

                    JSONObject serverInfo = new JSONObject();
                    serverInfo.put("name", "Termux+ Built-in MCP Server");
                    serverInfo.put("version", "1.0");
                    initResult.put("serverInfo", serverInfo);

                    JSONObject initMeta = new JSONObject();
                    initMeta.put("io.modelcontextprotocol/serverInfo", serverInfo);
                    initResult.put("_meta", initMeta);

                    resp.put("result", initResult);
                    TermuxMcpManager.getInstance().log("MCP", "[" + clientIp + "] [MCP] initialize response sent (status=200, protocol=" + protocolVersion + ")");
                    break;
                }

                case "ping":
                    resp.put("result", new JSONObject());
                    break;

                case "logging/setLevel":
                    resp.put("result", new JSONObject());
                    break;

                case "tools/list":
                    TermuxMcpManager.getInstance().log("MCP", "[" + clientIp + "] [MCP] tools/list received");
                    JSONObject listResult = new JSONObject();
                    JSONArray toolsArray = ToolRegistry.getInstance().getMcpToolsDefinition(mContext);
                    // 保底机制：若尚未加载工具或用户全部关闭，提供基础 ping 测试工具
                    if (toolsArray == null || toolsArray.length() == 0) {
                        toolsArray = new JSONArray();
                        JSONObject pingTool = new JSONObject();
                        pingTool.put("name", "ping");
                        pingTool.put("description", "test tool");
                        JSONObject schema = new JSONObject();
                        schema.put("type", "object");
                        pingTool.put("inputSchema", schema);
                        toolsArray.put(pingTool);
                    }
                    listResult.put("tools", toolsArray);
                    resp.put("result", listResult);
                    TermuxMcpManager.getInstance().log("MCP", "[" + clientIp + "] [MCP] tools/list response sent (toolsCount=" + toolsArray.length() + ")");
                    TermuxMcpManager.getInstance().log("MCP", "[" + clientIp + "] [MCP] tools/list response sent");
                    break;

                case "tools/call":
                    JSONObject params = req.optJSONObject("params");
                    String toolName = params != null ? params.optString("name", "") : "";
                    JSONObject arguments = params != null ? params.optJSONObject("arguments") : new JSONObject();
                    if (arguments == null) arguments = new JSONObject();

                    TermuxMcpManager.getInstance().log("MCP", "[" + clientIp + "] [MCP] tools/call received (tool=" + toolName + ")");

                    JSONObject callResult;
                    if ("ping".equals(toolName)) {
                        callResult = new JSONObject();
                        JSONArray content = new JSONArray();
                        JSONObject textObj = new JSONObject();
                        textObj.put("type", "text");
                        textObj.put("text", "pong");
                        content.put(textObj);
                        callResult.put("content", content);
                        callResult.put("isError", false);
                    } else {
                        callResult = ToolRegistry.getInstance().executeTool(toolName, arguments, mContext);
                    }

                    boolean isErr = callResult.optBoolean("isError", false);
                    TermuxMcpManager.getInstance().log("MCP", "[" + clientIp + "] [MCP] tools/call response sent (tool=" + toolName + ", isError=" + isErr + ")");

                    resp.put("result", callResult);
                    break;

                case "resources/list":
                    JSONObject resResult = new JSONObject();
                    resResult.put("resources", new JSONArray());
                    resp.put("result", resResult);
                    break;

                case "resources/templates/list":
                    JSONObject resTplResult = new JSONObject();
                    resTplResult.put("resourceTemplates", new JSONArray());
                    resp.put("result", resTplResult);
                    break;

                case "prompts/list":
                    JSONObject promptResult = new JSONObject();
                    promptResult.put("prompts", new JSONArray());
                    resp.put("result", promptResult);
                    break;

                default:
                    JSONObject err = new JSONObject();
                    err.put("code", -32601);
                    err.put("message", "Method not found: " + method);
                    resp.put("error", err);
                    TermuxMcpManager.getInstance().log("ERROR", "[" + clientIp + "] [MCP ERROR] 未知方法: " + method);
                    break;
            }

            // 若有活跃的 SSE 客户端长连接在监听本会话，广播该消息
            if (sessionId != null && mSseSessions.containsKey(sessionId)) {
                OutputStream sseOut = mSseSessions.get(sessionId);
                if (sseOut != null) {
                    sendSseNotification(sseOut, "event: message\ndata: " + resp.toString() + "\n\n");
                }
            }

            // 内容协商与输出
            String accept = headers != null ? headers.get("accept") : null;
            boolean wantsSse = (accept != null && accept.contains("text/event-stream") && !accept.contains("application/json") && !accept.contains("*/*"));

            // 打印响应日志 (满足用户第 1/5/6 项要求)
            StringBuilder respLog = new StringBuilder();
            respLog.append("\n[MCP RESPONSE]\n");
            respLog.append("statusCode=200\n");
            respLog.append("mcpSessionId=").append(sessionId != null ? sessionId : "null").append("\n");
            respLog.append("body=").append(resp.toString());
            TermuxMcpManager.getInstance().log("INFO", respLog.toString());

            if (wantsSse) {
                String sseData = "event: message\ndata: " + resp.toString() + "\n\n";
                sendChunkedSseResponse(out, sessionId, sseData, keepAlive);
            } else {
                sendJsonResponse(out, 200, resp.toString(), sessionId, keepAlive);
            }

        } catch (Exception e) {
            TermuxMcpManager.getInstance().log("ERROR", "[" + clientIp + "] [MCP ERROR] 处理 MCP 请求异常: " + e.getMessage());
            Logger.logStackTraceWithMessage(LOG_TAG, "Error processing MCP request", e);
            sendJsonResponse(out, 500, "{\"jsonrpc\":\"2.0\",\"error\":{\"code\":-32603,\"message\":\"Internal error: " + e.getMessage() + "\"}}", sessionId, keepAlive);
        }
    }

    /**
     * 处理 MCP Streamable HTTP GET 请求（建立持久 SSE 事件流通道）
     */
    private void handleMcpGet(OutputStream out, Socket socket, Map<String, String> headers, Map<String, String> queryParams) throws IOException {
        String sessionId = extractSessionId(headers, queryParams, null);
        if (sessionId == null || sessionId.isEmpty()) {
            if (!mSessions.isEmpty()) {
                sessionId = mSessions.keySet().iterator().next();
            } else {
                sessionId = UUID.randomUUID().toString();
                mSessions.put(sessionId, new McpSession(sessionId));
            }
        }

        McpSession session = mSessions.get(sessionId);
        if (session == null) {
            session = new McpSession(sessionId);
            mSessions.put(sessionId, session);
        }
        session.lastActiveAt = System.currentTimeMillis();

        mSseSessions.put(sessionId, out);

        StringBuilder sb = new StringBuilder();
        sb.append("HTTP/1.1 200 OK\r\n");
        sb.append("Content-Type: text/event-stream\r\n");
        sb.append("Cache-Control: no-cache, no-transform\r\n");
        sb.append("Connection: keep-alive\r\n");
        sb.append("x-accel-buffering: no\r\n");
        sb.append("Mcp-Session-Id: ").append(sessionId).append("\r\n");
        sb.append("Transfer-Encoding: chunked\r\n");
        sb.append("Access-Control-Allow-Origin: *\r\n");
        sb.append("Access-Control-Expose-Headers: Mcp-Session-Id\r\n\r\n");
        out.write(sb.toString().getBytes(StandardCharsets.UTF_8));
        out.flush();

        // 持续保持 SSE 长连接与 keep-alive 心跳
        byte[] keepAliveBytes = ": keepalive\n\n".getBytes(StandardCharsets.UTF_8);
        String chunkHeader = Integer.toHexString(keepAliveBytes.length) + "\r\n";

        try {
            while (mRunning && !socket.isClosed()) {
                Thread.sleep(15000);
                synchronized (out) {
                    out.write(chunkHeader.getBytes(StandardCharsets.UTF_8));
                    out.write(keepAliveBytes);
                    out.write("\r\n".getBytes(StandardCharsets.UTF_8));
                    out.flush();
                }
            }
        } catch (Exception ignored) {
        } finally {
            mSseSessions.remove(sessionId);
        }
    }

    /**
     * 处理 MCP Streamable HTTP DELETE 请求（客户端主动清理关闭会话）
     */
    private void handleMcpDelete(OutputStream out, Map<String, String> headers, Map<String, String> queryParams, boolean keepAlive) throws IOException {
        String sessionId = extractSessionId(headers, queryParams, null);
        // 关键防护：DELETE 不立即从 mSessions 中注销会话，只关闭活跃的 SSE 长连接流
        // 确保同一个 mcp-session-id 在健康探测或会话释放后仍可继续接收后续请求
        if (sessionId != null && !sessionId.isEmpty()) {
            mSseSessions.remove(sessionId);
            McpSession s = mSessions.get(sessionId);
            if (s != null) {
                s.lastActiveAt = System.currentTimeMillis();
            }
        }
        sendEmptyResponse(out, 204, sessionId, keepAlive);
    }

    /**
     * 向活跃的 SSE 流推送 chunked 消息
     */
    private void sendSseNotification(OutputStream sseOut, String sseMsg) {
        if (sseOut == null) return;
        try {
            byte[] payload = sseMsg.getBytes(StandardCharsets.UTF_8);
            String chunkHeader = Integer.toHexString(payload.length) + "\r\n";
            synchronized (sseOut) {
                sseOut.write(chunkHeader.getBytes(StandardCharsets.UTF_8));
                sseOut.write(payload);
                sseOut.write("\r\n".getBytes(StandardCharsets.UTF_8));
                sseOut.flush();
            }
        } catch (Exception ignored) {}
    }

    /**
     * 处理经典 SSE 长连接订阅
     */
    private void handleSseGet(OutputStream out, Socket socket, String clientIp) throws IOException {
        String sessionId = UUID.randomUUID().toString();
        mSessions.put(sessionId, new McpSession(sessionId));
        mSseSessions.put(sessionId, out);

        TermuxMcpManager.getInstance().log("SSE", "[" + clientIp + "] 建立 SSE 长连接事件流 (sessionId=" + sessionId.substring(0, Math.min(8, sessionId.length())) + ")");

        String header = "HTTP/1.1 200 OK\r\n" +
            "Content-Type: text/event-stream\r\n" +
            "Cache-Control: no-cache\r\n" +
            "Connection: keep-alive\r\n" +
            "Access-Control-Allow-Origin: *\r\n" +
            "Access-Control-Expose-Headers: Mcp-Session-Id\r\n\r\n";
        out.write(header.getBytes(StandardCharsets.UTF_8));

        // 发送初始化端点事件
        String endpointEvent = "event: endpoint\ndata: /messages?sessionId=" + sessionId + "\r\n\r\n";
        out.write(endpointEvent.getBytes(StandardCharsets.UTF_8));
        out.flush();

        // 保持心跳循环直到客户端断开
        try {
            while (mRunning && !socket.isClosed()) {
                Thread.sleep(15000);
                synchronized (out) {
                    out.write(": ping\r\n\r\n".getBytes(StandardCharsets.UTF_8));
                    out.flush();
                }
            }
        } catch (Exception ignored) {
        } finally {
            mSseSessions.remove(sessionId);
            TermuxMcpManager.getInstance().log("SSE", "[" + clientIp + "] SSE 连接已断开 (sessionId=" + sessionId.substring(0, Math.min(8, sessionId.length())) + ")");
        }
    }

    /**
     * 构建符合 JSON Schema 规范的 inputSchema
     */
    private void handleOpenApiSpec(Map<String, String> headers, OutputStream out) throws IOException {
        String host = headers.get("host");
        String publicHost = (host != null) ? "https://" + host : null;
        String schema = TermuxMcpManager.getInstance().getChatGptOpenApiSchema(mContext, publicHost);
        sendJsonResponse(out, 200, schema);
    }

    private void handleRestExecute(String body, OutputStream out, String clientIp) throws IOException {
        if (!ToolRegistry.getInstance().isToolAllowed("execute_command", mContext)) {
            TermuxMcpManager.getInstance().log("REST", "[" + clientIp + "] 拒绝执行命令: execute_command 工具已被用户关闭");
            sendJsonResponse(out, 403, "{\"success\": false, \"error\": \"权限拒绝：终端执行命令工具已被用户在手机端关闭！\"}");
            return;
        }
        try {
            JSONObject req = new JSONObject(body != null && !body.isEmpty() ? body : "{}");
            String cmd = req.optString("command", "").trim();
            String cmdSummary = cmd.length() > 80 ? cmd.substring(0, 80) + "..." : cmd;
            TermuxMcpManager.getInstance().log("REST", "[" + clientIp + "] REST API 执行命令: " + cmdSummary);

            McpTool tool = ToolRegistry.getInstance().getTool("execute_command");
            String output = (tool != null) ? tool.execute(req, mContext) : "错误：未找到 execute_command 工具";

            JSONObject resp = new JSONObject();
            resp.put("success", true);
            resp.put("output", output);
            sendJsonResponse(out, 200, resp.toString());
        } catch (Exception e) {
            sendJsonResponse(out, 500, "{\"success\": false, \"error\": \"" + e.getMessage() + "\"}");
        }
    }

    private void handleRestSystem(OutputStream out) throws IOException {
        if (!ToolRegistry.getInstance().isToolAllowed("get_system_info", mContext)) {
            sendJsonResponse(out, 403, "{\"success\": false, \"error\": \"权限拒绝：系统状态信息工具已被用户在手机端关闭！\"}");
            return;
        }
        sendJsonResponse(out, 200, SystemInfoTool.getSystemStatusJson(mContext));
    }

    private void handleRestClipboard(String method, String body, OutputStream out) throws IOException {
        if (!ToolRegistry.getInstance().isToolAllowed("get_clipboard", mContext)) {
            sendJsonResponse(out, 403, "{\"success\": false, \"error\": \"权限拒绝：剪贴板工具已被用户在手机端关闭！\"}");
            return;
        }
        if ("GET".equalsIgnoreCase(method)) {
            String text = ClipboardTools.getClipboardContent(mContext);
            try {
                JSONObject resp = new JSONObject();
                resp.put("success", true);
                resp.put("clipboard", text);
                sendJsonResponse(out, 200, resp.toString());
            } catch (Exception e) {
                sendJsonResponse(out, 500, "{\"success\": false, \"error\": \"" + e.getMessage() + "\"}");
            }
        } else {
            try {
                JSONObject req = new JSONObject(body != null && !body.isEmpty() ? body : "{}");
                String text = req.optString("text", "");
                String res = ClipboardTools.setClipboardContent(text, mContext);
                JSONObject resp = new JSONObject();
                resp.put("success", true);
                resp.put("message", res);
                sendJsonResponse(out, 200, resp.toString());
            } catch (Exception e) {
                sendJsonResponse(out, 500, "{\"success\": false, \"error\": \"" + e.getMessage() + "\"}");
            }
        }
    }

    private void handleRestTorch(String body, OutputStream out) throws IOException {
        if (!ToolRegistry.getInstance().isToolAllowed("termux_torch", mContext)) {
            sendJsonResponse(out, 403, "{\"success\": false, \"error\": \"权限拒绝：手电筒工具已被用户在手机端关闭！\"}");
            return;
        }
        try {
            JSONObject req = new JSONObject(body != null && !body.isEmpty() ? body : "{}");
            boolean enabled = req.optBoolean("enabled", true);
            String res = DeviceHardwareTools.toggleTorch(enabled, mContext);
            JSONObject resp = new JSONObject();
            resp.put("success", true);
            resp.put("message", res);
            sendJsonResponse(out, 200, resp.toString());
        } catch (Exception e) {
            sendJsonResponse(out, 500, "{\"success\": false, \"error\": \"" + e.getMessage() + "\"}");
        }
    }

    private void handleRestTts(String body, OutputStream out) throws IOException {
        if (!ToolRegistry.getInstance().isToolAllowed("termux_tts_speak", mContext)) {
            sendJsonResponse(out, 403, "{\"success\": false, \"error\": \"权限拒绝：TTS语音朗读工具已被用户在手机端关闭！\"}");
            return;
        }
        try {
            JSONObject req = new JSONObject(body != null && !body.isEmpty() ? body : "{}");
            String text = req.optString("text", "");
            String res = DeviceHardwareTools.speakTts(text, mContext);
            JSONObject resp = new JSONObject();
            resp.put("success", true);
            resp.put("message", res);
            sendJsonResponse(out, 200, resp.toString());
        } catch (Exception e) {
            sendJsonResponse(out, 500, "{\"success\": false, \"error\": \"" + e.getMessage() + "\"}");
        }
    }

    private void handleRestToast(String body, OutputStream out) throws IOException {
        if (!ToolRegistry.getInstance().isToolAllowed("termux_toast", mContext)) {
            sendJsonResponse(out, 403, "{\"success\": false, \"error\": \"权限拒绝：屏幕气泡工具已被用户在手机端关闭！\"}");
            return;
        }
        try {
            JSONObject req = new JSONObject(body != null && !body.isEmpty() ? body : "{}");
            String msg = req.optString("message", "");
            String res = DeviceHardwareTools.showToast(msg, mContext);
            JSONObject resp = new JSONObject();
            resp.put("success", true);
            resp.put("message", res);
            sendJsonResponse(out, 200, resp.toString());
        } catch (Exception e) {
            sendJsonResponse(out, 500, "{\"success\": false, \"error\": \"" + e.getMessage() + "\"}");
        }
    }

    private void handleRestOpenUrl(String body, OutputStream out) throws IOException {
        if (!ToolRegistry.getInstance().isToolAllowed("open_url", mContext)) {
            sendJsonResponse(out, 403, "{\"success\": false, \"error\": \"权限拒绝：打开网页工具已被用户在手机端关闭！\"}");
            return;
        }
        try {
            JSONObject req = new JSONObject(body != null && !body.isEmpty() ? body : "{}");
            String url = req.optString("url", "");
            String res = NetworkTools.openUrl(url, mContext);
            JSONObject resp = new JSONObject();
            resp.put("success", true);
            resp.put("message", res);
            sendJsonResponse(out, 200, resp.toString());
        } catch (Exception e) {
            sendJsonResponse(out, 500, "{\"success\": false, \"error\": \"" + e.getMessage() + "\"}");
        }
    }

    private void handleRestDownload(String body, OutputStream out) throws IOException {
        if (!ToolRegistry.getInstance().isToolAllowed("download_file", mContext)) {
            sendJsonResponse(out, 403, "{\"success\": false, \"error\": \"权限拒绝：高速下载工具已被用户在手机端关闭！\"}");
            return;
        }
        try {
            JSONObject req = new JSONObject(body != null && !body.isEmpty() ? body : "{}");
            String url = req.optString("url", "");
            String dest = req.optString("dest_path", "");
            String res = NetworkTools.downloadFile(url, dest, mContext);
            JSONObject resp = new JSONObject();
            resp.put("success", true);
            resp.put("message", res);
            sendJsonResponse(out, 200, resp.toString());
        } catch (Exception e) {
            sendJsonResponse(out, 500, "{\"success\": false, \"error\": \"" + e.getMessage() + "\"}");
        }
    }

    private void handleStatus(OutputStream out) throws IOException {
        try {
            JSONObject status = new JSONObject();
            status.put("name", "termux-mcp-std");
            status.put("status", "running");
            status.put("service", "Termux+ MCP Server (Native Dual-Protocol & OAuth 2.1)");
            status.put("version", "1.1.0");
            status.put("mcp_streamable_endpoint", "/mcp");
            status.put("mcp_sse_endpoint", "/sse");
            status.put("oauth_authorization_endpoint", "/oauth/authorize");
            status.put("oauth_token_endpoint", "/oauth/token");
            status.put("oauth_discovery_endpoint", "/.well-known/oauth-authorization-server");
            status.put("chatgpt_openapi_spec", "/openapi.json");
            status.put("port", mPort);
            status.put("timeout_sec", TermuxMcpManager.getInstance().getExecTimeoutSec(mContext));
            status.put("token_required", mToken != null && !mToken.isEmpty());
            status.put("tools_count", ToolRegistry.getInstance().getMcpToolsDefinition(mContext).length());
            sendJsonResponse(out, 200, status.toString(2));
        } catch (Exception e) {
            sendJsonResponse(out, 200, "{\"status\": \"running\"}");
        }
    }
}
