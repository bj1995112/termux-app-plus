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

    private TextView mTvOAuthClientId;
    private TextView mTvOAuthClientSecret;
    private TextView mTvOAuthUrls;

    private int mTextColorPrimary;
    private int mTextColorSecondary;

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

        mTvOAuthClientId = new TextView(this);
        mTvOAuthClientId.setTextSize(13);
        mTvOAuthClientId.setTextColor(mTextColorPrimary);
        oauthLayout.addView(mTvOAuthClientId);

        LinearLayout oauthSecretRow = new LinearLayout(this);
        oauthSecretRow.setOrientation(LinearLayout.HORIZONTAL);
        oauthSecretRow.setGravity(Gravity.CENTER_VERTICAL);
        oauthSecretRow.setPadding(0, (int) (4 * density), 0, (int) (4 * density));

        mTvOAuthClientSecret = new TextView(this);
        mTvOAuthClientSecret.setTextSize(13);
        mTvOAuthClientSecret.setTextColor(mTextColorPrimary);
        mTvOAuthClientSecret.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f));
        oauthSecretRow.addView(mTvOAuthClientSecret);

        MaterialButton btnResetSecret = new MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle);
        btnResetSecret.setText("重置 Secret");
        btnResetSecret.setTextSize(11);
        btnResetSecret.setOnClickListener(v -> {
            TermuxMcpManager.getInstance().resetOAuthClientSecret(this);
            refreshUI();
            Toast.makeText(this, "已重置 OAuth Client Secret", Toast.LENGTH_SHORT).show();
        });
        oauthSecretRow.addView(btnResetSecret);
        oauthLayout.addView(oauthSecretRow);

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
        // 卡片 4：客户端一键配置导出 (Cursor / Claude / ChatGPT)
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

        MaterialButton btnCopyCf = new MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle);
        btnCopyCf.setText("复制 Cloudflare 免费公网穿透命令");
        btnCopyCf.setTextSize(11);
        btnCopyCf.setOnClickListener(v -> {
            String cmd = TermuxMcpManager.getInstance().getCloudflareCommand(this);
            copyToClipboard("Cloudflare Tunnel Command", cmd);
            Toast.makeText(this, "已复制穿透命令，在 Termux 终端运行即可生成免费公网 HTTPS 链接！", Toast.LENGTH_LONG).show();
        });
        clientsLayout.addView(btnCopyCf);

        cardClients.addView(clientsLayout);
        content.addView(cardClients);

        // ────────────────────────────
        // 开关事件绑定
        // ────────────────────────────
        mSwitchService.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (buttonView.isPressed()) {
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
            }
        });

        mSwitchAutoStart.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (buttonView.isPressed()) {
                TermuxMcpManager.getInstance().setAutoStartEnabled(this, isChecked);
                Toast.makeText(this, isChecked ? "已启用开机与启动自启" : "已关闭启动自启", Toast.LENGTH_SHORT).show();
            }
        });

        mSwitchWakeLock.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (buttonView.isPressed()) {
                TermuxMcpManager.getInstance().setWakeLockEnabled(this, isChecked);
                Toast.makeText(this, isChecked ? "已启用 CPU 唤醒保活 (WakeLock)" : "已关闭 CPU 唤醒保活", Toast.LENGTH_SHORT).show();
            }
        });

        refreshUI();
    }

    private void refreshUI() {
        TermuxMcpManager manager = TermuxMcpManager.getInstance();
        boolean running = manager.isServerRunning();
        int port = manager.getPort(this);
        String token = manager.getToken(this);
        int timeoutSec = manager.getExecTimeoutSec(this);
        String localIp = TermuxMcpManager.getLocalIpAddress();

        mSwitchService.setChecked(running);
        mSwitchAutoStart.setChecked(manager.isAutoStartEnabled(this));
        mSwitchWakeLock.setChecked(manager.isWakeLockEnabled(this));

        if (running) {
            mTvStatus.setText("🟢 状态：运行中（双模与 OAuth 2.1 监听 0.0.0.0:" + port + "）");
            mTvStatus.setTextColor(0xFF2E7D32); // 绿色
            mTvAddress.setVisibility(View.VISIBLE);
            mTvAddress.setText("局域网地址: http://" + localIp + ":" + port + "/mcp\n" +
                              "经典 SSE 端点: http://" + localIp + ":" + port + "/sse\n" +
                              "OAuth 发现: http://" + localIp + ":" + port + "/.well-known/oauth-authorization-server");
        } else {
            mTvStatus.setText("⚪ 状态：未运行");
            mTvStatus.setTextColor(mTextColorSecondary);
            mTvAddress.setVisibility(View.GONE);
        }

        mTvPort.setText("当前监听端口：" + port);
        mTvToken.setText(token.isEmpty() ? "（未设置 · 免密模式）" : token);
        mTvTimeout.setText("当前超时上限：" + timeoutSec + " 秒（超时后自动终止进程）");

        mTvOAuthClientId.setText("Client ID: " + manager.getOAuthClientId(this));
        mTvOAuthClientSecret.setText("Client Secret: " + manager.getOAuthClientSecret(this));
        mTvOAuthUrls.setText(
            "授权端点: http://" + localIp + ":" + port + "/oauth/authorize\n" +
            "令牌端点: http://" + localIp + ":" + port + "/oauth/token"
        );
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
