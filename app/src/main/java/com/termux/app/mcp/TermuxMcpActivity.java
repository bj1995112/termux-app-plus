package com.termux.app.mcp;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Typeface;
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

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.switchmaterial.SwitchMaterial;

/**
 * AI 远程协同与 MCP 服务设置界面：
 * 提供服务启停开关、端口自定义修改、安全密钥管理、开机自启、以及针对 Cursor、Claude Desktop 与 ChatGPT 的配置一键导出。
 */
public class TermuxMcpActivity extends AppCompatActivity {

    private SwitchMaterial mSwitchService;
    private TextView mTvStatus;
    private TextView mTvAddress;
    private TextView mTvPort;
    private TextView mTvToken;
    private SwitchMaterial mSwitchAutoStart;

    private int mTextColorPrimary;
    private int mTextColorSecondary;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        float density = getResources().getDisplayMetrics().density;

        // 动态解析系统主题主次文字颜色，保证日间/夜间模式最高对比度
        TypedValue tvPrimary = new TypedValue();
        getTheme().resolveAttribute(android.R.attr.textColorPrimary, tvPrimary, true);
        mTextColorPrimary = tvPrimary.data != 0 ? tvPrimary.data : 0xFF212121;

        TypedValue tvSecondary = new TypedValue();
        getTheme().resolveAttribute(android.R.attr.textColorSecondary, tvSecondary, true);
        mTextColorSecondary = tvSecondary.data != 0 ? tvSecondary.data : 0xFF757575;

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
        tvIntro.setText("🚀 基于 2026 最新官方 Model Context Protocol 规范，原生内置零依赖引擎。允许电脑端 Cursor、Claude Desktop 及云端 ChatGPT 远程安全操控手机终端与查询硬件状态。");
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

        TextView tvTitle1 = createCardTitle("⚡ 服务运行状态", density);
        statusLayout.addView(tvTitle1);

        mSwitchService = new SwitchMaterial(this);
        mSwitchService.setText("开启 MCP 远程协同服务");
        mSwitchService.setTextSize(15);
        mSwitchService.setTextColor(mTextColorPrimary);
        statusLayout.addView(mSwitchService);

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

        mSwitchAutoStart = new SwitchMaterial(this);
        mSwitchAutoStart.setText("Termux 启动时自动开启服务");
        mSwitchAutoStart.setTextSize(13);
        mSwitchAutoStart.setTextColor(mTextColorSecondary);
        statusLayout.addView(mSwitchAutoStart);

        cardStatus.addView(statusLayout);
        content.addView(cardStatus);

        // ────────────────────────────
        // 卡片 2：端口与安全密钥 (Token)
        // ────────────────────────────
        MaterialCardView cardConfig = createCard(density);
        LinearLayout configLayout = createCardContent(density);

        TextView tvTitle2 = createCardTitle("⚙️ 网络端口与安全密钥", density);
        configLayout.addView(tvTitle2);

        // 端口行
        LinearLayout portRow = new LinearLayout(this);
        portRow.setOrientation(LinearLayout.HORIZONTAL);
        portRow.setGravity(Gravity.CENTER_VERTICAL);
        portRow.setPadding(0, (int) (4 * density), 0, (int) (4 * density));

        mTvPort = new TextView(this);
        mTvPort.setTextSize(14);
        mTvPort.setTextColor(mTextColorPrimary);
        LinearLayout.LayoutParams lpPortText = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f);
        mTvPort.setLayoutParams(lpPortText);
        portRow.addView(mTvPort);

        MaterialButton btnEditPort = new MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle);
        btnEditPort.setText("修改端口");
        btnEditPort.setTextSize(11);
        btnEditPort.setOnClickListener(v -> showEditPortDialog());
        portRow.addView(btnEditPort);
        configLayout.addView(portRow);

        // 密钥行
        LinearLayout tokenRow = new LinearLayout(this);
        tokenRow.setOrientation(LinearLayout.HORIZONTAL);
        tokenRow.setGravity(Gravity.CENTER_VERTICAL);
        tokenRow.setPadding(0, (int) (8 * density), 0, (int) (4 * density));

        mTvToken = new TextView(this);
        mTvToken.setTextSize(14);
        mTvToken.setTextColor(mTextColorPrimary);
        LinearLayout.LayoutParams lpTokenText = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f);
        mTvToken.setLayoutParams(lpTokenText);
        tokenRow.addView(mTvToken);

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
        tokenRow.addView(btnGenToken);
        configLayout.addView(tokenRow);

        cardConfig.addView(configLayout);
        content.addView(cardConfig);

        // ────────────────────────────
        // 卡片 3：客户端一键配置导出 (Cursor / Claude / ChatGPT)
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

        // 事件绑定
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
                Toast.makeText(this, isChecked ? "已启用启动自启" : "已关闭启动自启", Toast.LENGTH_SHORT).show();
            }
        });

        refreshUI();
    }

    private void refreshUI() {
        TermuxMcpManager manager = TermuxMcpManager.getInstance();
        boolean running = manager.isServerRunning();
        int port = manager.getPort(this);
        String token = manager.getToken(this);
        String localIp = TermuxMcpManager.getLocalIpAddress();

        mSwitchService.setChecked(running);
        mSwitchAutoStart.setChecked(manager.isAutoStartEnabled(this));

        if (running) {
            mTvStatus.setText("🟢 状态：运行中（双模监听 0.0.0.0:" + port + "）");
            mTvStatus.setTextColor(0xFF2E7D32); // 绿色
            mTvAddress.setVisibility(View.VISIBLE);
            mTvAddress.setText("局域网地址: http://" + localIp + ":" + port + "/mcp\n" +
                              "经典 SSE 端点: http://" + localIp + ":" + port + "/sse");
        } else {
            mTvStatus.setText("⚪ 状态：未运行");
            mTvStatus.setTextColor(mTextColorSecondary);
            mTvAddress.setVisibility(View.GONE);
        }

        mTvPort.setText("监听端口：" + port + " (冷门专属端口)");
        mTvToken.setText("安全密钥：" + (token.isEmpty() ? "（未设置 · 免密）" : token));
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
