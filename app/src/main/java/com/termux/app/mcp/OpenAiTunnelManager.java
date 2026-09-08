package com.termux.app.mcp;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;

import com.termux.shared.logger.Logger;
import com.termux.shared.termux.TermuxConstants;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.cert.X509Certificate;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.GZIPInputStream;

import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;

/**
 * OpenAI 官方原生 Secure MCP Tunnel (openai/tunnel-client) 独立管理器：
 * 1. 自动从 assets/bin 解压并赋予可执行权限 (+x)；
 * 2. 自动导出/注入 Android 根证书 Bundle (解决 x509 unknown authority)；
 * 3. 智能嗅探本地代理 (v2rayNG:10808, Clash:7890 等)，零配置开箱即用；
 * 4. 守护 tunnel-client 进程，出站直连 OpenAI 控制面，规范化 channel=main 与 Token。
 */
public class OpenAiTunnelManager {

    private static final String LOG_TAG = "OpenAiTunnelManager";
    private static final String PREFS_FILE = "termux_mcp_prefs";

    public static final String PREF_KEY_ENABLED = "mcp_openai_tunnel_enabled";
    public static final String PREF_KEY_TUNNEL_ID = "mcp_openai_tunnel_id";
    public static final String PREF_KEY_API_KEY = "mcp_openai_api_key";
    public static final String PREF_KEY_PROXY = "mcp_openai_proxy";
    public static final String PREF_KEY_TARGET_PORT = "mcp_openai_target_port";

    public enum TunnelState {
        STOPPED("未运行"),
        STARTING("正在启动与适配环境..."),
        CONNECTING("正在连接 OpenAI 控制面..."),
        CONNECTED("已连接至 OpenAI 官方隧道 (ChatGPT 就绪)"),
        ERROR("连接异常");

        private final String desc;
        TunnelState(String desc) { this.desc = desc; }
        public String getDesc() { return desc; }
    }

    public static class ProxyInfo {
        public final String scheme; // "socks5" or "http"
        public final String host;
        public final int port;
        public final String name;

        public ProxyInfo(String scheme, String host, int port, String name) {
            this.scheme = scheme;
            this.host = host;
            this.port = port;
            this.name = name;
        }

        public String getUrl() {
            return scheme + "://" + host + ":" + port;
        }

        @Override
        public String toString() {
            return name + " (" + getUrl() + ")";
        }
    }

    private static volatile OpenAiTunnelManager sInstance;

    private Process mProcess;
    private Context mAppContext;
    private volatile TunnelState mState = TunnelState.STOPPED;
    private volatile String mLastError = "";
    private final Deque<String> mLogBuffer = new ArrayDeque<>(150);
    private final ExecutorService mThreadPool = Executors.newCachedThreadPool();

    public interface StateListener {
        void onStateChanged(TunnelState state, String lastError);
    }

    private StateListener mListener;

    private OpenAiTunnelManager() {}

    public static OpenAiTunnelManager getInstance() {
        if (sInstance == null) {
            synchronized (OpenAiTunnelManager.class) {
                if (sInstance == null) {
                    sInstance = new OpenAiTunnelManager();
                }
            }
        }
        return sInstance;
    }

    private SharedPreferences getPrefs(Context context) {
        return context.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE);
    }

    public boolean isEnabled(Context context) {
        return getPrefs(context).getBoolean(PREF_KEY_ENABLED, false);
    }

    public void setEnabled(Context context, boolean enabled) {
        getPrefs(context).edit().putBoolean(PREF_KEY_ENABLED, enabled).apply();
    }

    public String getTunnelId(Context context) {
        return getPrefs(context).getString(PREF_KEY_TUNNEL_ID, "");
    }

    public void setTunnelId(Context context, String tunnelId) {
        getPrefs(context).edit().putString(PREF_KEY_TUNNEL_ID, tunnelId != null ? tunnelId.trim() : "").apply();
    }

    public String getApiKey(Context context) {
        return getPrefs(context).getString(PREF_KEY_API_KEY, "");
    }

    public void setApiKey(Context context, String apiKey) {
        getPrefs(context).edit().putString(PREF_KEY_API_KEY, apiKey != null ? apiKey.trim() : "").apply();
    }

    public String getProxy(Context context) {
        return getPrefs(context).getString(PREF_KEY_PROXY, "");
    }

    public void setProxy(Context context, String proxy) {
        getPrefs(context).edit().putString(PREF_KEY_PROXY, proxy != null ? proxy.trim() : "").apply();
    }

    public int getTargetPort(Context context) {
        return getPrefs(context).getInt(PREF_KEY_TARGET_PORT, 0);
    }

    public void setTargetPort(Context context, int port) {
        getPrefs(context).edit().putInt(PREF_KEY_TARGET_PORT, port).apply();
    }

    public TunnelState getState() {
        return mState;
    }

    public String getLastError() {
        return mLastError;
    }

    public void setStateListener(StateListener listener) {
        this.mListener = listener;
    }

    private void updateState(TunnelState newState, String errorMsg) {
        this.mState = newState;
        if (errorMsg != null) this.mLastError = errorMsg;
        if (mListener != null) {
            new Handler(Looper.getMainLooper()).post(() -> {
                if (mListener != null) mListener.onStateChanged(newState, mLastError);
            });
        }
    }

    public synchronized boolean isRunning() {
        if (mProcess != null) {
            try {
                mProcess.exitValue();
                return false;
            } catch (IllegalThreadStateException e) {
                return true;
            }
        }
        return false;
    }

    /**
     * 自动解压并安装二进制到 Termux bin 目录
     */
    public synchronized boolean ensureBinariesInstalled(Context context) {
        File binDir = new File(TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH);
        if (!binDir.exists()) binDir.mkdirs();

        File tunnelClient = new File(binDir, "tunnel-client");
        File cloudflared = new File(binDir, "cloudflared");

        boolean ok = true;
        if (!tunnelClient.exists() || tunnelClient.length() == 0) {
            ok = extractAssetGzip(context, "bin/tunnel-client.gz", tunnelClient);
        }
        if (ok && (!cloudflared.exists() || cloudflared.length() == 0)) {
            extractAssetGzip(context, "bin/cloudflared.gz", cloudflared);
        }

        if (tunnelClient.exists()) tunnelClient.setExecutable(true, false);
        if (cloudflared.exists()) cloudflared.setExecutable(true, false);

        return ok && tunnelClient.exists();
    }

    private boolean extractAssetGzip(Context context, String assetPath, File destFile) {
        try (InputStream is = context.getAssets().open(assetPath);
             GZIPInputStream gzis = new GZIPInputStream(is);
             FileOutputStream fos = new FileOutputStream(destFile)) {

            byte[] buffer = new byte[8192];
            int len;
            while ((len = gzis.read(buffer)) > 0) {
                fos.write(buffer, 0, len);
            }
            fos.flush();
            destFile.setExecutable(true, false);
            return true;
        } catch (Exception e) {
            Logger.logError(LOG_TAG, "Failed to extract asset " + assetPath + ": " + e.getMessage());
            return false;
        }
    }

    /**
     * 检测本地指定端口是否处于监听状态
     */
    private static boolean isPortListening(String host, int port) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), 60);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 智能嗅探本地常见代理客户端
     */
    public static ProxyInfo detectLocalProxy() {
        // 1. v2rayNG / Xray SOCKS5 (默认 10808)
        if (isPortListening("127.0.0.1", 10808)) {
            return new ProxyInfo("socks5", "127.0.0.1", 10808, "v2rayNG/Xray SOCKS5");
        }
        // 2. Clash / Mihomo HTTP / Mixed (默认 7890)
        if (isPortListening("127.0.0.1", 7890)) {
            return new ProxyInfo("http", "127.0.0.1", 7890, "Clash HTTP/Mixed");
        }
        // 3. v2rayNG / Xray HTTP (默认 10809)
        if (isPortListening("127.0.0.1", 10809)) {
            return new ProxyInfo("http", "127.0.0.1", 10809, "v2rayNG HTTP");
        }
        // 4. sing-box SOCKS5/Mixed (默认 2080)
        if (isPortListening("127.0.0.1", 2080)) {
            return new ProxyInfo("socks5", "127.0.0.1", 2080, "sing-box SOCKS5");
        }
        // 5. Clash SOCKS5 (默认 7891)
        if (isPortListening("127.0.0.1", 7891)) {
            return new ProxyInfo("socks5", "127.0.0.1", 7891, "Clash SOCKS5");
        }
        // 6. Shadowsocks SOCKS5 (默认 1080)
        if (isPortListening("127.0.0.1", 1080)) {
            return new ProxyInfo("socks5", "127.0.0.1", 1080, "Shadowsocks SOCKS5");
        }
        return null;
    }

    /**
     * 确保本地拥有合法的 PEM 格式 CA 根证书 bundle
     */
    public static synchronized File ensureCaBundle(Context context) {
        // 1. 优先检查 Termux 自带根证书
        File termuxCert = new File(TermuxConstants.TERMUX_PREFIX_DIR_PATH + "/etc/tls/cert.pem");
        if (termuxCert.exists() && termuxCert.length() > 10240) {
            return termuxCert;
        }

        // 2. 检查私有缓存 cacert.pem
        File certFile = new File(context.getFilesDir(), "cacert.pem");
        if (certFile.exists() && certFile.length() > 10240) {
            return certFile;
        }

        // 3. 从 Android 系统 TrustManager 自动导出全部受信任 CA 证书
        try {
            TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            tmf.init((KeyStore) null);

            StringBuilder pemBuilder = new StringBuilder();
            for (TrustManager tm : tmf.getTrustManagers()) {
                if (tm instanceof X509TrustManager) {
                    X509Certificate[] issuers = ((X509TrustManager) tm).getAcceptedIssuers();
                    if (issuers != null) {
                        for (X509Certificate cert : issuers) {
                            pemBuilder.append("-----BEGIN CERTIFICATE-----\n");
                            String b64 = Base64.encodeToString(cert.getEncoded(), Base64.NO_WRAP);
                            for (int i = 0; i < b64.length(); i += 64) {
                                int end = Math.min(i + 64, b64.length());
                                pemBuilder.append(b64, i, end).append("\n");
                            }
                            pemBuilder.append("-----END CERTIFICATE-----\n");
                        }
                    }
                }
            }

            if (pemBuilder.length() > 0) {
                try (FileOutputStream fos = new FileOutputStream(certFile)) {
                    fos.write(pemBuilder.toString().getBytes(StandardCharsets.US_ASCII));
                    fos.flush();
                }
                Logger.logInfo(LOG_TAG, "Exported " + certFile.length() + " bytes system CA certs to " + certFile.getAbsolutePath());
                return certFile;
            }
        } catch (Exception e) {
            Logger.logError(LOG_TAG, "Failed to export Android system CA bundle: " + e.getMessage());
        }

        return null;
    }

    public synchronized boolean isConnectingOrRunning() {
        return mState == TunnelState.STARTING || mState == TunnelState.CONNECTING || mState == TunnelState.CONNECTED || isRunning();
    }

    /**
     * 启动 OpenAI 官方 Secure Tunnel 进程
     */
    public synchronized boolean startTunnel(Context context) {
        mAppContext = context.getApplicationContext();

        String tunnelId = getTunnelId(context);
        String apiKey = getApiKey(context);
        String proxy = getProxy(context);

        if (tunnelId == null || tunnelId.isEmpty()) {
            updateState(TunnelState.ERROR, "未配置 Tunnel ID");
            return false;
        }
        if (apiKey == null || apiKey.isEmpty()) {
            updateState(TunnelState.ERROR, "未配置 Runtime API Key");
            return false;
        }

        // 立即进入启动中状态，主线程绝不执行网络与重度 I/O 操作
        updateState(TunnelState.STARTING, null);

        mThreadPool.execute(() -> {
            try {
                if (mProcess != null) {
                    try {
                        mProcess.destroy();
                        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                            mProcess.destroyForcibly();
                        }
                    } catch (Exception ignored) {}
                    mProcess = null;
                }

                // 启动前强力清理所有残留的孤儿 tunnel-client 进程，杜绝 tunnel-id 冲突
                killStaleTunnelProcesses(context);

                if (!ensureBinariesInstalled(context)) {
                    updateState(TunnelState.ERROR, "无法释放 tunnel-client 二进制可执行文件");
                    return;
                }

                File binFile = new File(TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH, "tunnel-client");
                int targetPort = getTargetPort(context);
                if (targetPort <= 0) {
                    targetPort = TermuxMcpManager.getInstance().getPort(context);
                }

                List<String> cmd = new ArrayList<>();
                cmd.add(binFile.getAbsolutePath());
                cmd.add("run");
                cmd.add("--control-plane.tunnel-id");
                cmd.add(tunnelId);
                // 标准直接 URL 格式，与成功命令完全一致
                cmd.add("--mcp.server-url");
                cmd.add("http://127.0.0.1:" + targetPort + "/mcp");
                cmd.add("--health.listen-addr");
                cmd.add("127.0.0.1:0"); // 自动分配随机空闲健康端口

                File pidFile = new File(context.getFilesDir(), "tunnel-client.pid");
                cmd.add("--pid.file");
                cmd.add(pidFile.getAbsolutePath());

                File healthUrlFile = new File(TermuxConstants.TERMUX_HOME_DIR_PATH, "tunnel-health.url");
                cmd.add("--health.url-file");
                cmd.add(healthUrlFile.getAbsolutePath());

                File logFile = new File(TermuxConstants.TERMUX_HOME_DIR_PATH, "tunnel.log");
                cmd.add("--log.file");
                cmd.add(logFile.getAbsolutePath());

                // 自动带上对应的 Bearer 认证 Token (同时为常规请求与 discovery 探测注入)
                String authToken = (targetPort == 3100) ? "bj1995112@." : TermuxMcpManager.getInstance().getToken(context);
                if (authToken != null && !authToken.trim().isEmpty()) {
                    cmd.add("--mcp.extra-headers");
                    cmd.add("Authorization: Bearer " + authToken.trim());
                    cmd.add("--mcp.discovery-extra-headers");
                    cmd.add("Authorization: Bearer " + authToken.trim());
                }

                // 关键点 3：自动挂载 CA 根证书 Bundle
                File caBundle = ensureCaBundle(context);
                if (caBundle != null && caBundle.exists()) {
                    cmd.add("--ca-bundle");
                    cmd.add(caBundle.getAbsolutePath());
                }

                // 关键点 4：智能识别代理 (在后台线程执行端口探测，严防 NetworkOnMainThreadException)
                ProxyInfo effectiveProxy = null;
                if (proxy != null && !proxy.trim().isEmpty()) {
                    String p = proxy.trim();
                    if (p.startsWith("socks5://") || p.startsWith("socks://")) {
                        String clean = p.replace("socks5://", "").replace("socks://", "");
                        String[] parts = clean.split(":");
                        int port = parts.length > 1 ? Integer.parseInt(parts[1]) : 10808;
                        effectiveProxy = new ProxyInfo("socks5", parts[0], port, "用户指定 SOCKS5");
                    } else if (p.startsWith("http://") || p.startsWith("https://")) {
                        String clean = p.replace("http://", "").replace("https://", "");
                        String[] parts = clean.split(":");
                        int port = parts.length > 1 ? Integer.parseInt(parts[1]) : 7890;
                        effectiveProxy = new ProxyInfo("http", parts[0], port, "用户指定 HTTP");
                    } else if (p.contains(":")) {
                        String[] parts = p.split(":");
                        int port = Integer.parseInt(parts[1]);
                        String scheme = (port == 10808 || port == 7891 || port == 1080) ? "socks5" : "http";
                        effectiveProxy = new ProxyInfo(scheme, parts[0], port, "用户指定代理");
                    }
                } else {
                    // 用户留空，后台智能嗅探
                    try {
                        effectiveProxy = detectLocalProxy();
                    } catch (Exception e) {
                        Logger.logError(LOG_TAG, "Proxy detection error: " + e.getMessage());
                    }
                }

                if (effectiveProxy != null && "http".equalsIgnoreCase(effectiveProxy.scheme)) {
                    // 使用 control-plane 专属 HTTP 代理参数，避免全局代理误拦截本地回环 127.0.0.1 目标端口
                    cmd.add("--control-plane.http-proxy");
                    cmd.add(effectiveProxy.getUrl());
                }

                ProcessBuilder pb = new ProcessBuilder(cmd);
                pb.directory(new File(TermuxConstants.TERMUX_HOME_DIR_PATH));

                Map<String, String> env = pb.environment();
                env.put("CONTROL_PLANE_API_KEY", apiKey);
                env.put("OPENAI_API_KEY", apiKey);
                env.put("PREFIX", TermuxConstants.TERMUX_PREFIX_DIR_PATH);
                env.put("HOME", TermuxConstants.TERMUX_HOME_DIR_PATH);
                env.put("PATH", TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH + ":" + System.getenv("PATH"));
                env.put("TMPDIR", TermuxConstants.TERMUX_TMP_PREFIX_DIR_PATH);

                // 注入证书环境变量
                if (caBundle != null && caBundle.exists()) {
                    env.put("SSL_CERT_FILE", caBundle.getAbsolutePath());
                    env.put("SSL_CERT_DIR", caBundle.getParentFile().getAbsolutePath());
                    env.put("CA_BUNDLE", caBundle.getAbsolutePath());
                }

                // 注入代理环境变量：
                // 1. ALL_PROXY 适用于 SOCKS5 与 HTTP，并指导 Go 远端解析 DNS
                // 2. HTTP_PROXY / HTTPS_PROXY 仅在 scheme 为 http/https 时设置（Go 严禁将 socks5:// 写入 HTTP_PROXY，否则抛 scheme unsupported）
                // 3. 强制注入 NO_PROXY，杜绝 tunnel-client 将本地 127.0.0.1 的 MCP 请求转发至外部代理
                if (effectiveProxy != null) {
                    String url = effectiveProxy.getUrl();
                    env.put("ALL_PROXY", url);
                    env.put("all_proxy", url);
                    if ("http".equalsIgnoreCase(effectiveProxy.scheme) || "https".equalsIgnoreCase(effectiveProxy.scheme)) {
                        env.put("HTTPS_PROXY", url);
                        env.put("HTTP_PROXY", url);
                        env.put("https_proxy", url);
                        env.put("http_proxy", url);
                    }
                }
                env.put("NO_PROXY", "127.0.0.1,localhost,::1");
                env.put("no_proxy", "127.0.0.1,localhost,::1");

                mLogBuffer.clear();
                appendLog("[Termux+] 🚀 正在启动 OpenAI 官方原生安全隧道...");
                appendLog("[Termux+] 🎯 转发目标 MCP: http://127.0.0.1:" + targetPort + "/mcp");
                if (caBundle != null && caBundle.exists()) {
                    appendLog("[Termux+] 🔐 已挂载 CA 根证书: " + caBundle.getName() + " (" + (caBundle.length() / 1024) + " KB)");
                }
                if (effectiveProxy != null) {
                    appendLog("[Termux+] 🌐 " + (proxy == null || proxy.trim().isEmpty() ? "智能嗅探到本地代理" : "使用指定代理") + ": " + effectiveProxy.toString());
                } else {
                    appendLog("[Termux+] 🌐 未检测到本地代理端口，尝试直接网络连接");
                }

                mProcess = pb.start();
                updateState(TunnelState.CONNECTING, null);
                startLogReader(mProcess.getInputStream());
                startLogReader(mProcess.getErrorStream());
            } catch (Exception e) {
                updateState(TunnelState.ERROR, "启动失败: " + e.getMessage());
                Logger.logError(LOG_TAG, "Failed to start tunnel-client: " + e.getMessage());
            }
        });

        return true;
    }

    private void startLogReader(InputStream is) {
        mThreadPool.execute(() -> {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    appendLog(line);
                    inspectLogLine(line);
                }
            } catch (Exception ignored) {}
        });
    }

    private synchronized void appendLog(String line) {
        if (line == null) return;
        if (mLogBuffer.size() >= 150) {
            mLogBuffer.pollFirst();
        }
        mLogBuffer.offerLast(line);
    }

    private void inspectLogLine(String line) {
        String lower = line.toLowerCase();
        if (lower.contains("starting control-plane poller") ||
            lower.contains("registered tunnel") ||
            lower.contains("listening for requests") ||
            lower.contains("tunnel ready") ||
            lower.contains("serving") ||
            (lower.contains("poller") && !lower.contains("failed") && !lower.contains("backing off") && !lower.contains("warn"))) {
            if (mState != TunnelState.CONNECTED) {
                updateState(TunnelState.CONNECTED, null);
            }
        } else if (lower.contains("failed") || lower.contains("error") || lower.contains("fatal")) {
            mLastError = line;
            if (lower.contains("certificate signed by unknown authority") ||
                lower.contains("unsupported protocol") ||
                lower.contains("main channel is required") ||
                (lower.contains("lookup") && lower.contains("connection refused"))) {
                updateState(TunnelState.ERROR, line);
            }
        }
    }

    public synchronized String getRecentLogs() {
        if (mLogBuffer.isEmpty()) {
            return "暂无隧道运行日志";
        }
        StringBuilder sb = new StringBuilder();
        for (String log : mLogBuffer) {
            sb.append(log).append("\n");
        }
        return sb.toString();
    }

    /**
     * 停止 OpenAI 官方 Secure Tunnel 进程
     */
    public synchronized void stopTunnel() {
        stopTunnel(mAppContext);
    }

    public synchronized void stopTunnel(Context context) {
        if (context != null) {
            mAppContext = context.getApplicationContext();
        }
        updateState(TunnelState.STOPPED, null);
        final Process procToKill = mProcess;
        mProcess = null;
        final Context targetContext = context != null ? context : mAppContext;
        mThreadPool.execute(() -> {
            if (procToKill != null) {
                try {
                    procToKill.destroy();
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                        procToKill.destroyForcibly();
                    }
                } catch (Exception ignored) {}
            }
            killStaleTunnelProcesses(targetContext);
        });
    }

    private void killStaleTunnelProcesses(Context context) {
        // 1. 根据 PID 文件精准击杀
        if (context != null) {
            try {
                File pidFile = new File(context.getFilesDir(), "tunnel-client.pid");
                if (pidFile.exists()) {
                    String pidStr = readFileToString(pidFile);
                    if (pidStr != null && !pidStr.trim().isEmpty()) {
                        int pid = Integer.parseInt(pidStr.trim());
                        android.os.Process.killProcess(pid);
                        try {
                            Runtime.getRuntime().exec(new String[]{"kill", "-9", String.valueOf(pid)});
                        } catch (Exception ignored) {}
                    }
                    pidFile.delete();
                }
            } catch (Exception ignored) {}
        }

        // 2. 遍历 /proc 查找自身 UID 下残留的所有 tunnel-client 进程并彻底击杀
        try {
            File procDir = new File("/proc");
            File[] files = procDir.listFiles();
            if (files != null) {
                int myPid = android.os.Process.myPid();
                for (File f : files) {
                    if (f.isDirectory()) {
                        String name = f.getName();
                        if (name.matches("\\d+")) {
                            try {
                                int pid = Integer.parseInt(name);
                                if (pid == myPid) continue;
                                File cmdlineFile = new File(f, "cmdline");
                                if (cmdlineFile.exists() && cmdlineFile.canRead()) {
                                    String cmdline = readFileToString(cmdlineFile);
                                    if (cmdline != null && cmdline.contains("tunnel-client")) {
                                        android.os.Process.killProcess(pid);
                                        try {
                                            Runtime.getRuntime().exec(new String[]{"kill", "-9", String.valueOf(pid)});
                                        } catch (Exception ignored) {}
                                    }
                                }
                            } catch (Exception ignored) {}
                        }
                    }
                }
            }
        } catch (Exception ignored) {}

        // 3. 补充执行 Termux pkill 与 shell killall
        try {
            String pkillPath = new File(TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH, "pkill").getAbsolutePath();
            if (new File(pkillPath).exists()) {
                Runtime.getRuntime().exec(new String[]{pkillPath, "-9", "-f", "tunnel-client"});
            }
            Runtime.getRuntime().exec(new String[]{"sh", "-c", "pkill -9 -f tunnel-client 2>/dev/null || killall -9 tunnel-client 2>/dev/null || true"});
        } catch (Exception ignored) {}
    }

    private String readFileToString(File file) {
        if (!file.exists() || !file.canRead()) return null;
        try (FileInputStream fis = new FileInputStream(file);
             ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
            byte[] buf = new byte[1024];
            int n;
            while ((n = fis.read(buf)) != -1) {
                bos.write(buf, 0, n);
            }
            return bos.toString("UTF-8");
        } catch (Exception e) {
            return null;
        }
    }
}
