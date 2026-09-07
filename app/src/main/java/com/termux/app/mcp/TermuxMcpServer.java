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

import java.io.BufferedReader;
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

            BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));

            while (mRunning && !socket.isClosed()) {
                // 设置 5 秒 Keep-Alive 空闲等待超时
                try {
                    socket.setSoTimeout(5000);
                } catch (Exception ignored) {}

                // 1. 读取 HTTP 请求行
                String requestLine;
                try {
                    requestLine = reader.readLine();
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
                    continue;
                }

                // 4. 读取 HTTP Body 内容 (支持 Content-Length 与 Transfer-Encoding: chunked 双模)
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
                } else {
                    String te = headers.get("transfer-encoding");
                    if (te != null && te.toLowerCase().contains("chunked")) {
                        StringBuilder sb = new StringBuilder();
                        while (true) {
                            String sizeLine = reader.readLine();
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
                                    String tr = reader.readLine();
                                    if (tr == null || tr.trim().isEmpty()) break;
                                }
                                break;
                            }
                            char[] cbuf = new char[chunkSize];
                            int cread = 0;
                            while (cread < chunkSize) {
                                int r = reader.read(cbuf, cread, chunkSize - cread);
                                if (r == -1) break;
                                cread += r;
                            }
                            sb.append(cbuf, 0, cread);
                            reader.readLine(); // 消耗 CRLF
                        }
                        body = sb.toString();
                    }
                }

                // 读取完成后，解除长任务等待期间的 Socket 读取超时
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
                    sendUnauthorizedResponse(out);
                    break;
                }

                // 6. 路由分发
                if (path.equals("/mcp") || path.equals("/mcp/")) {
                    // 最新标准 Streamable HTTP 单端点 (2026 MCP 规范：支持 POST 消息、GET 事件流、DELETE 会话清理)
                    if ("GET".equalsIgnoreCase(method)) {
                        handleMcpGet(out, socket, headers, queryParams);
                        break;
                    } else if ("DELETE".equalsIgnoreCase(method)) {
                        handleMcpDelete(out, headers, queryParams);
                    } else if ("POST".equalsIgnoreCase(method)) {
                        handleMcpPost(body, out, headers, queryParams, path);
                    } else {
                        sendJsonResponse(out, 405, "{\"jsonrpc\":\"2.0\",\"error\":{\"code\":-32600,\"message\":\"Method not allowed\"}}");
                    }
                } else if (path.equals("/sse") && "GET".equals(method)) {
                    // 经典 SSE 订阅端点（接管长连接直到客户端断开）
                    handleSseGet(out, socket);
                    break;
                } else if ((path.startsWith("/messages") || path.equals("/sse")) && "POST".equals(method)) {
                    // 经典 SSE 消息端点或直接 POST 到 /sse (支持 Streamable HTTP / MCP 双模)
                    handleMcpPost(body, out, headers, queryParams, path);
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
                    handleRestExecute(body, out);
                } else if (path.equals("/api/system") && "GET".equals(method)) {
                    // ChatGPT REST 系统状态端点
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
                    sendJsonResponse(out, 404, "{\"error\": \"Not Found\"}");
                }

                // 客户端若要求关闭连接，则跳出循环
                String connHeader = headers.get("connection");
                if ("close".equalsIgnoreCase(connHeader)) {
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
            "Access-Control-Allow-Methods: GET, POST, OPTIONS\r\n" +
            "Access-Control-Allow-Headers: Authorization, Content-Type, Mcp-Method, Mcp-Name\r\n" +
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
            case 500: return "Internal Server Error";
            default: return "OK";
        }
    }

    private void sendEmptyResponse(OutputStream out, int statusCode) throws IOException {
        String resp = "HTTP/1.1 " + statusCode + " " + getStatusText(statusCode) + "\r\n" +
            "Access-Control-Allow-Origin: *\r\n" +
            "Content-Length: 0\r\n\r\n";
        out.write(resp.getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    /**
     * 按照 MCP 官方 Streamable HTTP 标准（RFC 7230 分块传输编码 + text/event-stream 流式封装）输出响应
     */
    private void sendChunkedSseResponse(OutputStream out, String sessionId, String sseEvent) throws IOException {
        byte[] payload = sseEvent.getBytes(StandardCharsets.UTF_8);
        String chunkHeader = Integer.toHexString(payload.length) + "\r\n";
        String chunkFooter = "\r\n0\r\n\r\n";

        String respHeader = "HTTP/1.1 200 OK\r\n" +
            "Content-Type: text/event-stream\r\n" +
            "Cache-Control: no-cache, no-transform\r\n" +
            "Connection: close\r\n" +
            "x-accel-buffering: no\r\n" +
            "Mcp-Session-Id: " + sessionId + "\r\n" +
            "Transfer-Encoding: chunked\r\n" +
            "Access-Control-Allow-Origin: *\r\n\r\n";

        out.write(respHeader.getBytes(StandardCharsets.UTF_8));
        out.write(chunkHeader.getBytes(StandardCharsets.UTF_8));
        out.write(payload);
        out.write(chunkFooter.getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    private void sendJsonResponse(OutputStream out, int statusCode, String json) throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        String resp = "HTTP/1.1 " + statusCode + " " + getStatusText(statusCode) + "\r\n" +
            "Content-Type: application/json; charset=utf-8\r\n" +
            "Access-Control-Allow-Origin: *\r\n" +
            "Content-Length: " + bytes.length + "\r\n\r\n";
        out.write(resp.getBytes(StandardCharsets.UTF_8));
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
     * 处理 MCP JSON-RPC 2.0 请求（同时支持 Streamable HTTP POST 与 SSE 消息通道）
     */
    private void handleMcpPost(String body, OutputStream out, Map<String, String> headers, Map<String, String> queryParams, String path) throws IOException {
        if (body == null || body.trim().isEmpty()) {
            sendJsonResponse(out, 400, "{\"jsonrpc\":\"2.0\",\"error\":{\"code\":-32700,\"message\":\"Parse error: Empty body\"}}");
            return;
        }

        String sessionId = headers != null ? headers.get("mcp-session-id") : null;
        if ((sessionId == null || sessionId.isEmpty()) && queryParams != null) {
            sessionId = queryParams.get("sessionId");
        }

        try {
            JSONObject req = new JSONObject(body);
            String method = req.optString("method", "");
            Object id = req.opt("id");

            // 1. JSON-RPC 2.0 规范：若请求为 Notification（无 id 或以 notifications/ 开头），绝不可返回带有 body 的 JSON 响应，直接返回标准的 HTTP 202 Accepted 且 Content-Length: 0
            if (id == null || method.startsWith("notifications/")) {
                sendEmptyResponse(out, 202);
                return;
            }

            // 会话状态机：如果是 initialize，生成全新的 Mcp-Session-Id；若是后续请求，保持/刷新对应会话
            if ("initialize".equals(method) || "server/discover".equals(method) || sessionId == null || sessionId.isEmpty()) {
                sessionId = UUID.randomUUID().toString();
                mSessions.put(sessionId, new McpSession(sessionId));
            } else {
                McpSession s = mSessions.get(sessionId);
                if (s != null) {
                    s.lastActiveAt = System.currentTimeMillis();
                } else {
                    mSessions.put(sessionId, new McpSession(sessionId));
                }
            }

            JSONObject resp = new JSONObject();
            resp.put("jsonrpc", "2.0");
            resp.put("id", id);

            switch (method) {
                case "server/discover":
                case "initialize":
                    JSONObject initResult = new JSONObject();
                    String clientVersion = "2024-11-05";
                    if (req.optJSONObject("params") != null) {
                        JSONObject p = req.optJSONObject("params");
                        if (p.has("protocolVersion")) {
                            clientVersion = p.optString("protocolVersion");
                        } else if (p.has("_meta")) {
                            JSONObject meta = p.optJSONObject("_meta");
                            if (meta != null && meta.has("io.modelcontextprotocol/protocolVersion")) {
                                clientVersion = meta.optString("io.modelcontextprotocol/protocolVersion");
                            }
                        }
                    }
                    initResult.put("protocolVersion", clientVersion);

                    // 与官方 Node SDK 严格对齐：仅声明 tools 能力，避免声明未实际提供的空 resources/prompts 导致客户端校验失败
                    JSONObject capabilities = new JSONObject();
                    JSONObject toolsCap = new JSONObject();
                    toolsCap.put("listChanged", true);
                    capabilities.put("tools", toolsCap);
                    initResult.put("capabilities", capabilities);

                    JSONObject serverInfo = new JSONObject();
                    serverInfo.put("name", "Termux+ Built-in MCP Server");
                    serverInfo.put("version", "1.1.0");
                    initResult.put("serverInfo", serverInfo);

                    resp.put("result", initResult);
                    break;

                case "ping":
                    resp.put("result", new JSONObject());
                    break;

                case "logging/setLevel":
                    resp.put("result", new JSONObject());
                    break;

                case "tools/list":
                    JSONObject listResult = new JSONObject();
                    listResult.put("tools", ToolRegistry.getInstance().getMcpToolsDefinition(mContext));
                    resp.put("result", listResult);
                    break;

                case "tools/call":
                    JSONObject params = req.optJSONObject("params");
                    String toolName = params != null ? params.optString("name", "") : "";
                    JSONObject arguments = params != null ? params.optJSONObject("arguments") : new JSONObject();
                    if (arguments == null) arguments = new JSONObject();

                    JSONObject callResult = ToolRegistry.getInstance().executeTool(toolName, arguments, mContext);
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
                    break;
            }

            // 2. 如果有活跃的 SSE 会话（比如客户端正在长连接监听 GET /mcp 或 GET /sse）：
            //    同时通过 SSE 事件通道广播 JSON-RPC 响应
            if (sessionId != null && mSseSessions.containsKey(sessionId)) {
                OutputStream sseOut = mSseSessions.get(sessionId);
                if (sseOut != null) {
                    sendSseNotification(sseOut, "event: message\ndata: " + resp.toString() + "\n\n");
                }
            }

            // 3. 对于当前 HTTP POST /mcp 请求连接本身的响应：
            //    按照官方 Streamable HTTP 标准（RFC 7230 分块传输编码 + text/event-stream 流式封装）输出
            String accept = headers != null ? headers.get("accept") : "";
            boolean isMcpEndpoint = path != null && path.contains("/mcp");
            if (isMcpEndpoint || (accept != null && accept.contains("text/event-stream"))) {
                String sseData = "event: message\ndata: " + resp.toString() + "\n\n";
                sendChunkedSseResponse(out, sessionId, sseData);
            } else {
                byte[] rawBytes = resp.toString().getBytes(StandardCharsets.UTF_8);
                String respHeader = "HTTP/1.1 200 OK\r\n" +
                    "Content-Type: application/json; charset=utf-8\r\n" +
                    "Mcp-Session-Id: " + sessionId + "\r\n" +
                    "Access-Control-Allow-Origin: *\r\n" +
                    "Content-Length: " + rawBytes.length + "\r\n\r\n";
                out.write(respHeader.getBytes(StandardCharsets.UTF_8));
                out.write(rawBytes);
                out.flush();
            }

        } catch (Exception e) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Error processing MCP request", e);
            sendJsonResponse(out, 500, "{\"jsonrpc\":\"2.0\",\"error\":{\"code\":-32603,\"message\":\"Internal error: " + e.getMessage() + "\"}}");
        }
    }

    /**
     * 处理 MCP Streamable HTTP GET 请求（建立持久 SSE 事件流通道）
     */
    private void handleMcpGet(OutputStream out, Socket socket, Map<String, String> headers, Map<String, String> queryParams) throws IOException {
        String sessionId = headers != null ? headers.get("mcp-session-id") : null;
        if ((sessionId == null || sessionId.isEmpty()) && queryParams != null) {
            sessionId = queryParams.get("sessionId");
        }
        if (sessionId == null || sessionId.isEmpty()) {
            sessionId = UUID.randomUUID().toString();
        }

        mSessions.put(sessionId, new McpSession(sessionId));
        mSseSessions.put(sessionId, out);

        StringBuilder sb = new StringBuilder();
        sb.append("HTTP/1.1 200 OK\r\n");
        sb.append("Content-Type: text/event-stream\r\n");
        sb.append("Cache-Control: no-cache, no-transform\r\n");
        sb.append("Connection: keep-alive\r\n");
        sb.append("x-accel-buffering: no\r\n");
        sb.append("mcp-session-id: ").append(sessionId).append("\r\n");
        sb.append("Transfer-Encoding: chunked\r\n");
        sb.append("Access-Control-Allow-Origin: *\r\n\r\n");
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
    private void handleMcpDelete(OutputStream out, Map<String, String> headers, Map<String, String> queryParams) throws IOException {
        String sessionId = headers != null ? headers.get("mcp-session-id") : null;
        if ((sessionId == null || sessionId.isEmpty()) && queryParams != null) {
            sessionId = queryParams.get("sessionId");
        }
        if (sessionId != null && !sessionId.isEmpty()) {
            mSessions.remove(sessionId);
            mSseSessions.remove(sessionId);
        }
        sendEmptyResponse(out, 200);
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
                synchronized (out) {
                    out.write(": ping\r\n\r\n".getBytes(StandardCharsets.UTF_8));
                    out.flush();
                }
            }
        } catch (Exception ignored) {
        } finally {
            mSseSessions.remove(sessionId);
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

    private void handleRestExecute(String body, OutputStream out) throws IOException {
        if (!ToolRegistry.getInstance().isToolAllowed("execute_command", mContext)) {
            sendJsonResponse(out, 403, "{\"success\": false, \"error\": \"权限拒绝：终端执行命令工具已被用户在手机端关闭！\"}");
            return;
        }
        try {
            JSONObject req = new JSONObject(body != null && !body.isEmpty() ? body : "{}");
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
