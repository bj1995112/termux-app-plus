package com.termux.app.styling;

import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.Gravity;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.termux.R;
import com.termux.app.TermuxActivity;
import com.termux.terminal.TerminalSession;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

public class TermuxStyleActivity extends AppCompatActivity {

    private TextView mCurrentColorText;
    private TextView mCurrentFontText;
    private TextView mCurrentPromptText;
    private TextView mPreviewTerminalText;

    private List<TermuxStyleManager.StyleItem> mColorSchemes;
    private List<TermuxStyleManager.StyleItem> mFonts;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_termux_style);

        MaterialToolbar toolbar = findViewById(R.id.style_toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setDisplayShowHomeEnabled(true);
        }

        mCurrentColorText = findViewById(R.id.current_color_scheme_text);
        mCurrentFontText = findViewById(R.id.current_font_text);
        mCurrentPromptText = findViewById(R.id.current_prompt_text);
        mPreviewTerminalText = findViewById(R.id.preview_terminal_text);

        MaterialCardView cardColorScheme = findViewById(R.id.card_color_scheme);
        MaterialCardView cardFonts = findViewById(R.id.card_fonts);
        MaterialCardView cardPromptStyle = findViewById(R.id.card_prompt_style);
        MaterialButton buttonReset = findViewById(R.id.button_reset_style);

        mColorSchemes = TermuxStyleManager.getColorSchemes(this);
        mFonts = TermuxStyleManager.getFonts(this);

        updateCurrentState();

        com.google.android.material.switchmaterial.SwitchMaterial switchDrawerAdapt = findViewById(R.id.switch_drawer_theme_adapt);
        if (switchDrawerAdapt != null) {
            switchDrawerAdapt.setChecked(TermuxStyleManager.isDrawerThemeAdaptEnabled(this));
            switchDrawerAdapt.setOnCheckedChangeListener((buttonView, isChecked) -> {
                TermuxStyleManager.setDrawerThemeAdaptEnabled(this, isChecked);
                TermuxStyleManager.sNeedReloadStyle = true;
                Toast.makeText(this, isChecked ? "已开启抽屉配色跟随终端" : "已恢复抽屉默认经典配色", Toast.LENGTH_SHORT).show();
            });
        }

        if (cardColorScheme != null) cardColorScheme.setOnClickListener(v -> showColorSchemeDialog());
        if (cardFonts != null) cardFonts.setOnClickListener(v -> showFontDialog());
        if (cardPromptStyle != null) cardPromptStyle.setOnClickListener(v -> showPromptStyleDialog());
        if (buttonReset != null) buttonReset.setOnClickListener(v -> showResetDialog());
    }

    private void updateCurrentState() {
        File homeDir = new File(getFilesDir(), "home");
        File termuxDir = new File(homeDir, ".termux");
        File fontFile = new File(termuxDir, "font.ttf");

        if (fontFile.exists() && fontFile.length() > 0) {
            try {
                Typeface tf = Typeface.createFromFile(fontFile);
                if (tf != null) {
                    mPreviewTerminalText.setTypeface(tf);
                }
            } catch (Exception ignored) {
            }
        }

        // 更新命令行样式显示状态
        String curThemeId = TermuxPromptManager.getCurrentThemeId(this);
        String curColorId = TermuxPromptManager.getCurrentColorId(this);
        TermuxPromptManager.PromptTheme theme = TermuxPromptManager.getThemeById(curThemeId);
        TermuxPromptManager.PromptColor color = TermuxPromptManager.getColorById(curColorId);

        if (mCurrentPromptText != null) {
            if ("default".equalsIgnoreCase(curThemeId)) {
                mCurrentPromptText.setText("原生默认 · 保持系统初始状态");
            } else {
                mCurrentPromptText.setText(theme.displayName + " · " + color.displayName);
            }
        }

        if (mPreviewTerminalText != null) {
            if ("default".equalsIgnoreCase(curThemeId)) {
                mPreviewTerminalText.setText("$ echo \"Hello, Termux+\"\nHello, Termux+\n$ ");
            } else {
                String promptSim = theme.previewText;
                mPreviewTerminalText.setText(promptSim + "echo \"Hello, Termux+\"\nHello, Termux+\n" + promptSim);
            }
        }
    }

    private void showColorSchemeDialog() {
        String[] items = new String[mColorSchemes.size()];
        for (int i = 0; i < mColorSchemes.size(); i++) {
            items[i] = mColorSchemes.get(i).displayName;
        }

        new AlertDialog.Builder(this)
            .setTitle(R.string.styling_color_scheme_title)
            .setItems(items, (dialog, which) -> {
                TermuxStyleManager.StyleItem selected = mColorSchemes.get(which);
                boolean success = TermuxStyleManager.applyColorScheme(this, selected.fileName);
                if (success) {
                    mCurrentColorText.setText(selected.displayName);
                    Toast.makeText(this, getString(R.string.styling_applied) + ": " + selected.displayName, Toast.LENGTH_SHORT).show();
                }
            })
            .setNegativeButton(R.string.action_cancel, null)
            .show();
    }

    private void showFontDialog() {
        String[] items = new String[mFonts.size()];
        for (int i = 0; i < mFonts.size(); i++) {
            items[i] = mFonts.get(i).displayName;
        }

        new AlertDialog.Builder(this)
            .setTitle(R.string.styling_font_title)
            .setItems(items, (dialog, which) -> {
                TermuxStyleManager.StyleItem selected = mFonts.get(which);
                boolean success = TermuxStyleManager.applyFont(this, selected.fileName);
                if (success) {
                    mCurrentFontText.setText(selected.displayName);
                    updateCurrentState();
                    Toast.makeText(this, getString(R.string.styling_applied) + ": " + selected.displayName, Toast.LENGTH_SHORT).show();
                }
            })
            .setNegativeButton(R.string.action_cancel, null)
            .show();
    }

    /**
     * 动态命令行样式（Prompt）图形化选择器
     */
    private void showPromptStyleDialog() {
        final List<TermuxPromptManager.PromptTheme> allThemes = TermuxPromptManager.THEMES;
        final List<String> categories = TermuxPromptManager.getCategories();
        final List<TermuxPromptManager.PromptTheme> displayList = new ArrayList<>(allThemes);

        float density = getResources().getDisplayMetrics().density;

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding((int) (12 * density), (int) (8 * density), (int) (12 * density), (int) (8 * density));

        // 横向滚动分类标签栏
        HorizontalScrollView hsv = new HorizontalScrollView(this);
        hsv.setOverScrollMode(View.OVER_SCROLL_NEVER);
        hsv.setHorizontalScrollBarEnabled(false);
        LinearLayout catContainer = new LinearLayout(this);
        catContainer.setOrientation(LinearLayout.HORIZONTAL);
        hsv.addView(catContainer);
        root.addView(hsv);

        // 样式列表控件
        ListView listView = new ListView(this);
        listView.setDividerHeight(0);
        listView.setOverScrollMode(View.OVER_SCROLL_NEVER);
        LinearLayout.LayoutParams lvLp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            (int) (420 * density)
        );
        lvLp.topMargin = (int) (8 * density);
        listView.setLayoutParams(lvLp);
        root.addView(listView);

        AlertDialog dialog = new AlertDialog.Builder(this)
            .setTitle("⚡ 命令行样式 (动态加载 · 跨环境)")
            .setView(root)
            .setNegativeButton(R.string.action_cancel, null)
            .create();

        BaseAdapter adapter = new BaseAdapter() {
            @Override
            public int getCount() {
                return displayList.size();
            }

            @Override
            public TermuxPromptManager.PromptTheme getItem(int position) {
                return displayList.get(position);
            }

            @Override
            public long getItemId(int position) {
                return position;
            }

            class ViewHolder {
                TextView nameView;
                TextView catView;
                TextView previewView;
            }

            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                TermuxPromptManager.PromptTheme item = getItem(position);
                LinearLayout itemLayout;
                ViewHolder holder;
                if (convertView == null) {
                    itemLayout = new LinearLayout(TermuxStyleActivity.this);
                    itemLayout.setOrientation(LinearLayout.VERTICAL);
                    itemLayout.setPadding((int) (10 * density), (int) (10 * density), (int) (10 * density), (int) (10 * density));
                    itemLayout.setBackgroundResource(android.R.drawable.list_selector_background);

                    LinearLayout titleRow = new LinearLayout(TermuxStyleActivity.this);
                    titleRow.setOrientation(LinearLayout.HORIZONTAL);
                    titleRow.setGravity(Gravity.CENTER_VERTICAL);

                    TextView tvName = new TextView(TermuxStyleActivity.this);
                    tvName.setTextSize(14);
                    tvName.setTypeface(null, Typeface.BOLD);
                    tvName.setTextColor(0xFFE0E0E0);
                    LinearLayout.LayoutParams np = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f);
                    tvName.setLayoutParams(np);

                    TextView tvCat = new TextView(TermuxStyleActivity.this);
                    tvCat.setTextSize(11);
                    tvCat.setTextColor(0xFF00E5FF);
                    tvCat.setPadding((int) (6 * density), (int) (2 * density), (int) (6 * density), (int) (2 * density));

                    titleRow.addView(tvName);
                    titleRow.addView(tvCat);
                    itemLayout.addView(titleRow);

                    // 终端真实微缩预览框
                    TextView previewBox = new TextView(TermuxStyleActivity.this);
                    previewBox.setTextSize(12);
                    previewBox.setTypeface(Typeface.MONOSPACE);
                    previewBox.setTextColor(0xFF81D4FA);
                    previewBox.setBackgroundColor(0xFF1E1E1E);
                    previewBox.setPadding((int) (10 * density), (int) (6 * density), (int) (10 * density), (int) (6 * density));
                    LinearLayout.LayoutParams pp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    );
                    pp.topMargin = (int) (6 * density);
                    previewBox.setLayoutParams(pp);

                    itemLayout.addView(previewBox);

                    holder = new ViewHolder();
                    holder.nameView = tvName;
                    holder.catView = tvCat;
                    holder.previewView = previewBox;
                    itemLayout.setTag(holder);
                } else {
                    itemLayout = (LinearLayout) convertView;
                    holder = (ViewHolder) itemLayout.getTag();
                }

                if (holder != null) {
                    holder.nameView.setText(item.displayName);
                    holder.catView.setText(item.category);
                    holder.previewView.setText(item.previewText);
                }

                return itemLayout;
            }
        };

        listView.setAdapter(adapter);

        // 分类按钮点击切换
        for (String cat : categories) {
            MaterialButton btn = new MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle);
            btn.setText(cat);
            btn.setTextSize(11);
            btn.setPadding((int) (8 * density), 0, (int) (8 * density), 0);
            btn.setCornerRadius((int) (16 * density));
            LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                (int) (32 * density)
            );
            bp.rightMargin = (int) (6 * density);
            btn.setLayoutParams(bp);

            btn.setOnClickListener(v -> {
                displayList.clear();
                if (cat.equals("全部")) {
                    displayList.addAll(allThemes);
                } else {
                    for (TermuxPromptManager.PromptTheme item : allThemes) {
                        if (item.category.equals(cat)) {
                            displayList.add(item);
                        }
                    }
                }
                adapter.notifyDataSetChanged();
            });
            catContainer.addView(btn);
        }

        listView.setOnItemClickListener((parent, view, position, id) -> {
            TermuxPromptManager.PromptTheme selected = displayList.get(position);
            dialog.dismiss();

            if ("default".equalsIgnoreCase(selected.id)) {
                // 原生默认，直接应用并恢复
                applyThemeAndColor(selected, TermuxPromptManager.getColorById("cyan"));
            } else {
                // 弹出搭配色卡选择器
                showPromptColorDialog(selected);
            }
        });

        dialog.show();
    }

    /**
     * 搭配色彩选择对话框
     */
    private void showPromptColorDialog(TermuxPromptManager.PromptTheme theme) {
        final List<TermuxPromptManager.PromptColor> colors = TermuxPromptManager.COLORS;
        String[] colorNames = new String[colors.size()];
        for (int i = 0; i < colors.size(); i++) {
            colorNames[i] = colors.get(i).displayName;
        }

        String curColorId = TermuxPromptManager.getCurrentColorId(this);
        int defaultIndex = 0;
        for (int i = 0; i < colors.size(); i++) {
            if (colors.get(i).id.equalsIgnoreCase(curColorId)) {
                defaultIndex = i;
                break;
            }
        }

        new AlertDialog.Builder(this)
            .setTitle("🎨 选择主打色彩 (" + theme.displayName + ")")
            .setSingleChoiceItems(colorNames, defaultIndex, (dialog, which) -> {
                TermuxPromptManager.PromptColor chosenColor = colors.get(which);
                dialog.dismiss();
                applyThemeAndColor(theme, chosenColor);
            })
            .setNegativeButton(R.string.action_cancel, null)
            .show();
    }

    private void applyThemeAndColor(TermuxPromptManager.PromptTheme theme, TermuxPromptManager.PromptColor color) {
        TerminalSession session = TermuxActivity.sInstance != null ? TermuxActivity.sInstance.getCurrentSession() : null;
        boolean success = TermuxPromptManager.applyPromptStyle(this, session, theme.id, color.id);
        if (success) {
            updateCurrentState();
            TermuxStyleManager.sNeedReloadStyle = true;
            if ("default".equalsIgnoreCase(theme.id)) {
                Toast.makeText(this, "已恢复系统初始命令行样式", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, "已动态应用：" + theme.displayName + " · " + color.displayName + "（就地热加载生效）", Toast.LENGTH_SHORT).show();
            }
        }
    }

    private void showResetDialog() {
        new AlertDialog.Builder(this)
            .setTitle(R.string.styling_reset_title)
            .setMessage(R.string.styling_reset_confirm)
            .setPositiveButton(R.string.action_yes, (dialog, which) -> {
                boolean success = TermuxStyleManager.resetToDefault(this);
                if (success) {
                    mCurrentColorText.setText(R.string.styling_color_scheme_summary);
                    mCurrentFontText.setText(R.string.styling_font_summary);
                    mPreviewTerminalText.setTypeface(Typeface.MONOSPACE);

                    // 同步重置 Prompt 状态
                    TerminalSession session = TermuxActivity.sInstance != null ? TermuxActivity.sInstance.getCurrentSession() : null;
                    TermuxPromptManager.applyPromptStyle(this, session, "default", "cyan");
                    updateCurrentState();

                    Toast.makeText(this, R.string.styling_applied, Toast.LENGTH_SHORT).show();
                }
            })
            .setNegativeButton(R.string.action_no, null)
            .show();
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }
}
