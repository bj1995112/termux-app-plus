package com.termux.app.mcp;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkInfo;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;

import com.termux.shared.logger.Logger;
import com.termux.shared.termux.TermuxConstants;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
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
 * 3. 智能全能嗅探本地代理 (Clash: 7890/7891, v2rayNG: 10808/10809, sing-box: 2080/2081, SS: 1080 等)，自动识别协议；
 * 4. 内置纯 Java 轻量级透明 HTTP-to-SOCKS5 桥接，解决 tunnel-client 不支持 SOCKS5 以及 Android 缺失 resolv.conf 的 DNS 问题；
 * 5. 实时标准输入输出日志管道与 ~/tunnel.log 双写，彻底解决状态卡在 CONNECTING 的假死问题；
 * 6. 守护 tunnel-client 进程，出站直连 OpenAI 控制面，规范化 channel=main 与 Token。
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

    public static class LocalHttpToSocks5Bridge {
        private final String mSocksHost;
        private final int mSocksPort;
        private ServerSocket mServerSocket;
        private volatile boolean mIsRunning = false;
        private final List<Socket> mActiveSockets = new ArrayList<>();
        private final ExecutorService mBridgeThreadPool = Executors.newCachedThreadPool();

        public LocalHttpToSocks5Bridge(String socksHost, int socksPort) {
            this.mSocksHost = socksHost;
            this.mSocksPort = socksPort;
        }

        public synchronized int start() throws IOException {
            mServerSocket = new ServerSocket();
            mServerSocket.setReuseAddress(true);
            mServerSocket.bind(new InetSocketAddress("127.0.0.1", 0));
            mIsRunning = true;
            int port = mServerSocket.getLocalPort();
            mBridgeThreadPool.execute(this::acceptLoop);
            return port;
        }

        public int getPort() {
            return mServerSocket != null ? mServerSocket.getLocalPort() : -1;
        }

        private void acceptLoop() {
            while (mIsRunning && mServerSocket != null && !mServerSocket.isClosed()) {
                try {
                    Socket clientSocket = mServerSocket.accept();
                    clientSocket.setTcpNoDelay(true);
                    synchronized (mActiveSockets) { mActiveSockets.add(clientSocket); }
                    mBridgeThreadPool.execute(() -> handleClient(clientSocket));
                } catch (Exception e) {
                    if (!mIsRunning) break;
                }
            }
        }

        private void handleClient(Socket clientSocket) {
            Socket socksSocket = null;
            try {
                InputStream clientIn = clientSocket.getInputStream();
                OutputStream clientOut = clientSocket.getOutputStream();
                ByteArrayOutputStream headerBuf = new ByteArrayOutputStream();
                int b, state = 0;
                while ((b = clientIn.read()) != -1) {
                    headerBuf.write(b);
                    if (state == 0 && b == '\r') state = 1;
                    else if (state == 1 && b == '\n') state = 2;
                    else if (state == 2 && b == '\r') state = 3;
                    else if (state == 3 && b == '\n') break;
                    else state = (b == '\r') ? 1 : 0;
                    if (headerBuf.size() > 16384) throw new IOException("HTTP header too large");
                }

                String headerStr = headerBuf.toString(StandardCharsets.US_ASCII.name());
                int firstLineEnd = headerStr.indexOf("\r\n");
                if (firstLineEnd == -1) { closeQuietly(clientSocket); return; }
                String requestLine = headerStr.substring(0, firstLineEnd).trim();
                if (!requestLine.toUpperCase().startsWith("CONNECT ")) {
                    clientOut.write("HTTP/1.1 400 Bad Request\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
                    clientOut.flush(); closeQuietly(clientSocket); return;
                }
                String[] parts = requestLine.split(" ");
                if (parts.length < 2) { closeQuietly(clientSocket); return; }
                String target = parts[1];
                String targetHost;
                int targetPort = 443;
                int colonIdx = target.lastIndexOf(':');
                if (colonIdx != -1) {
                    targetHost = target.substring(0, colonIdx);
                    try { targetPort = Integer.parseInt(target.substring(colonIdx + 1)); } catch (Exception ignored) {}
                } else targetHost = target;

                socksSocket = new Socket();
                socksSocket.connect(new InetSocketAddress(mSocksHost, mSocksPort), 5000);
                socksSocket.setTcpNoDelay(true);
                synchronized (mActiveSockets) { mActiveSockets.add(socksSocket); }
                InputStream socksIn = socksSocket.getInputStream();
                OutputStream socksOut = socksSocket.getOutputStream();
                socksOut.write(new byte[]{0x05, 0x01, 0x00});
                socksOut.flush();
                byte[] hsResp = new byte[2];
                readFully(socksIn, hsResp);
                if (hsResp[0] != 0x05 || hsResp[1] != 0x00) throw new IOException("SOCKS5 auth failed: " + hsResp[1]);

                byte[] domainBytes = targetHost.getBytes(StandardCharsets.US_ASCII);
                ByteArrayOutputStream connReq = new ByteArrayOutputStream();
                connReq.write(0x05); connReq.write(0x01); connReq.write(0x00); connReq.write(0x03);
                connReq.write(domainBytes.length); connReq.write(domainBytes);
                connReq.write((targetPort >> 8) & 0xFF); connReq.write(targetPort & 0xFF);
                socksOut.write(connReq.toByteArray()); socksOut.flush();
                byte[] connResp = new byte[4]; readFully(socksIn, connResp);
                if (connResp[0] != 0x05 || connResp[1] != 0x00) throw new IOException("SOCKS5 connect to " + targetHost + ":" + targetPort + " failed: code=" + connResp[1]);
                int atyp = connResp[3] & 0xFF;
                if (atyp == 0x01) skipBytes(socksIn, 4);
                else if (atyp == 0x03) { int dlen = socksIn.read(); if (dlen > 0) skipBytes(socksIn, dlen); }
                else if (atyp == 0x04) skipBytes(socksIn, 16);
                skipBytes(socksIn, 2);
                clientOut.write("HTTP/1.1 200 Connection Established\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
                clientOut.flush();
                final Socket sClient = clientSocket, sSocks = socksSocket;
                mBridgeThreadPool.execute(() -> pipeStream(clientIn, socksOut, sClient, sSocks));
                pipeStream(socksIn, clientOut, sSocks, sClient);
            } catch (Exception e) {
                Logger.logDebug(LOG_TAG, "Bridge connection error: " + e.getMessage());
                closeQuietly(clientSocket); closeQuietly(socksSocket);
            }
        }

        private static void pipeStream(InputStream in, OutputStream out, Socket s1, Socket s2) {
            byte[] buf = new byte[8192]; int n;
            try { while ((n = in.read(buf)) != -1) { out.write(buf, 0, n); out.flush(); } }
            catch (Exception ignored) {}
            finally { closeQuietly(s1); closeQuietly(s2); }
        }

        private static void readFully(InputStream in, byte[] buf) throws IOException {
            int off = 0;
            while (off < buf.length) { int read = in.read(buf, off, buf.length - off); if (read < 0) throw new EOFException("Premature EOF"); off += read; }
        }

        private static void skipBytes(InputStream in, int count) throws IOException {
            for (int i = 0; i < count; i++) if (in.read() == -1) throw new EOFException("Premature EOF");
        }

        private static void closeQuietly(Socket s) { if (s != null) try { s.close(); } catch (Exception ignored) {} }

        public synchronized void stop() {
            mIsRunning = false;
            if (mServerSocket != null) { try { mServerSocket.close(); } catch (Exception ignored) {} mServerSocket = null; }
            synchronized (mActiveSockets) { for (Socket s : mActiveSockets) closeQuietly(s); mActiveSockets.clear(); }
            mBridgeThreadPool.shutdownNow();
        }
    }

    private static volatile OpenAiTunnelManager sInstance;
    private Process mProcess;
    private Context mAppContext;
    private volatile TunnelState mState = TunnelState.STOPPED;
    private volatile String mLastError = "";
    private final Deque<String> mLogBuffer = new ArrayDeque<>(150);
    private final ExecutorService mThreadPool = Executors.newCachedThreadPool();
    private LocalHttpToSocks5Bridge mHttpBridge;

    public interface StateListener { void onStateChanged(TunnelState state, String lastError); }
    private StateListener mListener;
    private OpenAiTunnelManager() {}

    public static OpenAiTunnelManager getInstance() {
        if (sInstance == null) synchronized (OpenAiTunnelManager.class) { if (sInstance == null) sInstance = new OpenAiTunnelManager(); }
        return sInstance;
    }

    private SharedPreferences getPrefs(Context context) { return context.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE); }
    public boolean isEnabled(Context context) { return getPrefs(context).getBoolean(PREF_KEY_ENABLED, false); }
    public void setEnabled(Context context, boolean enabled) { getPrefs(context).edit().putBoolean(PREF_KEY_ENABLED, enabled).apply(); }
    public String getTunnelId(Context context) { return getPrefs(context).getString(PREF_KEY_TUNNEL_ID, ""); }
    public void setTunnelId(Context context, String tunnelId) { getPrefs(context).edit().putString(PREF_KEY_TUNNEL_ID, tunnelId != null ? tunnelId.trim() : "").apply(); }
    public String getApiKey(Context context) { return getPrefs(context).getString(PREF_KEY_API_KEY, ""); }
    public void setApiKey(Context context, String apiKey) { getPrefs(context).edit().putString(PREF_KEY_API_KEY, apiKey != null ? apiKey.trim() : "").apply(); }
    public String getProxy(Context context) { return getPrefs(context).getString(PREF_KEY_PROXY, ""); }
    public void setProxy(Context context, String proxy) { getPrefs(context).edit().putString(PREF_KEY_PROXY, proxy != null ? proxy.trim() : "").apply(); }
    public int getTargetPort(Context context) { return getPrefs(context).getInt(PREF_KEY_TARGET_PORT, 0); }
    public void setTargetPort(Context context, int port) { getPrefs(context).edit().putInt(PREF_KEY_TARGET_PORT, port).apply(); }
    public TunnelState getState() { return mState; }
    public String getLastError() { return mLastError; }
    public void setStateListener(StateListener listener) { this.mListener = listener; }

    private void updateState(TunnelState newState, String errorMsg) {
        this.mState = newState;
        if (errorMsg != null) this.mLastError = errorMsg;
        if (mListener != null) new Handler(Looper.getMainLooper()).post(() -> { if (mListener != null) mListener.onStateChanged(newState, mLastError); });
    }

    public synchronized boolean isRunning() {
        if (mProcess != null) {
            try { mProcess.exitValue(); return false; } catch (IllegalThreadStateException e) { return true; }
        }
        return false;
    }

    public synchronized boolean ensureBinariesInstalled(Context context) {
        File binDir = new File(TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH);
        if (!binDir.exists()) binDir.mkdirs();
        File tunnelClient = new File(binDir, "tunnel-client");
        File cloudflared = new File(binDir, "cloudflared");
        boolean ok = true;
        if (!tunnelClient.exists() || tunnelClient.length() == 0) ok = extractAssetGzip(context, "bin/tunnel-client.gz", tunnelClient);
        if (ok && (!cloudflared.exists() || cloudflared.length() == 0)) extractAssetGzip(context, "bin/cloudflared.gz", cloudflared);
        if (tunnelClient.exists()) tunnelClient.setExecutable(true, false);
        if (cloudflared.exists()) cloudflared.setExecutable(true, false);
        return ok && tunnelClient.exists();
    }

    private boolean extractAssetGzip(Context context, String assetPath, File destFile) {
        try (InputStream is = context.getAssets().open(assetPath); GZIPInputStream gzis = new GZIPInputStream(is); FileOutputStream fos = new FileOutputStream(destFile)) {
            byte[] buffer = new byte[8192]; int len;
            while ((len = gzis.read(buffer)) > 0) fos.write(buffer, 0, len);
            fos.flush(); destFile.setExecutable(true, false); return true;
        } catch (Exception e) { Logger.logError(LOG_TAG, "Failed to extract asset " + assetPath + ": " + e.getMessage()); return false; }
    }

    public static boolean isPortListening(String host, int port) { return isPortListening(host, port, 80); }
    public static boolean isPortListening(String host, int port, int timeoutMs) {
        try (Socket socket = new Socket()) { socket.connect(new InetSocketAddress(host, port), timeoutMs); return true; }
        catch (Exception e) { return false; }
    }

    /** 常见本地 HTTP 代理端口池。仅作为没有显式代理时的最后兜底；避免误选其他应用的监听端口。 */
    private static final int[] COMMON_HTTP_PORTS = {
        2080,  // sing-box HTTP/Mixed（优先）
        7890,  // Clash / Mihomo HTTP/Mixed
        10809, // v2rayNG / Xray HTTP
        8888, 8889, 1082, 8080, 8118, 1081, 8787, 3128, 7892, 7893
    };

    private static final int[] COMMON_SOCKS5_PORTS = {
        2081,  // sing-box SOCKS5（优先）
        10808, // v2rayNG / Xray SOCKS5
        7891, 1080, 9050, 1086, 1087
    };

    public static ProxyInfo probeProxyPort(String host, int port) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), 150); socket.setSoTimeout(150);
            OutputStream os = socket.getOutputStream(); InputStream is = socket.getInputStream();
            os.write("CONNECT 1.1.1.1:443 HTTP/1.1\r\nHost: 1.1.1.1:443\r\n\r\n".getBytes(StandardCharsets.US_ASCII)); os.flush();
            byte[] buf = new byte[128]; int n = is.read(buf);
            if (n >= 12) {
                String resp = new String(buf, 0, n, StandardCharsets.US_ASCII).toUpperCase();
                if (resp.contains(" 200 ") || resp.contains(" 407 ") || resp.contains("CONNECTION ESTABLISHED")) return new ProxyInfo("http", host, port, "本地 HTTP 代理");
            }
        } catch (Exception ignored) {}
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), 150); socket.setSoTimeout(150);
            OutputStream os = socket.getOutputStream(); InputStream is = socket.getInputStream();
            os.write(new byte[]{0x05, 0x01, 0x00}); os.flush(); byte[] buf = new byte[2]; int n = is.read(buf);
            if (n == 2 && buf[0] == 0x05 && buf[1] == 0x00) return new ProxyInfo("socks5", host, port, "本地 SOCKS5 代理");
        } catch (Exception ignored) {}
        return null;
    }

    public static ProxyInfo detectLocalProxy() {
        String sysHttpHost = System.getProperty("http.proxyHost");
        String sysHttpPort = System.getProperty("http.proxyPort");
        if (sysHttpHost != null && sysHttpPort != null) {
            try { int p = Integer.parseInt(sysHttpPort); ProxyInfo pi = probeProxyPort(sysHttpHost, p); if (pi != null) return pi; return new ProxyInfo("http", sysHttpHost, p, "系统配置 HTTP 代理"); }
            catch (Exception ignored) {}
        }
        for (int port : COMMON_HTTP_PORTS) {
            ProxyInfo info = probeProxyPort("127.0.0.1", port);
            if (info != null) {
                String name;
                if (port == 2080) name = "sing-box HTTP (2080)";
                else if (port == 7890) name = "Clash/Mihomo HTTP (7890)";
                else if (port == 10809) name = "v2rayNG HTTP (10809)";
                else name = info.name + " (" + port + ")";
                return new ProxyInfo(info.scheme, "127.0.0.1", port, name);
            }
        }
        for (int port : COMMON_SOCKS5_PORTS) {
            ProxyInfo info = probeProxyPort("127.0.0.1", port);
            if (info != null) {
                String name;
                if (port == 2081) name = "sing-box SOCKS5 (2081)";
                else if (port == 10808) name = "v2rayNG/Xray SOCKS5 (10808)";
                else if (port == 7891) name = "Clash SOCKS5 (7891)";
                else if (port == 1080) name = "Shadowsocks SOCKS5 (1080)";
                else name = info.name + " (" + port + ")";
                return new ProxyInfo(info.scheme, "127.0.0.1", port, name);
            }
        }
        return null;
    }

    /** 获取 Android 当前网络提供的全局 HTTP 代理，避免依赖 APK 是否被另一个 VPN/TUN 应用选中。 */
    public static ProxyInfo getAndroidDefaultProxy(Context context) {
        if (context == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) return null;
        try {
            ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return null;
            android.net.ProxyInfo proxy = cm.getDefaultProxy();
            if (proxy == null) return null;
            String host = proxy.getHost(); int port = proxy.getPort();
            if (host == null || host.isEmpty() || port <= 0) return null;
            return new ProxyInfo("http", host, port, "Android 系统 HTTP 代理");
        } catch (Exception e) { Logger.logDebug(LOG_TAG, "Android default proxy unavailable: " + e.getMessage()); return null; }
    }

    public static boolean isVpnActive(Context context) {
        if (context == null) return false;
        try {
            ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm != null) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    Network activeNet = cm.getActiveNetwork();
                    if (activeNet != null) { NetworkCapabilities caps = cm.getNetworkCapabilities(activeNet); return caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN); }
                } else { NetworkInfo vpnInfo = cm.getNetworkInfo(ConnectivityManager.TYPE_VPN); return vpnInfo != null && vpnInfo.isConnected(); }
            }
        } catch (Exception ignored) {}
        return false;
    }

    public static synchronized File ensureCaBundle(Context context) {
        File termuxCert = new File(TermuxConstants.TERMUX_PREFIX_DIR_PATH + "/etc/tls/cert.pem");
        if (termuxCert.exists() && termuxCert.length() > 10240) return termuxCert;
        File certFile = new File(context.getFilesDir(), "cacert.pem");
        if (certFile.exists() && certFile.length() > 10240) return certFile;
        try {
            TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()); tmf.init((KeyStore) null);
            StringBuilder pemBuilder = new StringBuilder();
            for (TrustManager tm : tmf.getTrustManagers()) if (tm instanceof X509TrustManager) {
                X509Certificate[] issuers = ((X509TrustManager) tm).getAcceptedIssuers();
                if (issuers != null) for (X509Certificate cert : issuers) {
                    pemBuilder.append("-----BEGIN CERTIFICATE-----\n"); String b64 = Base64.encodeToString(cert.getEncoded(), Base64.NO_WRAP);
                    for (int i = 0; i < b64.length(); i += 64) pemBuilder.append(b64, i, Math.min(i + 64, b64.length())).append("\n");
                    pemBuilder.append("-----END CERTIFICATE-----\n");
                }
            }
            if (pemBuilder.length() > 0) {
                try (FileOutputStream fos = new FileOutputStream(certFile)) { fos.write(pemBuilder.toString().getBytes(StandardCharsets.US_ASCII)); fos.flush(); }
                Logger.logInfo(LOG_TAG, "Exported " + certFile.length() + " bytes system CA certs to " + certFile.getAbsolutePath()); return certFile;
            }
        } catch (Exception e) { Logger.logError(LOG_TAG, "Failed to export Android system CA bundle: " + e.getMessage()); }
        return null;
    }

    public synchronized boolean isConnectingOrRunning() { return mState == TunnelState.STARTING || mState == TunnelState.CONNECTING || mState == TunnelState.CONNECTED || isRunning(); }

    public synchronized boolean startTunnel(Context context) {
        mAppContext = context.getApplicationContext();
        String tunnelId = getTunnelId(context), apiKey = getApiKey(context), proxy = getProxy(context);
        if (tunnelId == null || tunnelId.isEmpty()) { updateState(TunnelState.ERROR, "未配置 Tunnel ID"); return false; }
        if (apiKey == null || apiKey.isEmpty()) { updateState(TunnelState.ERROR, "未配置 Runtime API Key"); return false; }
        updateState(TunnelState.STARTING, null);
        mThreadPool.execute(() -> {
            try {
                if (mProcess != null) { try { mProcess.destroy(); if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) mProcess.destroyForcibly(); } catch (Exception ignored) {} mProcess = null; }
                if (mHttpBridge != null) { try { mHttpBridge.stop(); } catch (Exception ignored) {} mHttpBridge = null; }
                killStaleTunnelProcesses(context);
                if (!ensureBinariesInstalled(context)) { updateState(TunnelState.ERROR, "无法释放 tunnel-client 二进制可执行文件"); return; }

                File binFile = new File(TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH, "tunnel-client");
                int targetPort = getTargetPort(context); if (targetPort <= 0) targetPort = TermuxMcpManager.getInstance().getPort(context);
                List<String> cmd = new ArrayList<>(); cmd.add(binFile.getAbsolutePath()); cmd.add("run");
                cmd.add("--control-plane.tunnel-id"); cmd.add(tunnelId);
                cmd.add("--mcp.server-url"); cmd.add("http://127.0.0.1:" + targetPort + "/mcp");
                cmd.add("--health.listen-addr"); cmd.add("127.0.0.1:0");
                File pidFile = new File(context.getFilesDir(), "tunnel-client.pid"); cmd.add("--pid.file"); cmd.add(pidFile.getAbsolutePath());
                File healthUrlFile = new File(TermuxConstants.TERMUX_HOME_DIR_PATH, "tunnel-health.url"); cmd.add("--health.url-file"); cmd.add(healthUrlFile.getAbsolutePath());
                String authToken = (targetPort == 3100) ? "bj1995112@." : TermuxMcpManager.getInstance().getToken(context);
                if (authToken != null && !authToken.trim().isEmpty()) {
                    cmd.add("--mcp.extra-headers"); cmd.add("Authorization: Bearer " + authToken.trim());
                    cmd.add("--mcp.discovery-extra-headers"); cmd.add("Authorization: Bearer " + authToken.trim());
                }
                File caBundle = ensureCaBundle(context);
                if (caBundle != null && caBundle.exists()) { cmd.add("--ca-bundle"); cmd.add(caBundle.getAbsolutePath()); }

                ProxyInfo effectiveProxy = null;
                if (proxy != null && !proxy.trim().isEmpty()) {
                    String p = proxy.trim();
                    if (p.startsWith("socks5://") || p.startsWith("socks://")) {
                        String clean = p.replace("socks5://", "").replace("socks://", ""); String[] parts = clean.split(":");
                        int prt = parts.length > 1 ? Integer.parseInt(parts[1]) : 10808; effectiveProxy = new ProxyInfo("socks5", parts[0], prt, "用户指定 SOCKS5");
                    } else if (p.startsWith("http://") || p.startsWith("https://")) {
                        String clean = p.replace("http://", "").replace("https://", ""); String[] parts = clean.split(":");
                        int prt = parts.length > 1 ? Integer.parseInt(parts[1]) : 7890; effectiveProxy = new ProxyInfo("http", parts[0], prt, "用户指定 HTTP");
                    } else if (p.contains(":")) {
                        String[] parts = p.split(":"); int prt = Integer.parseInt(parts[1]); ProxyInfo probed = probeProxyPort(parts[0], prt);
                        String scheme = probed != null ? probed.scheme : ((prt == 10808 || prt == 7891 || prt == 1080 || prt == 2081) ? "socks5" : "http");
                        effectiveProxy = new ProxyInfo(scheme, parts[0], prt, "用户指定代理");
                    }
                } else {
                    // 用户未指定代理：优先读取 Android 当前网络明确提供的系统代理。
                    // 只有系统代理不存在时才扫描常见本地端口，避免误选其他代理软件的端口。
                    effectiveProxy = getAndroidDefaultProxy(context);
                    if (effectiveProxy == null) {
                        try { effectiveProxy = detectLocalProxy(); }
                        catch (Exception e) { Logger.logError(LOG_TAG, "Proxy detection error: " + e.getMessage()); }
                    }
                }

                String httpProxyUrl = null;
                if (effectiveProxy != null) {
                    if ("http".equalsIgnoreCase(effectiveProxy.scheme)) httpProxyUrl = effectiveProxy.getUrl();
                    else if ("socks5".equalsIgnoreCase(effectiveProxy.scheme)) {
                        try { mHttpBridge = new LocalHttpToSocks5Bridge(effectiveProxy.host, effectiveProxy.port); int bridgePort = mHttpBridge.start(); httpProxyUrl = "http://127.0.0.1:" + bridgePort; }
                        catch (Exception bridgeErr) { Logger.logError(LOG_TAG, "Failed to start HTTP-to-SOCKS5 bridge: " + bridgeErr.getMessage()); }
                    }
                }
                if (httpProxyUrl != null) { cmd.add("--control-plane.http-proxy"); cmd.add(httpProxyUrl); }

                ProcessBuilder pb = new ProcessBuilder(cmd); pb.directory(new File(TermuxConstants.TERMUX_HOME_DIR_PATH));
                Map<String, String> env = pb.environment();
                env.put("CONTROL_PLANE_API_KEY", apiKey); env.put("OPENAI_API_KEY", apiKey);
                env.put("PREFIX", TermuxConstants.TERMUX_PREFIX_DIR_PATH); env.put("HOME", TermuxConstants.TERMUX_HOME_DIR_PATH);
                env.put("PATH", TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH + ":" + System.getenv("PATH")); env.put("TMPDIR", TermuxConstants.TERMUX_TMP_PREFIX_DIR_PATH);
                if (caBundle != null && caBundle.exists()) { env.put("SSL_CERT_FILE", caBundle.getAbsolutePath()); env.put("SSL_CERT_DIR", caBundle.getParentFile().getAbsolutePath()); env.put("CA_BUNDLE", caBundle.getAbsolutePath()); }
                if (httpProxyUrl != null) {
                    env.put("CONTROL_PLANE_HTTP_PROXY", httpProxyUrl); env.put("HTTP_PROXY", httpProxyUrl); env.put("HTTPS_PROXY", httpProxyUrl);
                    env.put("http_proxy", httpProxyUrl); env.put("https_proxy", httpProxyUrl); env.put("ALL_PROXY", httpProxyUrl); env.put("all_proxy", httpProxyUrl);
                }
                env.put("NO_PROXY", "127.0.0.1,localhost,::1"); env.put("no_proxy", "127.0.0.1,localhost,::1");
                mLogBuffer.clear();
                try { File lf = new File(TermuxConstants.TERMUX_HOME_DIR_PATH, "tunnel.log"); if (lf.exists() && lf.length() > 512 * 1024) lf.delete(); } catch (Exception ignored) {}
                appendLog("[Termux+] 🚀 正在启动 OpenAI 官方原生安全隧道..."); appendLog("[Termux+] 🎯 转发目标 MCP: http://127.0.0.1:" + targetPort + "/mcp");
                if (caBundle != null && caBundle.exists()) appendLog("[Termux+] 🔐 已挂载 CA 根证书: " + caBundle.getName() + " (" + (caBundle.length() / 1024) + " KB)");
                if (effectiveProxy != null) {
                    if ("socks5".equalsIgnoreCase(effectiveProxy.scheme)) {
                        appendLog("[Termux+] 🌐 发现 SOCKS5 代理: " + effectiveProxy.toString());
                        if (mHttpBridge != null) appendLog("[Termux+] 🔄 已自动启动本地 HTTP-to-SOCKS5 域名透传桥接 (127.0.0.1:" + mHttpBridge.getPort() + ")");
                    } else appendLog("[Termux+] 🌐 使用 HTTP 代理: " + effectiveProxy.toString());
                } else {
                    if (isVpnActive(context)) {
                        appendLog("[Termux+] ℹ️ 检测到系统 VPN/TUN，但没有可直接使用的 HTTP 代理入口。");
                        appendLog("[Termux+] 💡 tunnel-client 将不再依赖 APK 是否被代理软件选中；若代理仅提供 TUN、没有本地 HTTP/Mixed 入站，则无法从应用内部直接指定该代理。");
                    } else appendLog("[Termux+] 🌐 未检测到本地代理端口，尝试直接网络连接");
                }
                mProcess = pb.start(); updateState(TunnelState.CONNECTING, null); startLogReader(mProcess.getInputStream()); startLogReader(mProcess.getErrorStream());
            } catch (Exception e) { updateState(TunnelState.ERROR, "启动失败: " + e.getMessage()); Logger.logError(LOG_TAG, "Failed to start tunnel-client: " + e.getMessage()); }
        });
        return true;
    }

    private void startLogReader(InputStream is) {
        mThreadPool.execute(() -> { try (BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) { String line; while ((line = reader.readLine()) != null) { appendLog(line); inspectLogLine(line); } } catch (Exception ignored) {} });
    }

    private synchronized void appendLog(String line) {
        if (line == null) return;
        if (mLogBuffer.size() >= 150) mLogBuffer.pollFirst();
        mLogBuffer.offerLast(line);
        try { File logFile = new File(TermuxConstants.TERMUX_HOME_DIR_PATH, "tunnel.log"); try (FileOutputStream fos = new FileOutputStream(logFile, true)) { fos.write((line + "\n").getBytes(StandardCharsets.UTF_8)); } } catch (Exception ignored) {}
    }

    private void inspectLogLine(String line) {
        String lower = line.toLowerCase();
        if (lower.contains("starting control-plane poller") || lower.contains("registered tunnel") || lower.contains("listening for requests") || lower.contains("tunnel ready") || lower.contains("serving") || (lower.contains("poller") && !lower.contains("failed") && !lower.contains("backing off") && !lower.contains("warn"))) {
            if (mState != TunnelState.CONNECTED) updateState(TunnelState.CONNECTED, null);
        } else if (lower.contains("failed") || lower.contains("error") || lower.contains("fatal") || lower.contains("backing off")) {
            mLastError = line;
            if (lower.contains("certificate signed by unknown authority") || lower.contains("unsupported protocol") || lower.contains("main channel is required") || lower.contains("backing off") || lower.contains("failed to dial") || lower.contains("unauthorized") || lower.contains("invalid api key") || lower.contains("context deadline exceeded") || (lower.contains("lookup") && lower.contains("connection refused"))) updateState(TunnelState.ERROR, line);
        }
    }

    public synchronized String getRecentLogs() {
        if (mLogBuffer.isEmpty()) return "暂无隧道运行日志";
        StringBuilder sb = new StringBuilder(); for (String log : mLogBuffer) sb.append(log).append("\n"); return sb.toString();
    }

    public synchronized void clearLogs() {
        mLogBuffer.clear();
        try { File logFile = new File(TermuxConstants.TERMUX_HOME_DIR_PATH, "tunnel.log"); if (logFile.exists()) try (FileOutputStream fos = new FileOutputStream(logFile)) {} } catch (Exception ignored) {}
    }

    public synchronized void stopTunnel() { stopTunnel(mAppContext); }

    public synchronized void stopTunnel(Context context) {
        if (context != null) mAppContext = context.getApplicationContext();
        updateState(TunnelState.STOPPED, null);
        if (mHttpBridge != null) { try { mHttpBridge.stop(); } catch (Exception ignored) {} mHttpBridge = null; }
        final Process procToKill = mProcess; mProcess = null; final Context targetContext = context != null ? context : mAppContext;
        mThreadPool.execute(() -> { if (procToKill != null) { try { procToKill.destroy(); if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) procToKill.destroyForcibly(); } catch (Exception ignored) {} } killStaleTunnelProcesses(targetContext); });
    }

    private void killStaleTunnelProcesses(Context context) {
        if (context != null) try {
            File pidFile = new File(context.getFilesDir(), "tunnel-client.pid");
            if (pidFile.exists()) {
                String pidStr = readFileToString(pidFile);
                if (pidStr != null && !pidStr.trim().isEmpty()) { int pid = Integer.parseInt(pidStr.trim()); android.os.Process.killProcess(pid); try { Runtime.getRuntime().exec(new String[]{"kill", "-9", String.valueOf(pid)}); } catch (Exception ignored) {} }
                pidFile.delete();
            }
        } catch (Exception ignored) {}
        try {
            File procDir = new File("/proc"); File[] files = procDir.listFiles();
            if (files != null) {
                int myPid = android.os.Process.myPid();
                for (File f : files) if (f.isDirectory() && f.getName().matches("\\d+")) try {
                    int pid = Integer.parseInt(f.getName()); if (pid == myPid) continue;
                    File cmdlineFile = new File(f, "cmdline");
                    if (cmdlineFile.exists() && cmdlineFile.canRead()) { String cmdline = readFileToString(cmdlineFile); if (cmdline != null && cmdline.contains("tunnel-client")) { android.os.Process.killProcess(pid); try { Runtime.getRuntime().exec(new String[]{"kill", "-9", String.valueOf(pid)}); } catch (Exception ignored) {} } }
                } catch (Exception ignored) {}
            }
        } catch (Exception ignored) {}
        try {
            String pkillPath = new File(TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH, "pkill").getAbsolutePath();
            if (new File(pkillPath).exists()) Runtime.getRuntime().exec(new String[]{pkillPath, "-9", "-f", "tunnel-client"});
            Runtime.getRuntime().exec(new String[]{"sh", "-c", "pkill -9 -f tunnel-client 2>/dev/null || killall -9 tunnel-client 2>/dev/null || true"});
        } catch (Exception ignored) {}
    }

    private String readFileToString(File file) {
        if (!file.exists() || !file.canRead()) return null;
        try (FileInputStream fis = new FileInputStream(file); ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
            byte[] buf = new byte[1024]; int n; while ((n = fis.read(buf)) != -1) bos.write(buf, 0, n); return bos.toString("UTF-8");
        } catch (Exception e) { return null; }
    }
}
