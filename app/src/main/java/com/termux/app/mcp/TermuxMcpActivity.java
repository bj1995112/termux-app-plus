package com.termux.app.mcp;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.switchmaterial.SwitchMaterial;

/**
 * AI 远程协同与 MCP 服务设置界面：
 * 提供服务启停开关、端口自定义修改、安全密钥一键复制/重置、长任务超时、CPU唤醒保活、OAuth 2.1 协同配置及客户端一键导出。
 */
public class TermuxMcpActivity extends AppCompatActivity {

    private SwitchMaterial mSwitchService;
    private TextView mTvStatus;
    private TextView mTvAddress;
    private SwitchMaterial mSwitchAutoStart;
    private SwitchMaterial mSwitchWakeLock;

    private TextView mTvPort;
    private TextView mTvToken;
    private TextView mTvTimeout;
    private TextView mTvPublicHost;

    private TextView mTvOAuthClientId;
    private TextView mTvOAuthClientSecret;
    private TextView mTvOAuthUrls;

    // 独立工具开关
    private SwitchMaterial mSwitchToolExec;
    private SwitchMaterial mSwitchToolFile;
    private SwitchMaterial mSwitchToolSys;
    private SwitchMaterial mSwitchToolClip;
    private SwitchMaterial mSwitchToolTorch;
    private SwitchMaterial mSwitchToolTts;
    private SwitchMaterial mSwitchToolFeedback;
    private SwitchMaterial mSwitchToolUrl;
    private SwitchMaterial mSwitchToolDownload;
    private SwitchMaterial mSwitchToolPython;
    private SwitchMaterial mSwitchToolGit;
    private SwitchMaterial mSwitchToolPm2;

    // OpenAI 官方原生安全隧道
    private SwitchMaterial mSwitchOpenAiTunnel;
    private TextView mTvOpenAiStatus;
    private TextView mTvOpenAiTunnelId;
    private TextView mTvOpenAiApiKey;
    private TextView mTvOpenAiTargetPort;
    private TextView mTvOpenAiProxy;

    private int mTextColorPrimary;
    private int mTextColorSecondary;
    private boolean mIsUpdatingUi = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        float density = getResources().getDisplayMetrics().density;

        // 动态高可靠解析系统主次文字颜色，保证在任何日间/深色模式下均 100% 清晰可见
        TypedValue tvPrimary = new TypedValue();
        getTheme().resolveAttribute(android.R.attr.textColorPrimary, tvPrimary, true);
        if (tvPrimary.resourceId != 0) {
            mTextColorPrimary = ContextCompat.getColor(this, tvPrimary.resourceId);
        } else {
            mTextColorPrimary = tvPrimary.data != 0 ? tvPrimary.data : 0xFF212121;
        }

        TypedValue tvSecondary = new TypedValue();
        getTheme().resolveAttribute(android.R.attr.textColorSecondary, tvSecondary, true);
        if (tvSecondary.resourceId != 0) {
            mTextColorSecondary = ContextCompat.getColor(this, tvSecondary.resourceId);
        } else {
            mTextColorSecondary = tvSecondary.data != 0 ? tvSecondary.data : 0xFF757575;
        }

        // 根布局
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setLayoutParams(new ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        ));

        // 1. 顶部标题栏 Toolbar
        MaterialToolbar toolbar = new MaterialToolbar(this);
        toolbar.setTitle("AI 远程协同与 MCP 服务");
        toolbar.setTitleTextColor(mTextColorPrimary);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setDisplayShowHomeEnabled(true);
        }
        toolbar.setNavigationOnClickListener(v -> finish());
        root.addView(toolbar);

        // 2. 滚动内容区域
        ScrollView scrollView = new ScrollView(this);
        scrollView.setLayoutParams(new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        ));
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding((int) (16 * density), (int) (12 * density), (int) (16 * density), (int) (24 * density));
        scrollView.addView(content);
        root.addView(scrollView);

        setContentView(root);

        // 提示说明文案
        TextView tvIntro = new TextView(this);
        tvIntro.setText("🚀 基于 2026 最新官方 Model Context Protocol 规范，原生内置零依赖引擎。支持 Streamable HTTP、经典 SSE、REST API 与 OAuth 2.1 (PKCE S256) 协议，允许电脑端 Cursor、Claude Desktop 及云端 ChatGPT 安全操控手机终端。");
        tvIntro.setTextSize(13);
        tvIntro.setTextColor(mTextColorSecondary);
        tvIntro.setLineSpacing(0, 1.2f);
        tvIntro.setPadding(0, 0, 0, (int) (14 * density));
        content.addView(tvIntro);

        // ────────────────────────────
        // 卡片 1：核心服务状态与开关
        // ────────────────────────────
        MaterialCardView cardStatus = createCard(density);
        LinearLayout statusLayout = createCardContent(density);

        TextView tvTitle1 = createCardTitle("⚡ 服务运行状态与保活", density);
        statusLayout.addView(tvTitle1);

        mSwitchService = new SwitchMaterial(this);
        statusLayout.addView(createSwitchRow(
            "开启 MCP 远程协同服务",
            "监听端口，对外提供标准 MCP 协议与 REST 端点",
            mSwitchService, density
        ));

        mTvStatus = new TextView(this);
        mTvStatus.setTextSize(13);
        mTvStatus.setPadding(0, (int) (4 * density), 0, (int) (4 * density));
        statusLayout.addView(mTvStatus);

        mTvAddress = new TextView(this);
        mTvAddress.setTextSize(12);
        mTvAddress.setTypeface(Typeface.MONOSPACE);
        mTvAddress.setTextColor(0xFF009688);
        mTvAddress.setPadding(0, 0, 0, (int) (8 * density));
        statusLayout.addView(mTvAddress);

        MaterialButton btnShowMcpLogs = new MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle);
        btnShowMcpLogs.setText("📜 查看 MCP 服务实时运行日志");
        btnShowMcpLogs.setTextSize(12);
        LinearLayout.LayoutParams btnMcpLogsParams = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        btnMcpLogsParams.setMargins(0, (int) (4 * density), 0, (int) (8 * density));
        btnShowMcpLogs.setLayoutParams(btnMcpLogsParams);
        btnShowMcpLogs.setOnClickListener(v -> showMcpLogsDialog());
        statusLayout.addView(btnShowMcpLogs);

        addDivider(statusLayout, density);

        mSwitchAutoStart = new SwitchMaterial(this);
        statusLayout.addView(createSwitchRow(
            "Termux 启动时自动开启",
            "应用启动或后台服务初始化时自动恢复监听",
            mSwitchAutoStart, density
        ));

        mSwitchWakeLock = new SwitchMaterial(this);
        statusLayout.addView(createSwitchRow(
            "保持 CPU 唤醒 (WakeLock)",
            "服务运行期间防止手机锁屏休眠，确保后台与长任务不中断",
            mSwitchWakeLock, density
        ));

        cardStatus.addView(statusLayout);
        content.addView(cardStatus);

        // ────────────────────────────
        // 卡片 2：端口、密钥与长任务超时
        // ────────────────────────────
        MaterialCardView cardConfig = createCard(density);
        LinearLayout configLayout = createCardContent(density);

        TextView tvTitle2 = createCardTitle("⚙️ 网络端口、安全密钥与执行超时", density);
        configLayout.addView(tvTitle2);

        // 端口行
        LinearLayout portRow = new LinearLayout(this);
        portRow.setOrientation(LinearLayout.HORIZONTAL);
        portRow.setGravity(Gravity.CENTER_VERTICAL);
        portRow.setPadding(0, (int) (6 * density), 0, (int) (6 * density));

        LinearLayout portTextCol = new LinearLayout(this);
        portTextCol.setOrientation(LinearLayout.VERTICAL);
        portTextCol.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f));

        mTvPort = new TextView(this);
        mTvPort.setTextSize(14);
        mTvPort.setTypeface(null, Typeface.BOLD);
        mTvPort.setTextColor(mTextColorPrimary);
        portTextCol.addView(mTvPort);

        TextView tvPortHint = new TextView(this);
        tvPortHint.setText("默认专属冷门端口，降低局域网冲突");
        tvPortHint.setTextSize(12);
        tvPortHint.setTextColor(mTextColorSecondary);
        portTextCol.addView(tvPortHint);
        portRow.addView(portTextCol);

        MaterialButton btnEditPort = new MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle);
        btnEditPort.setText("修改端口");
        btnEditPort.setTextSize(11);
        btnEditPort.setOnClickListener(v -> showEditPortDialog());
        portRow.addView(btnEditPort);
        configLayout.addView(portRow);

        addDivider(configLayout, density);

        // 密钥行
        LinearLayout tokenHeaderRow = new LinearLayout(this);
        tokenHeaderRow.setOrientation(LinearLayout.HORIZONTAL);
        tokenHeaderRow.setGravity(Gravity.CENTER_VERTICAL);
        tokenHeaderRow.setPadding(0, (int) (6 * density), 0, 0);

        LinearLayout tokenTextCol = new LinearLayout(this);
        tokenTextCol.setOrientation(LinearLayout.VERTICAL);
        tokenTextCol.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f));

        TextView tvTokenLabel = new TextView(this);
        tvTokenLabel.setText("安全密钥 (Token)");
        tvTokenLabel.setTextSize(14);
        tvTokenLabel.setTypeface(null, Typeface.BOLD);
        tvTokenLabel.setTextColor(mTextColorPrimary);
        tokenTextCol.addView(tvTokenLabel);

        TextView tvTokenHint = new TextView(this);
        tvTokenHint.setText("外部客户端 Bearer 身份认证（点击下方卡片即可直接复制）");
        tvTokenHint.setTextSize(12);
        tvTokenHint.setTextColor(mTextColorSecondary);
        tokenTextCol.addView(tvTokenHint);
        tokenHeaderRow.addView(tokenTextCol);
        configLayout.addView(tokenHeaderRow);

        // 密钥展示框（可直接点击复制）
        mTvToken = new TextView(this);
        mTvToken.setTextSize(13);
        mTvToken.setTypeface(Typeface.MONOSPACE);
        mTvToken.setTextColor(0xFF009688);
        mTvToken.setBackgroundColor(0x15009688);
        mTvToken.setPadding((int) (12 * density), (int) (8 * density), (int) (12 * density), (int) (8 * density));
        LinearLayout.LayoutParams lpTvToken = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lpTvToken.topMargin = (int) (6 * density);
        lpTvToken.bottomMargin = (int) (8 * density);
        mTvToken.setLayoutParams(lpTvToken);
        mTvToken.setOnClickListener(v -> copyTokenToClipboard());
        configLayout.addView(mTvToken);

        // 密钥操作按钮行（复制 + 重置）
        LinearLayout tokenBtnRow = new LinearLayout(this);
        tokenBtnRow.setOrientation(LinearLayout.HORIZONTAL);
        tokenBtnRow.setGravity(Gravity.END);

        MaterialButton btnCopyToken = new MaterialButton(this);
        btnCopyToken.setText("复制密钥");
        btnCopyToken.setTextSize(11);
        btnCopyToken.setOnClickListener(v -> copyTokenToClipboard());
        tokenBtnRow.addView(btnCopyToken);

        View spacerBtn = new View(this);
        spacerBtn.setLayoutParams(new LinearLayout.LayoutParams((int) (8 * density), 1));
        tokenBtnRow.addView(spacerBtn);

        MaterialButton btnGenToken = new MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle);
        btnGenToken.setText("重置密钥");
        btnGenToken.setTextSize(11);
        btnGenToken.setOnClickListener(v -> {
            String newToken = TermuxMcpManager.generateRandomToken();
            TermuxMcpManager.getInstance().setToken(this, newToken);
            refreshUI();
            if (TermuxMcpManager.getInstance().isServerRunning()) {
                TermuxMcpManager.getInstance().restartServer(this);
            }
            Toast.makeText(this, "已生成并应用新密钥", Toast.LENGTH_SHORT).show();
        });
        tokenBtnRow.addView(btnGenToken);
        configLayout.addView(tokenBtnRow);

        addDivider(configLayout, density);

        // 超时设置行
        LinearLayout timeoutRow = new LinearLayout(this);
        timeoutRow.setOrientation(LinearLayout.HORIZONTAL);
        timeoutRow.setGravity(Gravity.CENTER_VERTICAL);
        timeoutRow.setPadding(0, (int) (6 * density), 0, (int) (4 * density));

        LinearLayout timeoutTextCol = new LinearLayout(this);
        timeoutTextCol.setOrientation(LinearLayout.VERTICAL);
        timeoutTextCol.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f));

        TextView tvTimeoutTitle = new TextView(this);
        tvTimeoutTitle.setText("命令执行超时时长");
        tvTimeoutTitle.setTextSize(14);
        tvTimeoutTitle.setTypeface(null, Typeface.BOLD);
        tvTimeoutTitle.setTextColor(mTextColorPrimary);
        timeoutTextCol.addView(tvTimeoutTitle);

        mTvTimeout = new TextView(this);
        mTvTimeout.setTextSize(12);
        mTvTimeout.setTextColor(mTextColorSecondary);
        timeoutTextCol.addView(mTvTimeout);
        timeoutRow.addView(timeoutTextCol);

        MaterialButton btnEditTimeout = new MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle);
        btnEditTimeout.setText("设置超时");
        btnEditTimeout.setTextSize(11);
        btnEditTimeout.setOnClickListener(v -> showEditTimeoutDialog());
        timeoutRow.addView(btnEditTimeout);
        configLayout.addView(timeoutRow);

        addDivider(configLayout, density);

        // 公网穿透域名 (ngrok / 穿透代理)
        LinearLayout hostHeaderRow = new LinearLayout(this);
        hostHeaderRow.setOrientation(LinearLayout.HORIZONTAL);
        hostHeaderRow.setGravity(Gravity.CENTER_VERTICAL);
        hostHeaderRow.setPadding(0, (int) (6 * density), 0, 0);

        LinearLayout hostTextCol = new LinearLayout(this);
        hostTextCol.setOrientation(LinearLayout.VERTICAL);
        hostTextCol.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f));

        TextView tvHostLabel = new TextView(this);
        tvHostLabel.setText("公网穿透域名 (ngrok / 穿透代理)");
        tvHostLabel.setTextSize(14);
        tvHostLabel.setTypeface(null, Typeface.BOLD);
        tvHostLabel.setTextColor(mTextColorPrimary);
        hostTextCol.addView(tvHostLabel);

        TextView tvHostHint = new TextView(this);
        tvHostHint.setText("配置后自动在 ChatGPT OAuth 与客户端清单中生成 HTTPS 地址");
        tvHostHint.setTextSize(12);
        tvHostHint.setTextColor(mTextColorSecondary);
        hostTextCol.addView(tvHostHint);
        hostHeaderRow.addView(hostTextCol);
        configLayout.addView(hostHeaderRow);

        mTvPublicHost = new TextView(this);
        mTvPublicHost.setTextSize(13);
        mTvPublicHost.setTypeface(Typeface.MONOSPACE);
        mTvPublicHost.setTextColor(0xFF009688);
        mTvPublicHost.setBackgroundColor(0x15009688);
        mTvPublicHost.setPadding((int) (12 * density), (int) (8 * density), (int) (12 * density), (int) (8 * density));
        LinearLayout.LayoutParams lpHSerial = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lpHSerial.topMargin = (int) (6 * density);
        lpHSerial.bottomMargin = (int) (8 * density);
        mTvPublicHost.setLayoutParams(lpHSerial);
        mTvPublicHost.setOnClickListener(v -> showEditPublicHostDialog());
        configLayout.addView(mTvPublicHost);

        LinearLayout hostBtnRow = new LinearLayout(this);
        hostBtnRow.setOrientation(LinearLayout.HORIZONTAL);
        hostBtnRow.setGravity(Gravity.END);

        MaterialButton btnEditHost = new MaterialButton(this);
        btnEditHost.setText("设置公网域名");
        btnEditHost.setTextSize(11);
        btnEditHost.setOnClickListener(v -> showEditPublicHostDialog());
        hostBtnRow.addView(btnEditHost);

        View spacerHostBtn = new View(this);
        spacerHostBtn.setLayoutParams(new LinearLayout.LayoutParams((int) (8 * density), 1));
        hostBtnRow.addView(spacerHostBtn);

        MaterialButton btnClearHost = new MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle);
        btnClearHost.setText("清空");
        btnClearHost.setTextSize(11);
        btnClearHost.setOnClickListener(v -> {
            TermuxMcpManager.getInstance().setPublicHost(this, "");
            refreshUI();
            Toast.makeText(this, "已清空公网域名（恢复使用本地局域网 IP）", Toast.LENGTH_SHORT).show();
        });
        hostBtnRow.addView(btnClearHost);
        configLayout.addView(hostBtnRow);

        cardConfig.addView(configLayout);
        content.addView(cardConfig);

        // ────────────────────────────
        // 卡片 3：OAuth 2.1 协同认证
        // ────────────────────────────
        MaterialCardView cardOAuth = createCard(density);
        LinearLayout oauthLayout = createCardContent(density);

        TextView tvTitleOAuth = createCardTitle("🔐 OAuth 2.1 协同认证 (ChatGPT / Agent)", density);
        oauthLayout.addView(tvTitleOAuth);

        TextView tvOAuthDesc = new TextView(this);
        tvOAuthDesc.setText("已原生支持 RFC 7636 PKCE (S256) 与 RFC 8414 发现协议。ChatGPT Custom GPTs 或远程智能体可通过 OAuth 标准授权接入，无需在第三方明文硬编码密钥。");
        tvOAuthDesc.setTextSize(12);
        tvOAuthDesc.setTextColor(mTextColorSecondary);
        tvOAuthDesc.setPadding(0, 0, 0, (int) (8 * density));
        oauthLayout.addView(tvOAuthDesc);

        // ── 客户端 ID (Client ID) ──
        TextView tvClientIdLabel = new TextView(this);
        tvClientIdLabel.setText("客户端 ID (Client ID)");
        tvClientIdLabel.setTextSize(14);
        tvClientIdLabel.setTypeface(null, Typeface.BOLD);
        tvClientIdLabel.setTextColor(mTextColorPrimary);
        oauthLayout.addView(tvClientIdLabel);

        TextView tvClientIdHint = new TextView(this);
        tvClientIdHint.setText("ChatGPT OAuth 填报中的 Client ID（点击下方卡片可直接复制）");
        tvClientIdHint.setTextSize(12);
        tvClientIdHint.setTextColor(mTextColorSecondary);
        oauthLayout.addView(tvClientIdHint);

        mTvOAuthClientId = new TextView(this);
        mTvOAuthClientId.setTextSize(13);
        mTvOAuthClientId.setTypeface(Typeface.MONOSPACE);
        mTvOAuthClientId.setTextColor(0xFF009688);
        mTvOAuthClientId.setBackgroundColor(0x15009688);
        mTvOAuthClientId.setPadding((int) (12 * density), (int) (8 * density), (int) (12 * density), (int) (8 * density));
        LinearLayout.LayoutParams lpTvClientId = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lpTvClientId.topMargin = (int) (6 * density);
        lpTvClientId.bottomMargin = (int) (6 * density);
        mTvOAuthClientId.setLayoutParams(lpTvClientId);
        mTvOAuthClientId.setOnClickListener(v -> copyClientIdToClipboard());
        oauthLayout.addView(mTvOAuthClientId);

        LinearLayout clientIdBtnRow = new LinearLayout(this);
        clientIdBtnRow.setOrientation(LinearLayout.HORIZONTAL);
        clientIdBtnRow.setGravity(Gravity.END);

        MaterialButton btnCopyClientId = new MaterialButton(this);
        btnCopyClientId.setText("复制 Client ID");
        btnCopyClientId.setTextSize(11);
        btnCopyClientId.setOnClickListener(v -> copyClientIdToClipboard());
        clientIdBtnRow.addView(btnCopyClientId);
        oauthLayout.addView(clientIdBtnRow);

        addDivider(oauthLayout, density);

        // ── 客户端密码 (Client Secret) ──
        TextView tvClientSecretLabel = new TextView(this);
        tvClientSecretLabel.setText("客户端密码 (Client Secret)");
        tvClientSecretLabel.setTextSize(14);
        tvClientSecretLabel.setTypeface(null, Typeface.BOLD);
        tvClientSecretLabel.setTextColor(mTextColorPrimary);
        oauthLayout.addView(tvClientSecretLabel);

        TextView tvClientSecretHint = new TextView(this);
        tvClientSecretHint.setText("ChatGPT OAuth 填报中的 Client Secret（点击下方卡片可直接复制）");
        tvClientSecretHint.setTextSize(12);
        tvClientSecretHint.setTextColor(mTextColorSecondary);
        oauthLayout.addView(tvClientSecretHint);

        mTvOAuthClientSecret = new TextView(this);
        mTvOAuthClientSecret.setTextSize(13);
        mTvOAuthClientSecret.setTypeface(Typeface.MONOSPACE);
        mTvOAuthClientSecret.setTextColor(0xFF009688);
        mTvOAuthClientSecret.setBackgroundColor(0x15009688);
        mTvOAuthClientSecret.setPadding((int) (12 * density), (int) (8 * density), (int) (12 * density), (int) (8 * density));
        LinearLayout.LayoutParams lpTvSecret = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lpTvSecret.topMargin = (int) (6 * density);
        lpTvSecret.bottomMargin = (int) (6 * density);
        mTvOAuthClientSecret.setLayoutParams(lpTvSecret);
        mTvOAuthClientSecret.setOnClickListener(v -> copyClientSecretToClipboard());
        oauthLayout.addView(mTvOAuthClientSecret);

        LinearLayout secretBtnRow = new LinearLayout(this);
        secretBtnRow.setOrientation(LinearLayout.HORIZONTAL);
        secretBtnRow.setGravity(Gravity.END);

        MaterialButton btnCopySecret = new MaterialButton(this);
        btnCopySecret.setText("复制 Secret (密码)");
        btnCopySecret.setTextSize(11);
        btnCopySecret.setOnClickListener(v -> copyClientSecretToClipboard());
        secretBtnRow.addView(btnCopySecret);

        View spacerSecretBtn = new View(this);
        spacerSecretBtn.setLayoutParams(new LinearLayout.LayoutParams((int) (8 * density), 1));
        secretBtnRow.addView(spacerSecretBtn);

        MaterialButton btnResetSecret = new MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle);
        btnResetSecret.setText("重置 Secret");
        btnResetSecret.setTextSize(11);
        btnResetSecret.setOnClickListener(v -> {
            TermuxMcpManager.getInstance().resetOAuthClientSecret(this);
            refreshUI();
            Toast.makeText(this, "已重置 OAuth Client Secret 密码", Toast.LENGTH_SHORT).show();
        });
        secretBtnRow.addView(btnResetSecret);
        oauthLayout.addView(secretBtnRow);

        addDivider(oauthLayout, density);

        mTvOAuthUrls = new TextView(this);
        mTvOAuthUrls.setTextSize(12);
        mTvOAuthUrls.setTypeface(Typeface.MONOSPACE);
        mTvOAuthUrls.setTextColor(0xFF009688);
        mTvOAuthUrls.setPadding(0, (int) (4 * density), 0, (int) (8 * density));
        oauthLayout.addView(mTvOAuthUrls);

        MaterialButton btnCopyOAuth = new MaterialButton(this);
        btnCopyOAuth.setText("复制 ChatGPT OAuth 完整填报清单");
        btnCopyOAuth.setTextSize(12);
        btnCopyOAuth.setOnClickListener(v -> {
            String snippet = TermuxMcpManager.getInstance().getChatGptOAuthSnippet(this, null);
            copyToClipboard("ChatGPT OAuth Config", snippet);
            Toast.makeText(this, "已复制 OAuth 配置清单，可直接在 ChatGPT Actions 中填报！", Toast.LENGTH_SHORT).show();
        });
        oauthLayout.addView(btnCopyOAuth);

        MaterialButton btnPreviewAuth = new MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle);
        btnPreviewAuth.setText("在手机浏览器中预览授权页面");
        btnPreviewAuth.setTextSize(11);
        btnPreviewAuth.setOnClickListener(v -> {
            int port = TermuxMcpManager.getInstance().getPort(this);
            String url = "http://127.0.0.1:" + port + "/oauth/authorize?client_id=" +
                TermuxMcpManager.getInstance().getOAuthClientId(this) +
                "&redirect_uri=https://example.com/callback&response_type=code";
            try {
                Intent browserIntent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
                startActivity(browserIntent);
            } catch (Exception e) {
                Toast.makeText(this, "无法打开浏览器: " + e.getMessage(), Toast.LENGTH_SHORT).show();
            }
        });
        oauthLayout.addView(btnPreviewAuth);

        cardOAuth.addView(oauthLayout);
        content.addView(cardOAuth);

        // ────────────────────────────
        // 卡片 4：OpenAI 官方原生安全隧道 (Secure MCP Tunnel)
        // ────────────────────────────
        MaterialCardView cardOpenAi = createCard(density);
        LinearLayout openAiLayout = createCardContent(density);

        TextView tvTitleOpenAi = createCardTitle("🌐 OpenAI 原生安全隧道 (免公网穿透 · 无限流量)", density);
        openAiLayout.addView(tvTitleOpenAi);

        TextView tvOpenAiDesc = new TextView(this);
        tvOpenAiDesc.setText("基于 OpenAI 官方开源的 tunnel-client 出站专线架构。由手机直接与 OpenAI 云端建立双向通道，无需公网 IP、无第三方流量配额限制，解决 ngrok 流量不足难题。");
        tvOpenAiDesc.setTextSize(12);
        tvOpenAiDesc.setTextColor(mTextColorSecondary);
        tvOpenAiDesc.setPadding(0, 0, 0, (int) (8 * density));
        openAiLayout.addView(tvOpenAiDesc);

        mSwitchOpenAiTunnel = new SwitchMaterial(this);
        openAiLayout.addView(createSwitchRow(
            "启用 OpenAI 官方安全隧道",
            "后台自动拉起 tunnel-client 保持出站长连接",
            mSwitchOpenAiTunnel, density
        ));

        mTvOpenAiStatus = new TextView(this);
        mTvOpenAiStatus.setTextSize(12);
        mTvOpenAiStatus.setPadding(0, (int) (2 * density), 0, (int) (6 * density));
        openAiLayout.addView(mTvOpenAiStatus);

        addDivider(openAiLayout, density);

        // Tunnel ID 行
        LinearLayout tidHeaderRow = new LinearLayout(this);
        tidHeaderRow.setOrientation(LinearLayout.HORIZONTAL);
        tidHeaderRow.setGravity(Gravity.CENTER_VERTICAL);
        tidHeaderRow.setPadding(0, (int) (4 * density), 0, 0);

        LinearLayout tidTextCol = new LinearLayout(this);
        tidTextCol.setOrientation(LinearLayout.VERTICAL);
        tidTextCol.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f));

        TextView tvTidLabel = new TextView(this);
        tvTidLabel.setText("Tunnel ID (控制面隧道 ID)");
        tvTidLabel.setTextSize(14);
        tvTidLabel.setTypeface(null, Typeface.BOLD);
        tvTidLabel.setTextColor(mTextColorPrimary);
        tidTextCol.addView(tvTidLabel);

        TextView tvTidHint = new TextView(this);
        tvTidHint.setText("在 platform.openai.com/settings/organization/tunnels 中获取");
        tvTidHint.setTextSize(12);
        tvTidHint.setTextColor(mTextColorSecondary);
        tidTextCol.addView(tvTidHint);
        tidHeaderRow.addView(tidTextCol);
        openAiLayout.addView(tidHeaderRow);

        mTvOpenAiTunnelId = new TextView(this);
        mTvOpenAiTunnelId.setTextSize(13);
        mTvOpenAiTunnelId.setTypeface(Typeface.MONOSPACE);
        mTvOpenAiTunnelId.setTextColor(0xFF009688);
        mTvOpenAiTunnelId.setBackgroundColor(0x15009688);
        mTvOpenAiTunnelId.setPadding((int) (12 * density), (int) (8 * density), (int) (12 * density), (int) (8 * density));
        LinearLayout.LayoutParams lpTid = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lpTid.topMargin = (int) (6 * density);
        lpTid.bottomMargin = (int) (6 * density);
        mTvOpenAiTunnelId.setLayoutParams(lpTid);
        mTvOpenAiTunnelId.setOnClickListener(v -> showEditOpenAiTunnelIdDialog());
        openAiLayout.addView(mTvOpenAiTunnelId);

        LinearLayout tidBtnRow = new LinearLayout(this);
        tidBtnRow.setOrientation(LinearLayout.HORIZONTAL);
        tidBtnRow.setGravity(Gravity.END);

        MaterialButton btnEditTid = new MaterialButton(this);
        btnEditTid.setText("配置 Tunnel ID");
        btnEditTid.setTextSize(11);
        btnEditTid.setOnClickListener(v -> showEditOpenAiTunnelIdDialog());
        tidBtnRow.addView(btnEditTid);
        openAiLayout.addView(tidBtnRow);

        addDivider(openAiLayout, density);

        // API Key 行
        LinearLayout keyHeaderRow = new LinearLayout(this);
        keyHeaderRow.setOrientation(LinearLayout.HORIZONTAL);
        keyHeaderRow.setGravity(Gravity.CENTER_VERTICAL);
        keyHeaderRow.setPadding(0, (int) (4 * density), 0, 0);

        LinearLayout keyTextCol = new LinearLayout(this);
        keyTextCol.setOrientation(LinearLayout.VERTICAL);
        keyTextCol.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f));

        TextView tvKeyLabel = new TextView(this);
        tvKeyLabel.setText("Runtime API Key (运行时凭证)");
        tvKeyLabel.setTextSize(14);
        tvKeyLabel.setTypeface(null, Typeface.BOLD);
        tvKeyLabel.setTextColor(mTextColorPrimary);
        keyTextCol.addView(tvKeyLabel);

        TextView tvKeyHint = new TextView(this);
        tvKeyHint.setText("在 platform.openai.com/settings/organization/api-keys 中创建");
        tvKeyHint.setTextSize(12);
        tvKeyHint.setTextColor(mTextColorSecondary);
        keyTextCol.addView(tvKeyHint);
        keyHeaderRow.addView(keyTextCol);
        openAiLayout.addView(keyHeaderRow);

        mTvOpenAiApiKey = new TextView(this);
        mTvOpenAiApiKey.setTextSize(13);
        mTvOpenAiApiKey.setTypeface(Typeface.MONOSPACE);
        mTvOpenAiApiKey.setTextColor(0xFF009688);
        mTvOpenAiApiKey.setBackgroundColor(0x15009688);
        mTvOpenAiApiKey.setPadding((int) (12 * density), (int) (8 * density), (int) (12 * density), (int) (8 * density));
        LinearLayout.LayoutParams lpKey = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lpKey.topMargin = (int) (6 * density);
        lpKey.bottomMargin = (int) (6 * density);
        mTvOpenAiApiKey.setLayoutParams(lpKey);
        mTvOpenAiApiKey.setOnClickListener(v -> showEditOpenAiApiKeyDialog());
        openAiLayout.addView(mTvOpenAiApiKey);

        LinearLayout keyBtnRow = new LinearLayout(this);
        keyBtnRow.setOrientation(LinearLayout.HORIZONTAL);
        keyBtnRow.setGravity(Gravity.END);

        MaterialButton btnEditKey = new MaterialButton(this);
        btnEditKey.setText("配置 API Key");
        btnEditKey.setTextSize(11);
        btnEditKey.setOnClickListener(v -> showEditOpenAiApiKeyDialog());
        keyBtnRow.addView(btnEditKey);
        openAiLayout.addView(keyBtnRow);

        addDivider(openAiLayout, density);

        // 转发目标端口行 (选填 · 默认 28488)
        LinearLayout targetPortHeaderRow = new LinearLayout(this);
        targetPortHeaderRow.setOrientation(LinearLayout.HORIZONTAL);
        targetPortHeaderRow.setGravity(Gravity.CENTER_VERTICAL);
        targetPortHeaderRow.setPadding(0, (int) (4 * density), 0, 0);

        LinearLayout targetPortTextCol = new LinearLayout(this);
        targetPortTextCol.setOrientation(LinearLayout.VERTICAL);
        targetPortTextCol.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f));

        TextView tvTargetPortLabel = new TextView(this);
        tvTargetPortLabel.setText("转发目标 MCP 端口 (默认 28488)");
        tvTargetPortLabel.setTextSize(14);
        tvTargetPortLabel.setTypeface(null, Typeface.BOLD);
        tvTargetPortLabel.setTextColor(mTextColorPrimary);
        targetPortTextCol.addView(tvTargetPortLabel);

        TextView tvTargetPortHint = new TextView(this);
        tvTargetPortHint.setText("默认转发给 Termux+ 内置服务 (28488)，亦可切换为本地其他服务 (如 3100)");
        tvTargetPortHint.setTextSize(12);
        tvTargetPortHint.setTextColor(mTextColorSecondary);
        targetPortTextCol.addView(tvTargetPortHint);
        targetPortHeaderRow.addView(targetPortTextCol);
        openAiLayout.addView(targetPortHeaderRow);

        mTvOpenAiTargetPort = new TextView(this);
        mTvOpenAiTargetPort.setTextSize(13);
        mTvOpenAiTargetPort.setTypeface(Typeface.MONOSPACE);
        mTvOpenAiTargetPort.setTextColor(0xFF009688);
        mTvOpenAiTargetPort.setBackgroundColor(0x15009688);
        mTvOpenAiTargetPort.setPadding((int) (12 * density), (int) (8 * density), (int) (12 * density), (int) (8 * density));
        LinearLayout.LayoutParams lpTargetPort = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lpTargetPort.topMargin = (int) (6 * density);
        lpTargetPort.bottomMargin = (int) (6 * density);
        mTvOpenAiTargetPort.setLayoutParams(lpTargetPort);
        mTvOpenAiTargetPort.setOnClickListener(v -> showEditOpenAiTargetPortDialog());
        openAiLayout.addView(mTvOpenAiTargetPort);

        LinearLayout targetPortBtnRow = new LinearLayout(this);
        targetPortBtnRow.setOrientation(LinearLayout.HORIZONTAL);
        targetPortBtnRow.setGravity(Gravity.END);

        MaterialButton btnEditTargetPort = new MaterialButton(this);
        btnEditTargetPort.setText("修改端口");
        btnEditTargetPort.setTextSize(11);
        btnEditTargetPort.setOnClickListener(v -> showEditOpenAiTargetPortDialog());
        targetPortBtnRow.addView(btnEditTargetPort);
        openAiLayout.addView(targetPortBtnRow);

        addDivider(openAiLayout, density);

        // 前置代理行 (选填)
        LinearLayout proxyHeaderRow = new LinearLayout(this);
        proxyHeaderRow.setOrientation(LinearLayout.HORIZONTAL);
        proxyHeaderRow.setGravity(Gravity.CENTER_VERTICAL);
        proxyHeaderRow.setPadding(0, (int) (4 * density), 0, 0);

        LinearLayout proxyTextCol = new LinearLayout(this);
        proxyTextCol.setOrientation(LinearLayout.VERTICAL);
        proxyTextCol.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f));

        TextView tvProxyLabel = new TextView(this);
        tvProxyLabel.setText("出站前置代理 (选填 · 科学上网环境)");
        tvProxyLabel.setTextSize(14);
        tvProxyLabel.setTypeface(null, Typeface.BOLD);
        tvProxyLabel.setTextColor(mTextColorPrimary);
        proxyTextCol.addView(tvProxyLabel);

        TextView tvProxyHint = new TextView(this);
        tvProxyHint.setText("已开启智能中继：推荐留空，自动自适应匹配 Clash/v2rayNG/sing-box 等任意代理及端口");
        tvProxyHint.setTextSize(12);
        tvProxyHint.setTextColor(mTextColorSecondary);
        proxyTextCol.addView(tvProxyHint);
        proxyHeaderRow.addView(proxyTextCol);
        openAiLayout.addView(proxyHeaderRow);

        mTvOpenAiProxy = new TextView(this);
        mTvOpenAiProxy.setTextSize(13);
        mTvOpenAiProxy.setTypeface(Typeface.MONOSPACE);
        mTvOpenAiProxy.setTextColor(0xFF009688);
        mTvOpenAiProxy.setBackgroundColor(0x15009688);
        mTvOpenAiProxy.setPadding((int) (12 * density), (int) (8 * density), (int) (12 * density), (int) (8 * density));
        LinearLayout.LayoutParams lpProxy = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lpProxy.topMargin = (int) (6 * density);
        lpProxy.bottomMargin = (int) (6 * density);
        mTvOpenAiProxy.setLayoutParams(lpProxy);
        mTvOpenAiProxy.setOnClickListener(v -> showEditOpenAiProxyDialog());
        openAiLayout.addView(mTvOpenAiProxy);

        LinearLayout proxyBtnRow = new LinearLayout(this);
        proxyBtnRow.setOrientation(LinearLayout.HORIZONTAL);
        proxyBtnRow.setGravity(Gravity.END);

        MaterialButton btnEditProxy = new MaterialButton(this);
        btnEditProxy.setText("指定代理");
        btnEditProxy.setTextSize(11);
        btnEditProxy.setOnClickListener(v -> showEditOpenAiProxyDialog());
        proxyBtnRow.addView(btnEditProxy);

        View spacerProxyBtn = new View(this);
        spacerProxyBtn.setLayoutParams(new LinearLayout.LayoutParams((int) (8 * density), 1));
        proxyBtnRow.addView(spacerProxyBtn);

        MaterialButton btnClearProxy = new MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle);
        btnClearProxy.setText("恢复自动检测");
        btnClearProxy.setTextSize(11);
        btnClearProxy.setOnClickListener(v -> {
            OpenAiTunnelManager.getInstance().setProxy(this, "");
            refreshUI();
            Toast.makeText(this, "已恢复【全自动智能代理模式】", Toast.LENGTH_SHORT).show();
        });
        proxyBtnRow.addView(btnClearProxy);
        openAiLayout.addView(proxyBtnRow);

        addDivider(openAiLayout, density);

        // 底部运维按钮（查看日志 + 配置指引）
        LinearLayout bottomOpsRow = new LinearLayout(this);
        bottomOpsRow.setOrientation(LinearLayout.HORIZONTAL);
        bottomOpsRow.setGravity(Gravity.CENTER_VERTICAL);
        bottomOpsRow.setPadding(0, (int) (4 * density), 0, 0);

        MaterialButton btnShowLogs = new MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle);
        btnShowLogs.setText("查看隧道实时日志");
        btnShowLogs.setTextSize(11);
        btnShowLogs.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f));
        btnShowLogs.setOnClickListener(v -> showTunnelLogsDialog());
        bottomOpsRow.addView(btnShowLogs);

        View spacerOps = new View(this);
        spacerOps.setLayoutParams(new LinearLayout.LayoutParams((int) (8 * density), 1));
        bottomOpsRow.addView(spacerOps);

        MaterialButton btnHelp = new MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle);
        btnHelp.setText("获取配置指引");
        btnHelp.setTextSize(11);
        btnHelp.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f));
        btnHelp.setOnClickListener(v -> showOpenAiTunnelHelpDialog());
        bottomOpsRow.addView(btnHelp);

        openAiLayout.addView(bottomOpsRow);

        cardOpenAi.addView(openAiLayout);
        content.addView(cardOpenAi);

        // ────────────────────────────
        // 卡片 5：MCP 工具能力与权限管理
        // ────────────────────────────
        MaterialCardView cardTools = createCard(density);
        LinearLayout toolsLayout = createCardContent(density);

        TextView tvTitleTools = createCardTitle("🛠️ MCP 工具能力与权限管理", density);
        toolsLayout.addView(tvTitleTools);

        TextView tvToolsDesc = new TextView(this);
        tvToolsDesc.setText("可按需单独开启或关闭 AI 可用的各项执行工具。关闭的工具将从 MCP 协议中隐藏，且服务端直接拒绝执行，实现严格的安全最小特权原则。");
        tvToolsDesc.setTextSize(12);
        tvToolsDesc.setTextColor(mTextColorSecondary);
        tvToolsDesc.setPadding(0, 0, 0, (int) (8 * density));
        toolsLayout.addView(tvToolsDesc);

        // 1. 命令执行
        mSwitchToolExec = new SwitchMaterial(this);
        toolsLayout.addView(createSwitchRow(
            "终端命令执行 (execute_command)",
            "允许 AI 在手机终端环境中运行任意 Shell 命令行与脚本",
            mSwitchToolExec, density
        ));
        addDivider(toolsLayout, density);

        // 2. 文件管理
        mSwitchToolFile = new SwitchMaterial(this);
        toolsLayout.addView(createSwitchRow(
            "文件管理与读写 (read/write/list)",
            "允许 AI 读取、写入修改手机文件与列举目录清单",
            mSwitchToolFile, density
        ));
        addDivider(toolsLayout, density);

        // 3. 系统状态
        mSwitchToolSys = new SwitchMaterial(this);
        toolsLayout.addView(createSwitchRow(
            "系统状态查询 (get_system_info)",
            "允许 AI 查询手机电量、内存占用、CPU型号与磁盘空间",
            mSwitchToolSys, density
        ));
        addDivider(toolsLayout, density);

        // 4. 剪贴板
        mSwitchToolClip = new SwitchMaterial(this);
        toolsLayout.addView(createSwitchRow(
            "系统剪贴板交互 (get/set_clipboard)",
            "允许 AI 读取手机当前复制的内容或向手机剪贴板写入文本",
            mSwitchToolClip, density
        ));
        addDivider(toolsLayout, density);

        // 5. 手电筒
        mSwitchToolTorch = new SwitchMaterial(this);
        toolsLayout.addView(createSwitchRow(
            "闪光灯/手电筒控制 (termux_torch)",
            "允许 AI 打开或关闭手机后置闪光灯",
            mSwitchToolTorch, density
        ));
        addDivider(toolsLayout, density);

        // 6. TTS 语音合成
        mSwitchToolTts = new SwitchMaterial(this);
        toolsLayout.addView(createSwitchRow(
            "扬声器语音朗读 (termux_tts_speak)",
            "允许 AI 通过手机自带扬声器实时朗读指定的文本字符串",
            mSwitchToolTts, density
        ));
        addDivider(toolsLayout, density);

        // 7. 屏幕气泡与通知
        mSwitchToolFeedback = new SwitchMaterial(this);
        toolsLayout.addView(createSwitchRow(
            "状态栏通知与屏幕气泡 (toast/notify/vibrate)",
            "允许 AI 弹出状态栏通知、手机屏幕浮动气泡(Toast)及振动",
            mSwitchToolFeedback, density
        ));
        addDivider(toolsLayout, density);

        // 8. 打开网页
        mSwitchToolUrl = new SwitchMaterial(this);
        toolsLayout.addView(createSwitchRow(
            "默认浏览器打开链接 (open_url)",
            "允许 AI 在手机系统默认浏览器中自动打开指定网页",
            mSwitchToolUrl, density
        ));
        addDivider(toolsLayout, density);

        // 9. 高速下载
        mSwitchToolDownload = new SwitchMaterial(this);
        toolsLayout.addView(createSwitchRow(
            "高速网络下载 (download_file)",
            "允许 AI 下载网络文件并直接保存至手机 Download 目录",
            mSwitchToolDownload, density
        ));
        addDivider(toolsLayout, density);

        // 10. Python 脚本与代码执行
        mSwitchToolPython = new SwitchMaterial(this);
        toolsLayout.addView(createSwitchRow(
            "Python 脚本与代码执行 (python_run)",
            "允许 AI 直接执行 Python 代码或运行指定 .py 文件并获取完整耗时回显",
            mSwitchToolPython, density
        ));
        addDivider(toolsLayout, density);

        // 11. Git 版本控制
        mSwitchToolGit = new SwitchMaterial(this);
        toolsLayout.addView(createSwitchRow(
            "Git 版本控制管理 (git_status/pull/clone/log/diff)",
            "允许 AI 检查代码仓库状态、拉取更新、克隆及查看修改历史",
            mSwitchToolGit, density
        ));
        addDivider(toolsLayout, density);

        // 12. PM2 进程守护
        mSwitchToolPm2 = new SwitchMaterial(this);
        toolsLayout.addView(createSwitchRow(
            "PM2 进程守护管理 (pm2_list/start/stop/restart/logs/save)",
            "允许 AI 管理与监控 Termux 原生或 Ubuntu 容器内的后台守护服务",
            mSwitchToolPm2, density
        ));

        cardTools.addView(toolsLayout);
        content.addView(cardTools);

        // ────────────────────────────
        // 卡片 5：客户端一键配置导出 (Cursor / Claude / ChatGPT)
        // ────────────────────────────
        MaterialCardView cardClients = createCard(density);
        LinearLayout clientsLayout = createCardContent(density);

        TextView tvTitle3 = createCardTitle("📋 外部客户端一键连接配置", density);
        clientsLayout.addView(tvTitle3);

        // 1. Cursor / Streamable HTTP
        TextView tvCursorDesc = new TextView(this);
        tvCursorDesc.setText("• Cursor 等支持最新 2026 MCP 规范的客户端 (Streamable HTTP 单端点)：");
        tvCursorDesc.setTextSize(12);
        tvCursorDesc.setTextColor(mTextColorSecondary);
        clientsLayout.addView(tvCursorDesc);

        MaterialButton btnCopyCursor = new MaterialButton(this);
        btnCopyCursor.setText("复制 Cursor MCP 配置代码");
        btnCopyCursor.setTextSize(12);
        btnCopyCursor.setOnClickListener(v -> {
            String json = TermuxMcpManager.getInstance().getCursorConfigJson(this);
            copyToClipboard("Cursor MCP Config", json);
            Toast.makeText(this, "已复制 Cursor 配置代码到剪贴板！", Toast.LENGTH_SHORT).show();
        });
        clientsLayout.addView(btnCopyCursor);

        // 2. Claude Desktop (mcp-remote)
        TextView tvClaudeDesc = new TextView(this);
        tvClaudeDesc.setText("• Claude Desktop 电脑端 (通过官方 mcp-remote 自动桥接)：");
        tvClaudeDesc.setTextSize(12);
        tvClaudeDesc.setTextColor(mTextColorSecondary);
        tvClaudeDesc.setPadding(0, (int) (10 * density), 0, 0);
        clientsLayout.addView(tvClaudeDesc);

        MaterialButton btnCopyClaude = new MaterialButton(this);
        btnCopyClaude.setText("复制 Claude Desktop 配置代码");
        btnCopyClaude.setTextSize(12);
        btnCopyClaude.setOnClickListener(v -> {
            String json = TermuxMcpManager.getInstance().getClaudeConfigJson(this);
            copyToClipboard("Claude Desktop MCP Config", json);
            Toast.makeText(this, "已复制 Claude Desktop 配置代码到剪贴板！", Toast.LENGTH_SHORT).show();
        });
        clientsLayout.addView(btnCopyClaude);

        // 3. ChatGPT 专属连接
        TextView tvChatGptDesc = new TextView(this);
        tvChatGptDesc.setText("• OpenAI ChatGPT 连接 (开发者模式 MCP 或 Custom GPTs Actions)：");
        tvChatGptDesc.setTextSize(12);
        tvChatGptDesc.setTextColor(mTextColorSecondary);
        tvChatGptDesc.setPadding(0, (int) (10 * density), 0, 0);
        clientsLayout.addView(tvChatGptDesc);

        MaterialButton btnCopyOpenApi = new MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle);
        btnCopyOpenApi.setText("复制 ChatGPT Custom Actions OpenAPI Schema");
        btnCopyOpenApi.setTextSize(11);
        btnCopyOpenApi.setOnClickListener(v -> {
            String schema = TermuxMcpManager.getInstance().getChatGptOpenApiSchema(this, null);
            copyToClipboard("ChatGPT OpenAPI Schema", schema);
            Toast.makeText(this, "已复制 OpenAPI Schema，可直接粘贴到 Custom GPTs！", Toast.LENGTH_SHORT).show();
        });
        clientsLayout.addView(btnCopyOpenApi);

        MaterialButton btnCopyNgrok = new MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle);
        btnCopyNgrok.setText("复制 ngrok 官方公网穿透命令");
        btnCopyNgrok.setTextSize(11);
        btnCopyNgrok.setOnClickListener(v -> {
            int port = TermuxMcpManager.getInstance().getPort(this);
            String cmd = "ngrok http " + port;
            copyToClipboard("ngrok Tunnel Command", cmd);
            Toast.makeText(this, "已复制：ngrok http " + port + "，在终端运行即可建立公网穿透！", Toast.LENGTH_LONG).show();
        });
        clientsLayout.addView(btnCopyNgrok);

        cardClients.addView(clientsLayout);
        content.addView(cardClients);

        // ────────────────────────────
        // 开关事件绑定
        // ────────────────────────────
        mSwitchService.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (mIsUpdatingUi) return;
            if (isChecked) {
                boolean ok = TermuxMcpManager.getInstance().startServer(this);
                if (!ok) {
                    mSwitchService.setChecked(false);
                    Toast.makeText(this, "服务启动失败，请检查端口是否被占用", Toast.LENGTH_SHORT).show();
                } else {
                    Toast.makeText(this, "Termux+ MCP 服务已在后台启动！", Toast.LENGTH_SHORT).show();
                }
            } else {
                TermuxMcpManager.getInstance().stopServer(this);
                Toast.makeText(this, "Termux+ MCP 服务已停止", Toast.LENGTH_SHORT).show();
            }
            refreshUI();
        });

        mSwitchAutoStart.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (mIsUpdatingUi) return;
            TermuxMcpManager.getInstance().setAutoStartEnabled(this, isChecked);
            Toast.makeText(this, isChecked ? "已启用开机与启动自启" : "已关闭启动自启", Toast.LENGTH_SHORT).show();
        });

        mSwitchWakeLock.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (mIsUpdatingUi) return;
            TermuxMcpManager.getInstance().setWakeLockEnabled(this, isChecked);
            Toast.makeText(this, isChecked ? "已启用 CPU 唤醒保活 (WakeLock)" : "已关闭 CPU 唤醒保活", Toast.LENGTH_SHORT).show();
        });

        // 绑定 12 大工具独立开关
        bindToolSwitch(mSwitchToolExec, TermuxMcpManager.PREF_KEY_TOOL_EXEC_CMD, "终端命令执行");
        bindToolSwitch(mSwitchToolFile, TermuxMcpManager.PREF_KEY_TOOL_FILE_OPS, "文件管理读写");
        bindToolSwitch(mSwitchToolSys, TermuxMcpManager.PREF_KEY_TOOL_SYSTEM_INFO, "系统状态查询");
        bindToolSwitch(mSwitchToolClip, TermuxMcpManager.PREF_KEY_TOOL_CLIPBOARD, "系统剪贴板交互");
        bindToolSwitch(mSwitchToolTorch, TermuxMcpManager.PREF_KEY_TOOL_TORCH, "闪光灯/手电筒");
        bindToolSwitch(mSwitchToolTts, TermuxMcpManager.PREF_KEY_TOOL_TTS, "扬声器语音朗读");
        bindToolSwitch(mSwitchToolFeedback, TermuxMcpManager.PREF_KEY_TOOL_FEEDBACK, "通知气泡与振动");
        bindToolSwitch(mSwitchToolUrl, TermuxMcpManager.PREF_KEY_TOOL_OPEN_URL, "浏览器打开网页");
        bindToolSwitch(mSwitchToolDownload, TermuxMcpManager.PREF_KEY_TOOL_DOWNLOAD, "高速网络下载");
        bindToolSwitch(mSwitchToolPython, TermuxMcpManager.PREF_KEY_TOOL_PYTHON, "Python 脚本执行");
        bindToolSwitch(mSwitchToolGit, TermuxMcpManager.PREF_KEY_TOOL_GIT, "Git 版本控制");
        bindToolSwitch(mSwitchToolPm2, TermuxMcpManager.PREF_KEY_TOOL_PM2, "PM2 进程守护");

        // 绑定 OpenAI 官方原生安全隧道开关
        mSwitchOpenAiTunnel.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (mIsUpdatingUi) return;
            OpenAiTunnelManager manager = OpenAiTunnelManager.getInstance();
            manager.setEnabled(this, isChecked);
            if (isChecked) {
                if (!TermuxMcpManager.getInstance().isServerRunning()) {
                    TermuxMcpManager.getInstance().startServer(this);
                }
                boolean ok = manager.startTunnel(this);
                if (!ok) {
                    mSwitchOpenAiTunnel.setChecked(false);
                    Toast.makeText(this, "启动失败: " + manager.getLastError(), Toast.LENGTH_LONG).show();
                } else {
                    Toast.makeText(this, "已启动 OpenAI 官方隧道，正在建立出站长连接...", Toast.LENGTH_SHORT).show();
                }
            } else {
                manager.stopTunnel(this);
                Toast.makeText(this, "OpenAI 官方隧道已停止", Toast.LENGTH_SHORT).show();
            }
            refreshUI();
        });

        OpenAiTunnelManager.getInstance().setStateListener((state, lastError) -> {
            refreshUI();
        });

        refreshUI();
    }

    private void bindToolSwitch(SwitchMaterial switchView, String prefKey, String name) {
        switchView.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (mIsUpdatingUi) return;
            TermuxMcpManager.getInstance().setToolEnabled(this, prefKey, isChecked);
            Toast.makeText(this, (isChecked ? "已启用工具: " : "已关闭工具: ") + name, Toast.LENGTH_SHORT).show();
        });
    }

    private void refreshUI() {
        mIsUpdatingUi = true;
        try {
            TermuxMcpManager manager = TermuxMcpManager.getInstance();
            boolean running = manager.isServerRunning();
            int port = manager.getPort(this);
            String token = manager.getToken(this);
            int timeoutSec = manager.getExecTimeoutSec(this);
            String localIp = TermuxMcpManager.getLocalIpAddress();
            String publicHost = manager.getPublicHost(this);

            mSwitchService.setChecked(running);
            mSwitchAutoStart.setChecked(manager.isAutoStartEnabled(this));
            mSwitchWakeLock.setChecked(manager.isWakeLockEnabled(this));

            // 公网穿透域名展示
            if (publicHost != null && !publicHost.isEmpty()) {
                mTvPublicHost.setText(publicHost);
            } else {
                mTvPublicHost.setText("未配置（当前使用局域网: http://" + localIp + ":" + port + "）");
            }

            String primaryEndpoint = (publicHost != null && !publicHost.isEmpty()) ? publicHost : ("http://" + localIp + ":" + port);

            if (running) {
                mTvStatus.setText("🟢 状态：运行中（双模与 OAuth 2.1 监听 0.0.0.0:" + port + "）");
                mTvStatus.setTextColor(0xFF2E7D32); // 绿色
                mTvAddress.setVisibility(View.VISIBLE);
                mTvAddress.setText("外部访问端点: " + primaryEndpoint + "/mcp\n" +
                                  "经典 SSE 端点: " + primaryEndpoint + "/sse\n" +
                                  "OAuth 发现: " + primaryEndpoint + "/.well-known/oauth-authorization-server");
            } else {
                mTvStatus.setText("⚪ 状态：未运行");
                mTvStatus.setTextColor(mTextColorSecondary);
                mTvAddress.setVisibility(View.GONE);
            }

            mTvPort.setText("当前监听端口：" + port);
            mTvToken.setText(token.isEmpty() ? "（未设置 · 免密模式）" : token);
            mTvTimeout.setText("当前超时上限：" + timeoutSec + " 秒（超时后自动终止进程）");

            mTvOAuthClientId.setText(manager.getOAuthClientId(this));
            mTvOAuthClientSecret.setText(manager.getOAuthClientSecret(this));
            mTvOAuthUrls.setText(
                "授权端点: " + primaryEndpoint + "/oauth/authorize\n" +
                "令牌端点: " + primaryEndpoint + "/oauth/token"
            );

            // 同步 12 个独立工具开关状态
            mSwitchToolExec.setChecked(manager.isToolEnabled(this, TermuxMcpManager.PREF_KEY_TOOL_EXEC_CMD));
            mSwitchToolFile.setChecked(manager.isToolEnabled(this, TermuxMcpManager.PREF_KEY_TOOL_FILE_OPS));
            mSwitchToolSys.setChecked(manager.isToolEnabled(this, TermuxMcpManager.PREF_KEY_TOOL_SYSTEM_INFO));
            mSwitchToolClip.setChecked(manager.isToolEnabled(this, TermuxMcpManager.PREF_KEY_TOOL_CLIPBOARD));
            mSwitchToolTorch.setChecked(manager.isToolEnabled(this, TermuxMcpManager.PREF_KEY_TOOL_TORCH));
            mSwitchToolTts.setChecked(manager.isToolEnabled(this, TermuxMcpManager.PREF_KEY_TOOL_TTS));
            mSwitchToolFeedback.setChecked(manager.isToolEnabled(this, TermuxMcpManager.PREF_KEY_TOOL_FEEDBACK));
            mSwitchToolUrl.setChecked(manager.isToolEnabled(this, TermuxMcpManager.PREF_KEY_TOOL_OPEN_URL));
            mSwitchToolDownload.setChecked(manager.isToolEnabled(this, TermuxMcpManager.PREF_KEY_TOOL_DOWNLOAD));
            mSwitchToolPython.setChecked(manager.isToolEnabled(this, TermuxMcpManager.PREF_KEY_TOOL_PYTHON));
            mSwitchToolGit.setChecked(manager.isToolEnabled(this, TermuxMcpManager.PREF_KEY_TOOL_GIT));
            mSwitchToolPm2.setChecked(manager.isToolEnabled(this, TermuxMcpManager.PREF_KEY_TOOL_PM2));

            // 同步 OpenAI 官方原生安全隧道状态
            OpenAiTunnelManager openAiMgr = OpenAiTunnelManager.getInstance();
            boolean openAiActive = openAiMgr.isConnectingOrRunning();
            mSwitchOpenAiTunnel.setChecked(openAiActive);

            OpenAiTunnelManager.TunnelState state = openAiMgr.getState();
            if (state == OpenAiTunnelManager.TunnelState.CONNECTED) {
                mTvOpenAiStatus.setText("🟢 状态：" + state.getDesc());
                mTvOpenAiStatus.setTextColor(0xFF2E7D32);
            } else if (state == OpenAiTunnelManager.TunnelState.STARTING || state == OpenAiTunnelManager.TunnelState.CONNECTING) {
                mTvOpenAiStatus.setText("🟡 状态：" + state.getDesc());
                mTvOpenAiStatus.setTextColor(0xFFF57F17);
            } else if (state == OpenAiTunnelManager.TunnelState.ERROR) {
                mTvOpenAiStatus.setText("🔴 异常：" + openAiMgr.getLastError());
                mTvOpenAiStatus.setTextColor(0xFFC62828);
            } else {
                mTvOpenAiStatus.setText("⚪ 状态：未运行");
                mTvOpenAiStatus.setTextColor(mTextColorSecondary);
            }

            String tid = openAiMgr.getTunnelId(this);
            mTvOpenAiTunnelId.setText(tid.isEmpty() ? "（未配置 · 点击下方按钮配置）" : tid);

            String apiKey = openAiMgr.getApiKey(this);
            if (apiKey.isEmpty()) {
                mTvOpenAiApiKey.setText("（未配置 · 点击下方按钮配置）");
            } else {
                mTvOpenAiApiKey.setText(apiKey.length() > 8 ? (apiKey.substring(0, 4) + "••••••••" + apiKey.substring(apiKey.length() - 4)) : "••••••••");
            }

            int targetPort = openAiMgr.getTargetPort(this);
            int defaultPort = TermuxMcpManager.getInstance().getPort(this);
            if (targetPort <= 0 || targetPort == defaultPort) {
                mTvOpenAiTargetPort.setText(defaultPort + " (默认 · Termux+ 内置原生服务)");
            } else {
                mTvOpenAiTargetPort.setText(targetPort + " (自定义外部服务)");
            }

            String proxy = openAiMgr.getProxy(this);
            mTvOpenAiProxy.setText(proxy.isEmpty() ? "（全自动智能嗅探模式 · 自动对接 v2rayNG / Clash，无需配置）" : proxy);
        } finally {
            mIsUpdatingUi = false;
        }
    }

    private void showEditPublicHostDialog() {
        TermuxMcpManager manager = TermuxMcpManager.getInstance();
        String currentHost = manager.getPublicHost(this);
        if (currentHost == null || currentHost.isEmpty()) {
            // 预填用户的有效 ngrok 域名，极大方便用户直接点击确定保存
            currentHost = "https://exalted-embellish-unsorted.ngrok-free.dev";
        }

        final EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        input.setText(currentHost);
        input.setSelectAllOnFocus(true);
        input.setHint("https://your-domain.ngrok-free.dev");

        new AlertDialog.Builder(this)
            .setTitle("配置公网穿透域名")
            .setMessage("请输入公网 HTTPS 完整域名（如 ngrok 分配的外网地址）：")
            .setView(input)
            .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                String host = input.getText().toString().trim();
                manager.setPublicHost(this, host);
                refreshUI();
                Toast.makeText(this, "公网域名已保存！已自动联动全部配置清单与端点。", Toast.LENGTH_SHORT).show();
            })
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    private void showEditOpenAiTunnelIdDialog() {
        OpenAiTunnelManager manager = OpenAiTunnelManager.getInstance();
        final EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        input.setText(manager.getTunnelId(this));
        input.setHint("如: tunnel_01j7... 或从平台复制的 ID");
        input.setSelectAllOnFocus(true);

        new AlertDialog.Builder(this)
            .setTitle("配置 OpenAI Tunnel ID")
            .setMessage("请输入在 OpenAI Platform (platform.openai.com) 创建的 Tunnel ID：")
            .setView(input)
            .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                String val = input.getText().toString().trim();
                manager.setTunnelId(this, val);
                refreshUI();
                if (manager.isRunning()) {
                    manager.stopTunnel(this);
                    manager.startTunnel(this);
                    Toast.makeText(this, "Tunnel ID 已保存，隧道已自动重启生效", Toast.LENGTH_SHORT).show();
                } else {
                    Toast.makeText(this, "已保存 Tunnel ID", Toast.LENGTH_SHORT).show();
                }
            })
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    private void showEditOpenAiApiKeyDialog() {
        OpenAiTunnelManager manager = OpenAiTunnelManager.getInstance();
        final EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        input.setText(manager.getApiKey(this));
        input.setHint("如: sec_... (需具备 Tunnels Read+Use 权限)");
        input.setSelectAllOnFocus(true);

        new AlertDialog.Builder(this)
            .setTitle("配置 OpenAI Runtime API Key")
            .setMessage("请输入用于该隧道的 OpenAI 运行时 API Key：")
            .setView(input)
            .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                String val = input.getText().toString().trim();
                manager.setApiKey(this, val);
                refreshUI();
                if (manager.isRunning()) {
                    manager.stopTunnel(this);
                    manager.startTunnel(this);
                    Toast.makeText(this, "API Key 已保存，隧道已自动重启生效", Toast.LENGTH_SHORT).show();
                } else {
                    Toast.makeText(this, "已保存 Runtime API Key", Toast.LENGTH_SHORT).show();
                }
            })
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    private void showEditOpenAiTargetPortDialog() {
        OpenAiTunnelManager manager = OpenAiTunnelManager.getInstance();
        final EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_NUMBER);
        int curr = manager.getTargetPort(this);
        input.setText(curr > 0 ? String.valueOf(curr) : "");
        input.setHint("留空默认 28488（或填 3100 等）");
        input.setSelectAllOnFocus(true);

        new AlertDialog.Builder(this)
            .setTitle("配置隧道转发目标端口")
            .setMessage("OpenAI 隧道默认将请求直接转发给 Termux+ 原生内置服务（28488 端口）。\n\n如果需要临时转发给外部脚本（如容器内 3100 端口），请填入对应端口；如需使用内置服务，请清空或直接点击【恢复默认 (28488)】：")
            .setView(input)
            .setPositiveButton("保存并生效", (dialog, which) -> {
                String val = input.getText().toString().trim();
                int port = 0;
                try {
                    if (!val.isEmpty()) port = Integer.parseInt(val);
                } catch (Exception ignored) {}
                applyTargetPortChange(manager, port);
            })
            .setNeutralButton("恢复默认 (28488)", (dialog, which) -> {
                applyTargetPortChange(manager, 0);
            })
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    private void applyTargetPortChange(OpenAiTunnelManager manager, int port) {
        manager.setTargetPort(this, port);
        refreshUI();
        String portDesc = (port > 0 && port != 28488) ? ("已将转发目标端口设置为: " + port + " (外部服务)") : "已恢复默认内置原生服务 (28488)";
        if (manager.isRunning()) {
            manager.stopTunnel(this);
            manager.startTunnel(this);
            Toast.makeText(this, portDesc + "，隧道已强力重启生效", Toast.LENGTH_SHORT).show();
        } else {
            Toast.makeText(this, portDesc, Toast.LENGTH_SHORT).show();
        }
    }

    private void showEditOpenAiProxyDialog() {
        OpenAiTunnelManager manager = OpenAiTunnelManager.getInstance();
        final EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        String curr = manager.getProxy(this);
        input.setText(curr);
        input.setHint("推荐留空自动检测（支持 7890, 10808, 2080 或自定义端口）");
        input.setSelectAllOnFocus(true);

        new AlertDialog.Builder(this)
            .setTitle("前置代理 (推荐留空自动检测与自愈)")
            .setMessage("手机已运行 Clash、v2rayNG、sing-box 或开启 VPN 时，保持留空即可，内置智能代理网关将自动自适应匹配当前工作的端口与协议，频繁切换代理软件无需手动修改。\n\n如需强制指定端口，可填入（如 10808、7890、socks5://127.0.0.1:10808 或 http://127.0.0.1:7890）：")
            .setView(input)
            .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                String val = input.getText().toString().trim();
                manager.setProxy(this, val);
                refreshUI();
                String modeDesc = val.isEmpty() ? "已切换为【全自动智能代理模式】" : "已保存指定前置代理";
                if (manager.isRunning()) {
                    manager.stopTunnel(this);
                    manager.startTunnel(this);
                    Toast.makeText(this, modeDesc + "，隧道已自动重启生效", Toast.LENGTH_SHORT).show();
                } else {
                    Toast.makeText(this, modeDesc, Toast.LENGTH_SHORT).show();
                }
            })
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    private void showMcpLogsDialog() {
        LinearLayout rootLayout = new LinearLayout(this);
        rootLayout.setOrientation(LinearLayout.VERTICAL);

        // 顶部操作工具栏：[📋 复制]  [🗑️ 清空]  [🔄 刷新]
        LinearLayout toolBar = new LinearLayout(this);
        toolBar.setOrientation(LinearLayout.HORIZONTAL);
        toolBar.setPadding(20, 10, 20, 10);
        toolBar.setGravity(Gravity.END);

        Button btnCopy = new Button(this, null, android.R.attr.borderlessButtonStyle);
        btnCopy.setText("📋 复制");
        btnCopy.setTextSize(13);

        Button btnClear = new Button(this, null, android.R.attr.borderlessButtonStyle);
        btnClear.setText("🗑️ 清空");
        btnClear.setTextSize(13);

        Button btnRefresh = new Button(this, null, android.R.attr.borderlessButtonStyle);
        btnRefresh.setText("🔄 刷新");
        btnRefresh.setTextSize(13);

        toolBar.addView(btnCopy);
        toolBar.addView(btnClear);
        toolBar.addView(btnRefresh);
        rootLayout.addView(toolBar);

        // 日志展示区域
        TextView tv = new TextView(this);
        tv.setText(TermuxMcpManager.getInstance().getRecentLogs());
        tv.setTextSize(11);
        tv.setTypeface(Typeface.MONOSPACE);
        tv.setPadding(30, 10, 30, 20);

        ScrollView sv = new ScrollView(this);
        sv.addView(tv);
        LinearLayout.LayoutParams svParams = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1.0f);
        rootLayout.addView(sv, svParams);

        // 自动滑动至最新底部
        sv.post(() -> sv.fullScroll(View.FOCUS_DOWN));

        btnCopy.setOnClickListener(v -> {
            String currentLogs = tv.getText().toString();
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) {
                cm.setPrimaryClip(ClipData.newPlainText("MCP Server Logs", currentLogs));
                Toast.makeText(this, "MCP 服务日志已成功复制到系统剪贴板！", Toast.LENGTH_SHORT).show();
            }
        });

        btnClear.setOnClickListener(v -> {
            TermuxMcpManager.getInstance().clearLogs();
            tv.setText("暂无 MCP 服务运行日志（已清空）");
            Toast.makeText(this, "MCP 服务日志已清空！", Toast.LENGTH_SHORT).show();
        });

        btnRefresh.setOnClickListener(v -> {
            tv.setText(TermuxMcpManager.getInstance().getRecentLogs());
            sv.post(() -> sv.fullScroll(View.FOCUS_DOWN));
            Toast.makeText(this, "日志已刷新", Toast.LENGTH_SHORT).show();
        });

        new AlertDialog.Builder(this)
            .setTitle("Termux MCP 服务实时运行日志")
            .setView(rootLayout)
            .setPositiveButton("关闭", null)
            .show();
    }

    private void showTunnelLogsDialog() {
        LinearLayout rootLayout = new LinearLayout(this);
        rootLayout.setOrientation(LinearLayout.VERTICAL);

        // 顶部操作工具栏：[📋 复制]  [🗑️ 清空]  [🔄 刷新]
        LinearLayout toolBar = new LinearLayout(this);
        toolBar.setOrientation(LinearLayout.HORIZONTAL);
        toolBar.setPadding(20, 10, 20, 10);
        toolBar.setGravity(Gravity.END);

        Button btnCopy = new Button(this, null, android.R.attr.borderlessButtonStyle);
        btnCopy.setText("📋 复制");
        btnCopy.setTextSize(13);

        Button btnClear = new Button(this, null, android.R.attr.borderlessButtonStyle);
        btnClear.setText("🗑️ 清空");
        btnClear.setTextSize(13);

        Button btnRefresh = new Button(this, null, android.R.attr.borderlessButtonStyle);
        btnRefresh.setText("🔄 刷新");
        btnRefresh.setTextSize(13);

        toolBar.addView(btnCopy);
        toolBar.addView(btnClear);
        toolBar.addView(btnRefresh);
        rootLayout.addView(toolBar);

        // 日志展示区域
        TextView tv = new TextView(this);
        tv.setText(OpenAiTunnelManager.getInstance().getRecentLogs());
        tv.setTextSize(11);
        tv.setTypeface(Typeface.MONOSPACE);
        tv.setPadding(30, 10, 30, 20);

        ScrollView sv = new ScrollView(this);
        sv.addView(tv);
        LinearLayout.LayoutParams svParams = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1.0f);
        rootLayout.addView(sv, svParams);

        // 自动滑动至最新底部
        sv.post(() -> sv.fullScroll(View.FOCUS_DOWN));

        btnCopy.setOnClickListener(v -> {
            String currentLogs = tv.getText().toString();
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) {
                cm.setPrimaryClip(ClipData.newPlainText("Tunnel Logs", currentLogs));
                Toast.makeText(this, "隧道日志已成功复制到系统剪贴板！", Toast.LENGTH_SHORT).show();
            }
        });

        btnClear.setOnClickListener(v -> {
            OpenAiTunnelManager.getInstance().clearLogs();
            tv.setText("暂无隧道运行日志（已清空）");
            Toast.makeText(this, "隧道日志已清空！", Toast.LENGTH_SHORT).show();
        });

        btnRefresh.setOnClickListener(v -> {
            tv.setText(OpenAiTunnelManager.getInstance().getRecentLogs());
            sv.post(() -> sv.fullScroll(View.FOCUS_DOWN));
            Toast.makeText(this, "日志已刷新", Toast.LENGTH_SHORT).show();
        });

        new AlertDialog.Builder(this)
            .setTitle("OpenAI 隧道实时运行日志")
            .setView(rootLayout)
            .setPositiveButton("关闭", null)
            .show();
    }

    private void showOpenAiTunnelHelpDialog() {
        new AlertDialog.Builder(this)
            .setTitle("OpenAI 官方安全隧道配置指南")
            .setMessage("1. 访问 platform.openai.com/settings/organization/tunnels 创建一条新隧道并复制 Tunnel ID。\n\n" +
                        "2. 在 platform.openai.com/settings/organization/api-keys 创建一把具有 Tunnels Read + Use 权限的 Runtime API Key。\n\n" +
                        "3. 将上述两项分别填入本界面的输入框，点击开启开关即可！\n\n" +
                        "4. 本方案为出站加密专线直连，完全走 OpenAI 官方通道，无任何第三方流量限额，彻底告别 1GB 流量不足问题！")
            .setPositiveButton("知道了", null)
            .show();
    }

    private void showEditPortDialog() {
        final EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_NUMBER);
        input.setText(String.valueOf(TermuxMcpManager.getInstance().getPort(this)));
        input.setSelectAllOnFocus(true);

        new AlertDialog.Builder(this)
            .setTitle("自定义服务端口")
            .setMessage("请输入 1024 ~ 65535 之间的未分配端口号：")
            .setView(input)
            .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                try {
                    int newPort = Integer.parseInt(input.getText().toString().trim());
                    if (newPort >= 1024 && newPort <= 65535) {
                        TermuxMcpManager.getInstance().setPort(this, newPort);
                        if (TermuxMcpManager.getInstance().isServerRunning()) {
                            TermuxMcpManager.getInstance().restartServer(this);
                        }
                        refreshUI();
                        Toast.makeText(this, "已切换监听端口至 " + newPort, Toast.LENGTH_SHORT).show();
                    } else {
                        Toast.makeText(this, "端口号超出有效范围 (1024-65535)", Toast.LENGTH_SHORT).show();
                    }
                } catch (Exception e) {
                    Toast.makeText(this, "请输入有效的数字端口号", Toast.LENGTH_SHORT).show();
                }
            })
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    private void copyTokenToClipboard() {
        String token = TermuxMcpManager.getInstance().getToken(this);
        copyToClipboard("Termux MCP Token", token);
        Toast.makeText(this, "已复制安全密钥到剪贴板！", Toast.LENGTH_SHORT).show();
    }

    private void copyClientIdToClipboard() {
        String clientId = TermuxMcpManager.getInstance().getOAuthClientId(this);
        copyToClipboard("OAuth Client ID", clientId);
        Toast.makeText(this, "已复制 Client ID：" + clientId, Toast.LENGTH_SHORT).show();
    }

    private void copyClientSecretToClipboard() {
        String secret = TermuxMcpManager.getInstance().getOAuthClientSecret(this);
        copyToClipboard("OAuth Client Secret", secret);
        Toast.makeText(this, "已复制 Client Secret (密码) 到剪贴板！", Toast.LENGTH_SHORT).show();
    }

    private void showEditTimeoutDialog() {
        TermuxMcpManager manager = TermuxMcpManager.getInstance();
        int currentSec = manager.getExecTimeoutSec(this);

        final String[] items = {
            "30 秒 (快速交互)",
            "60 秒 (常规推荐)",
            "300 秒 (5 分钟 / 编译安装)",
            "1800 秒 (30 分钟 / 耗时长任务)",
            "自定义秒数..."
        };
        final int[] values = {30, 60, 300, 1800, -1};

        int checkedItem = 1;
        if (currentSec == 30) checkedItem = 0;
        else if (currentSec == 60) checkedItem = 1;
        else if (currentSec == 300) checkedItem = 2;
        else if (currentSec == 1800) checkedItem = 3;
        else checkedItem = 4;

        new AlertDialog.Builder(this)
            .setTitle("设置命令执行超时时长")
            .setSingleChoiceItems(items, checkedItem, (dialog, which) -> {
                dialog.dismiss();
                if (values[which] == -1) {
                    showCustomTimeoutInputDialog(currentSec);
                } else {
                    manager.setExecTimeoutSec(this, values[which]);
                    refreshUI();
                    Toast.makeText(this, "已将超时限制设置为 " + values[which] + " 秒", Toast.LENGTH_SHORT).show();
                }
            })
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    private void showCustomTimeoutInputDialog(int currentSec) {
        final EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_NUMBER);
        input.setText(String.valueOf(currentSec));
        input.setSelectAllOnFocus(true);

        new AlertDialog.Builder(this)
            .setTitle("自定义超时时长 (秒)")
            .setMessage("请输入 5 ~ 86400 之间的秒数：")
            .setView(input)
            .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                try {
                    int sec = Integer.parseInt(input.getText().toString().trim());
                    if (sec >= 5 && sec <= 86400) {
                        TermuxMcpManager.getInstance().setExecTimeoutSec(this, sec);
                        refreshUI();
                        Toast.makeText(this, "已将超时限制设置为 " + sec + " 秒", Toast.LENGTH_SHORT).show();
                    } else {
                        Toast.makeText(this, "秒数超出有效范围 (5 ~ 86400 秒)", Toast.LENGTH_SHORT).show();
                    }
                } catch (Exception e) {
                    Toast.makeText(this, "请输入有效的数字", Toast.LENGTH_SHORT).show();
                }
            })
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    private LinearLayout createSwitchRow(String title, String subtitle, SwitchMaterial switchView, float density) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setLayoutParams(new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ));
        row.setPadding(0, (int) (6 * density), 0, (int) (6 * density));

        LinearLayout textCol = new LinearLayout(this);
        textCol.setOrientation(LinearLayout.VERTICAL);
        textCol.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f));

        TextView tvTitle = new TextView(this);
        tvTitle.setText(title);
        tvTitle.setTextSize(15);
        tvTitle.setTypeface(null, Typeface.BOLD);
        tvTitle.setTextColor(mTextColorPrimary);
        textCol.addView(tvTitle);

        if (subtitle != null && !subtitle.isEmpty()) {
            TextView tvSub = new TextView(this);
            tvSub.setText(subtitle);
            tvSub.setTextSize(12);
            tvSub.setTextColor(mTextColorSecondary);
            tvSub.setPadding(0, (int) (2 * density), 0, 0);
            textCol.addView(tvSub);
        }

        row.addView(textCol);
        switchView.setText(""); // 消除内置文字，由左侧 TextView 统一排版与控色
        row.addView(switchView);
        row.setOnClickListener(v -> switchView.toggle());
        return row;
    }

    private void addDivider(LinearLayout parent, float density) {
        View divider = new View(this);
        divider.setLayoutParams(new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            (int) (1 * density)
        ));
        divider.setBackgroundColor(0x1A888888);
        LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) divider.getLayoutParams();
        lp.topMargin = (int) (8 * density);
        lp.bottomMargin = (int) (8 * density);
        parent.addView(divider);
    }

    private void copyToClipboard(String label, String text) {
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) {
            ClipData clip = ClipData.newPlainText(label, text);
            cm.setPrimaryClip(clip);
        }
    }

    private MaterialCardView createCard(float density) {
        MaterialCardView card = new MaterialCardView(this);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        );
        lp.bottomMargin = (int) (12 * density);
        card.setLayoutParams(lp);
        card.setRadius(12 * density);
        card.setCardElevation(2 * density);
        card.setUseCompatPadding(true);
        return card;
    }

    private LinearLayout createCardContent(float density) {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding((int) (16 * density), (int) (14 * density), (int) (16 * density), (int) (14 * density));
        return layout;
    }

    private TextView createCardTitle(String text, float density) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(16);
        tv.setTypeface(null, Typeface.BOLD);
        tv.setTextColor(mTextColorPrimary);
        tv.setPadding(0, 0, 0, (int) (10 * density));
        return tv;
    }

    @Override
    public boolean onOptionsItemSelected(android.view.MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }
}
