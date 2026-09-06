package com.termux.app.mcp;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import com.termux.shared.logger.Logger;
import com.termux.shared.termux.TermuxConstants;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.GZIPInputStream;

/**
 * OpenAI 官方原生 Secure MCP Tunnel (openai/tunnel-client) 独立管理器：
 * 1. 自动从 assets/bin 解压并赋予可执行权限 (+x)；
 * 2. 负责守护 tunnel-client 进程，出站直连 OpenAI 控制面；
 * 3. 收集最新运行日志与连通状态，支持前置 HTTP/HTTPS 代理配置。
 */
public class OpenAiTunnelManager {

    private static final String LOG_TAG = "OpenAiTunnelManager";
    private static final String PREFS_FILE = "termux_mcp_prefs";

    public static final String PREF_KEY_ENABLED = "mcp_openai_tunnel_enabled";
    public static final String PREF_KEY_TUNNEL_ID = "mcp_openai_tunnel_id";
    public static final String PREF_KEY_API_KEY = "mcp_openai_api_key";
    public static final String PREF_KEY_PROXY = "mcp_openai_proxy";

    public enum TunnelState {
        STOPPED("未运行"),
        STARTING("正在启动与释放..."),
        CONNECTING("正在连接 OpenAI 控制面..."),
        CONNECTED("已连接至 OpenAI 官方隧道 (ChatGPT 就绪)"),
        ERROR("连接异常");

        private final String desc;
        TunnelState(String desc) { this.desc = desc; }
        public String getDesc() { return desc; }
    }

    private static volatile OpenAiTunnelManager sInstance;

    private Process mProcess;
    private volatile TunnelState mState = TunnelState.STOPPED;
    private volatile String mLastError = "";
    private final Deque<String> mLogBuffer = new ArrayDeque<>(100);
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
     * 启动 OpenAI 官方 Secure Tunnel 进程
     */
    public synchronized boolean startTunnel(Context context) {
        if (isRunning()) {
            stopTunnel();
        }

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

        updateState(TunnelState.STARTING, null);

        if (!ensureBinariesInstalled(context)) {
            updateState(TunnelState.ERROR, "无法释放 tunnel-client 二进制可执行文件");
            return false;
        }

        File binFile = new File(TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH, "tunnel-client");
        int mcpPort = TermuxMcpManager.getInstance().getPort(context);

        List<String> cmd = new ArrayList<>();
        cmd.add(binFile.getAbsolutePath());
        cmd.add("run");
        cmd.add("--control-plane.tunnel-id");
        cmd.add(tunnelId);
        cmd.add("--mcp.server-url");
        cmd.add("http://127.0.0.1:" + mcpPort + "/mcp");
        cmd.add("--health.listen-addr");
        cmd.add("127.0.0.1:0"); // 自动分配随机空闲健康端口

        if (proxy != null && !proxy.isEmpty()) {
            cmd.add("--http-proxy");
            cmd.add(proxy);
        }

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(new File(TermuxConstants.TERMUX_HOME_DIR_PATH));

        Map<String, String> env = pb.environment();
        env.put("CONTROL_PLANE_API_KEY", apiKey);
        env.put("PREFIX", TermuxConstants.TERMUX_PREFIX_DIR_PATH);
        env.put("HOME", TermuxConstants.TERMUX_HOME_DIR_PATH);
        env.put("PATH", TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH + ":" + System.getenv("PATH"));
        env.put("TMPDIR", TermuxConstants.TERMUX_TMP_PREFIX_DIR_PATH);

        if (proxy != null && !proxy.isEmpty()) {
            env.put("HTTPS_PROXY", proxy);
            env.put("HTTP_PROXY", proxy);
            env.put("https_proxy", proxy);
            env.put("http_proxy", proxy);
        }

        try {
            mProcess = pb.start();
            updateState(TunnelState.CONNECTING, null);
            startLogReader(mProcess.getInputStream());
            startLogReader(mProcess.getErrorStream());
            return true;
        } catch (Exception e) {
            updateState(TunnelState.ERROR, "启动进程失败: " + e.getMessage());
            Logger.logError(LOG_TAG, "Failed to start tunnel-client: " + e.getMessage());
            return false;
        }
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
        if (mLogBuffer.size() >= 100) {
            mLogBuffer.pollFirst();
        }
        mLogBuffer.offerLast(line);
    }

    private void inspectLogLine(String line) {
        String lower = line.toLowerCase();
        if (lower.contains("ready") || lower.contains("serving") || lower.contains("registered tunnel") || lower.contains("connected")) {
            if (mState != TunnelState.CONNECTED) {
                updateState(TunnelState.CONNECTED, null);
            }
        } else if (lower.contains("failed") || lower.contains("error") || lower.contains("fatal")) {
            if (!lower.contains("retry")) {
                mLastError = line;
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
        if (mProcess != null) {
            try {
                mProcess.destroy();
            } catch (Exception ignored) {}
            mProcess = null;
        }
        updateState(TunnelState.STOPPED, null);
    }
}
