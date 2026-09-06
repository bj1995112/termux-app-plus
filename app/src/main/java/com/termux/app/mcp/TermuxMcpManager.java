package com.termux.app.mcp;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.PowerManager;

import com.termux.shared.logger.Logger;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.security.SecureRandom;
import java.util.Collections;
import java.util.List;

/**
 * 终端 MCP 服务全局管理器：统一管理服务生命周期、SharedPreferences 配置、IP 探测与配置模板生成。
 */
public class TermuxMcpManager {

    private static final String LOG_TAG = "TermuxMcpManager";
    private static final String PREF_NAME = "termux_mcp_preferences";

    public static final String PREF_KEY_ENABLED = "mcp_enabled";
    public static final String PREF_KEY_AUTO_START = "mcp_auto_start";
    public static final String PREF_KEY_PORT = "mcp_port";
    public static final String PREF_KEY_TOKEN = "mcp_token";
    public static final String PREF_KEY_EXEC_TIMEOUT_SEC = "mcp_exec_timeout_sec";
    public static final String PREF_KEY_WAKELOCK = "mcp_wakelock";
    public static final String PREF_KEY_OAUTH_CLIENT_ID = "mcp_oauth_client_id";
    public static final String PREF_KEY_OAUTH_CLIENT_SECRET = "mcp_oauth_client_secret";

    public static final int DEFAULT_PORT = 28488;
    public static final int DEFAULT_EXEC_TIMEOUT_SEC = 60;
    public static final String DEFAULT_OAUTH_CLIENT_ID = "termux-mcp";

    private static TermuxMcpManager sInstance;
    private TermuxMcpServer mServer;
    private PowerManager.WakeLock mWakeLock;

    private TermuxMcpManager() {}

    public static synchronized TermuxMcpManager getInstance() {
        if (sInstance == null) {
            sInstance = new TermuxMcpManager();
        }
        return sInstance;
    }

    private static SharedPreferences getPrefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
    }

    public boolean isEnabled(Context context) {
        return getPrefs(context).getBoolean(PREF_KEY_ENABLED, false);
    }

    public void setEnabled(Context context, boolean enabled) {
        getPrefs(context).edit().putBoolean(PREF_KEY_ENABLED, enabled).apply();
    }

    public boolean isAutoStartEnabled(Context context) {
        return getPrefs(context).getBoolean(PREF_KEY_AUTO_START, false);
    }

    public void setAutoStartEnabled(Context context, boolean autoStart) {
        getPrefs(context).edit().putBoolean(PREF_KEY_AUTO_START, autoStart).apply();
    }

    public int getPort(Context context) {
        return getPrefs(context).getInt(PREF_KEY_PORT, DEFAULT_PORT);
    }

    public void setPort(Context context, int port) {
        if (port < 1024 || port > 65535) {
            port = DEFAULT_PORT;
        }
        getPrefs(context).edit().putInt(PREF_KEY_PORT, port).apply();
    }

    public String getToken(Context context) {
        SharedPreferences prefs = getPrefs(context);
        String token = prefs.getString(PREF_KEY_TOKEN, null);
        if (token == null) {
            token = generateRandomToken();
            prefs.edit().putString(PREF_KEY_TOKEN, token).apply();
        }
        return token;
    }

    public void setToken(Context context, String token) {
        getPrefs(context).edit().putString(PREF_KEY_TOKEN, token != null ? token.trim() : "").apply();
    }

    public static String generateRandomToken() {
        SecureRandom random = new SecureRandom();
        byte[] bytes = new byte[8];
        random.nextBytes(bytes);
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    public int getExecTimeoutSec(Context context) {
        return getPrefs(context).getInt(PREF_KEY_EXEC_TIMEOUT_SEC, DEFAULT_EXEC_TIMEOUT_SEC);
    }

    public void setExecTimeoutSec(Context context, int sec) {
        if (sec < 5) sec = 5;
        if (sec > 86400) sec = 86400; // 最大 24 小时
        getPrefs(context).edit().putInt(PREF_KEY_EXEC_TIMEOUT_SEC, sec).apply();
    }

    public boolean isWakeLockEnabled(Context context) {
        return getPrefs(context).getBoolean(PREF_KEY_WAKELOCK, true);
    }

    public void setWakeLockEnabled(Context context, boolean enabled) {
        getPrefs(context).edit().putBoolean(PREF_KEY_WAKELOCK, enabled).apply();
        if (isServerRunning()) {
            if (enabled) {
                acquireWakeLock(context);
            } else {
                releaseWakeLock();
            }
        }
    }

    private synchronized void acquireWakeLock(Context context) {
        if (mWakeLock == null) {
            try {
                PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
                if (pm != null) {
                    mWakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Termux:McpServerWakeLock");
                    mWakeLock.setReferenceCounted(false);
                    mWakeLock.acquire();
                    Logger.logInfo(LOG_TAG, "MCP WakeLock acquired.");
                }
            } catch (Exception e) {
                Logger.logError(LOG_TAG, "Failed to acquire WakeLock: " + e.getMessage());
            }
        }
    }

    private synchronized void releaseWakeLock() {
        if (mWakeLock != null) {
            try {
                if (mWakeLock.isHeld()) {
                    mWakeLock.release();
                }
            } catch (Exception ignored) {}
            mWakeLock = null;
            Logger.logInfo(LOG_TAG, "MCP WakeLock released.");
        }
    }

    public String getOAuthClientId(Context context) {
        return getPrefs(context).getString(PREF_KEY_OAUTH_CLIENT_ID, DEFAULT_OAUTH_CLIENT_ID);
    }

    public void setOAuthClientId(Context context, String clientId) {
        getPrefs(context).edit().putString(PREF_KEY_OAUTH_CLIENT_ID, clientId != null ? clientId.trim() : DEFAULT_OAUTH_CLIENT_ID).apply();
    }

    public String getOAuthClientSecret(Context context) {
        SharedPreferences prefs = getPrefs(context);
        String secret = prefs.getString(PREF_KEY_OAUTH_CLIENT_SECRET, null);
        if (secret == null || secret.isEmpty()) {
            secret = generateRandomToken() + generateRandomToken();
            prefs.edit().putString(PREF_KEY_OAUTH_CLIENT_SECRET, secret).apply();
        }
        return secret;
    }

    public void setOAuthClientSecret(Context context, String secret) {
        getPrefs(context).edit().putString(PREF_KEY_OAUTH_CLIENT_SECRET, secret != null ? secret.trim() : "").apply();
    }

    public String resetOAuthClientSecret(Context context) {
        String secret = generateRandomToken() + generateRandomToken();
        getPrefs(context).edit().putString(PREF_KEY_OAUTH_CLIENT_SECRET, secret).apply();
        return secret;
    }

    public synchronized boolean isServerRunning() {
        return mServer != null && mServer.isRunning();
    }

    public synchronized boolean startServer(Context context) {
        if (isServerRunning()) {
            Logger.logInfo(LOG_TAG, "MCP Server is already running.");
            return true;
        }
        int port = getPort(context);
        String token = getToken(context);
        try {
            mServer = new TermuxMcpServer(context.getApplicationContext(), port, token);
            mServer.start();
            setEnabled(context, true);

            if (isWakeLockEnabled(context)) {
                acquireWakeLock(context);
            }

            Logger.logInfo(LOG_TAG, "MCP Server successfully started on port " + port);
            return true;
        } catch (Exception e) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Failed to start MCP Server", e);
            mServer = null;
            return false;
        }
    }

    public synchronized void stopServer(Context context) {
        if (mServer != null) {
            try {
                mServer.stop();
            } catch (Exception e) {
                Logger.logStackTraceWithMessage(LOG_TAG, "Error stopping MCP Server", e);
            }
            mServer = null;
        }
        releaseWakeLock();
        if (context != null) {
            setEnabled(context, false);
        }
        Logger.logInfo(LOG_TAG, "MCP Server stopped.");
    }

    public synchronized boolean restartServer(Context context) {
        stopServer(context);
        return startServer(context);
    }

    /**
     * 获取本机首选局域网 IP（Wi-Fi 或以太网优先，排除本地回环）
     */
    public static String getLocalIpAddress() {
        try {
            List<NetworkInterface> interfaces = Collections.list(NetworkInterface.getNetworkInterfaces());
            // 优先匹配 wlan (Wi-Fi) 或 eth (以太网)
            for (NetworkInterface nif : interfaces) {
                if (nif.isLoopback() || !nif.isUp()) continue;
                String name = nif.getName().toLowerCase();
                if (name.startsWith("wlan") || name.startsWith("eth") || name.startsWith("rndis")) {
                    for (InetAddress addr : Collections.list(nif.getInetAddresses())) {
                        if (!addr.isLoopbackAddress() && addr instanceof Inet4Address) {
                            return addr.getHostAddress();
                        }
                    }
                }
            }
            // 兜底任意非回环 IPv4
            for (NetworkInterface nif : interfaces) {
                if (nif.isLoopback() || !nif.isUp()) continue;
                for (InetAddress addr : Collections.list(nif.getInetAddresses())) {
                    if (!addr.isLoopbackAddress() && addr instanceof Inet4Address) {
                        return addr.getHostAddress();
                    }
                }
            }
        } catch (Exception ignored) {}
        return "127.0.0.1";
    }

    /**
     * 生成 Cursor 等支持最新 Streamable HTTP 标准的 MCP 客户端配置
     */
    public String getCursorConfigJson(Context context) {
        int port = getPort(context);
        String ip = getLocalIpAddress();
        String token = getToken(context);
        String url = "http://" + ip + ":" + port + "/mcp";

        try {
            JSONObject root = new JSONObject();
            JSONObject mcpServers = new JSONObject();
            JSONObject termux = new JSONObject();
            termux.put("url", url);
            if (token != null && !token.isEmpty()) {
                JSONObject headers = new JSONObject();
                headers.put("Authorization", "Bearer " + token);
                termux.put("headers", headers);
            }
            mcpServers.put("termux-plus", termux);
            root.put("mcpServers", mcpServers);
            return root.toString(2);
        } catch (Exception e) {
            return "{\"mcpServers\":{\"termux-plus\":{\"url\":\"" + url + "\"}}}";
        }
    }

    /**
     * 生成 Claude Desktop 客户端配置（使用标准 mcp-remote 桥接）
     */
    public String getClaudeConfigJson(Context context) {
        int port = getPort(context);
        String ip = getLocalIpAddress();
        String token = getToken(context);
        String sseUrl = "http://" + ip + ":" + port + "/sse" + (token != null && !token.isEmpty() ? "?token=" + token : "");

        try {
            JSONObject root = new JSONObject();
            JSONObject mcpServers = new JSONObject();
            JSONObject termux = new JSONObject();
            termux.put("command", "npx");
            JSONArray args = new JSONArray();
            args.put("-y");
            args.put("mcp-remote");
            args.put(sseUrl);
            termux.put("args", args);
            mcpServers.put("termux-plus", termux);
            root.put("mcpServers", mcpServers);
            return root.toString(2);
        } catch (Exception e) {
            return "{\"mcpServers\":{\"termux-plus\":{\"command\":\"npx\",\"args\":[\"-y\",\"mcp-remote\",\"" + sseUrl + "\"]}}}";
        }
    }

    /**
     * 生成 ChatGPT Custom Actions 专属的完整 OpenAPI 3.1 规范定义
     */
    public String getChatGptOpenApiSchema(Context context, String publicHost) {
        int port = getPort(context);
        String host = (publicHost != null && !publicHost.isEmpty()) ? publicHost : "https://your-public-domain.trycloudflare.com";
        if (host.endsWith("/")) {
            host = host.substring(0, host.length() - 1);
        }

        try {
            JSONObject root = new JSONObject();
            root.put("openapi", "3.1.0");

            JSONObject info = new JSONObject();
            info.put("title", "Termux+ Terminal & System API");
            info.put("description", "允许 ChatGPT 远程与手机上的 Termux+ 终端进行交互、运行命令、读写文件及查询设备状态。");
            info.put("version", "1.0.0");
            root.put("info", info);

            JSONArray servers = new JSONArray();
            JSONObject server = new JSONObject();
            server.put("url", host);
            servers.put(server);
            root.put("servers", servers);

            JSONObject paths = new JSONObject();

            // 1. POST /api/execute
            JSONObject pExec = new JSONObject();
            JSONObject opExec = new JSONObject();
            opExec.put("operationId", "executeCommand");
            opExec.put("summary", "在手机终端执行 Shell 命令行指令");
            JSONObject reqBodyExec = new JSONObject();
            JSONObject contentExec = new JSONObject();
            JSONObject appJsonExec = new JSONObject();
            JSONObject schemaExec = new JSONObject();
            schemaExec.put("type", "object");
            JSONObject propsExec = new JSONObject();
            JSONObject pCmd = new JSONObject();
            pCmd.put("type", "string");
            pCmd.put("description", "要执行的命令行（如 ls -l, uname -a, pkg list 等）");
            propsExec.put("command", pCmd);
            JSONObject pCwd = new JSONObject();
            pCwd.put("type", "string");
            pCwd.put("description", "执行目录（可选，默认 Termux 用户家目录）");
            propsExec.put("cwd", pCwd);
            schemaExec.put("properties", propsExec);
            JSONArray reqExec = new JSONArray();
            reqExec.put("command");
            schemaExec.put("required", reqExec);
            appJsonExec.put("schema", schemaExec);
            contentExec.put("application/json", appJsonExec);
            reqBodyExec.put("content", contentExec);
            reqBodyExec.put("required", true);
            opExec.put("requestBody", reqBodyExec);
            pExec.put("post", opExec);
            paths.put("/api/execute", pExec);

            // 2. GET /api/system
            JSONObject pSys = new JSONObject();
            JSONObject opSys = new JSONObject();
            opSys.put("operationId", "getSystemInfo");
            opSys.put("summary", "查询手机运行状态（型号、电量、CPU、可用运存、存储剩余）");
            pSys.put("get", opSys);
            paths.put("/api/system", pSys);

            root.put("paths", paths);
            return root.toString(2);
        } catch (Exception e) {
            return "{}";
        }
    }

    /**
     * 生成 ngrok 官方极速穿透映射命令
     */
    public String getNgrokCommand(Context context) {
        int port = getPort(context);
        return "ngrok http " + port;
    }

    /**
     * 生成供 ChatGPT Custom Actions 填报的 OAuth 2.1 专属配置清单
     */
    public String getChatGptOAuthSnippet(Context context, String host) {
        int port = getPort(context);
        String baseHost = (host != null && !host.isEmpty()) ? host : "https://你的公网域名(如ngrok或自建穿透)";
        if (baseHost.endsWith("/")) {
            baseHost = baseHost.substring(0, baseHost.length() - 1);
        }

        return "【ChatGPT Custom Action OAuth 2.1 填报清单】\n\n" +
            "1. 身份验证类型 (Authentication Type): OAuth\n" +
            "2. 客户端 ID (Client ID): " + getOAuthClientId(context) + "\n" +
            "3. 客户端密钥 (Client Secret): " + getOAuthClientSecret(context) + "\n" +
            "4. 授权 URL (Authorization URL): " + baseHost + "/oauth/authorize\n" +
            "5. 令牌 URL (Token URL): " + baseHost + "/oauth/token\n" +
            "6. 作用域 (Scope): execute\n" +
            "7. 令牌交换方式 (Token Exchange Method): POST (支持 PKCE S256 与 Client Secret 凭据)\n\n" +
            "※ 温馨提示：如果使用 ngrok / FRP 等公网穿透，请将上述 Authorization URL 和 Token URL 中的前缀替换为您生成的公网 HTTPS 域名（例如 https://xxxx.ngrok-free.dev）。";
    }
}
