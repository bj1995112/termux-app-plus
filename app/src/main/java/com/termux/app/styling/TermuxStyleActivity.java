package com.termux.app.styling;

import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.Gravity;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.util.TypedValue;
import android.widget.BaseAdapter;
import android.widget.FrameLayout;
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
import java.util.Set;

public class TermuxStyleActivity extends AppCompatActivity {

    private TextView mCurrentColorText;
    private TextView mCurrentFontText;
    private TextView mCurrentPromptText;
    private TextView mPreviewTerminalText;
    private View mPreviewTerminalBox;
    private TextView mPreviewTitle;

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
        mPreviewTerminalBox = findViewById(R.id.preview_terminal_box);
        mPreviewTitle = findViewById(R.id.preview_title);

        MaterialCardView cardColorScheme = findViewById(R.id.card_color_scheme);
        MaterialCardView cardFonts = findViewById(R.id.card_fonts);
        MaterialCardView cardPromptStyle = findViewById(R.id.card_prompt_style);
        MaterialButton buttonReset = findViewById(R.id.button_reset_style);

        mColorSchemes = TermuxStyleManager.getColorSchemes(this);
        mFonts = TermuxStyleManager.getFonts(this);
        TermuxStyleManager.sortItemsWithFavorites(mColorSchemes, TermuxStyleManager.getFavoriteColors(this));
        TermuxStyleManager.sortItemsWithFavorites(mFonts, TermuxStyleManager.getFavoriteFonts(this));

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
        // 1. 动态联动终端当前配色方案
        int[] terminalColors = TermuxStyleManager.getTerminalCurrentColors(this);
        int termBg = terminalColors[0];
        int termFg = terminalColors[1];

        if (mPreviewTerminalBox != null) {
            mPreviewTerminalBox.setBackgroundColor(termBg);
        }
        if (mPreviewTerminalText != null) {
            mPreviewTerminalText.setTextColor(termFg);
        }
        if (mPreviewTitle != null) {
            mPreviewTitle.setTextColor(termFg);
        }

        // 2. 动态联动终端当前字体
        File homeDir = new File(getFilesDir(), "home");
        File termuxDir = new File(homeDir, ".termux");
        File fontFile = new File(termuxDir, "font.ttf");

        if (fontFile.exists() && fontFile.length() > 0) {
            try {
                Typeface tf = Typeface.createFromFile(fontFile);
                if (tf != null && mPreviewTerminalText != null) {
                    mPreviewTerminalText.setTypeface(tf);
                }
            } catch (Exception ignored) {
            }
        } else {
            if (mPreviewTerminalText != null) {
                mPreviewTerminalText.setTypeface(Typeface.MONOSPACE);
            }
        }

        // 3. 动态联动命令行样式（Prompt）
        String curThemeId = TermuxPromptManager.getCurrentThemeId(this);
        String curColorId = TermuxPromptManager.getCurrentColorId(this);
        TermuxPromptManager.PromptTheme theme = TermuxPromptManager.getThemeById(curThemeId);
        TermuxPromptManager.PromptColor color = TermuxPromptManager.getColorById(curColorId);

        if (mCurrentPromptText != null) {
            mCurrentPromptText.setText(theme.displayName + " · " + color.displayName);
        }

        if (mPreviewTerminalText != null) {
            String promptSim;
            if ("default".equalsIgnoreCase(curThemeId)) {
                promptSim = "root@localhost:~$ ";
            } else {
                promptSim = theme.previewText;
            }
            mPreviewTerminalText.setText(promptSim + "cat README.md\n[+] Termux+ 终端增强版就绪\n" + promptSim);
        }
    }

    private void showColorSchemeDialog() {
        float density = getResources().getDisplayMetrics().density;
        Set<String> favColors = TermuxStyleManager.getFavoriteColors(this);
        TermuxStyleManager.sortItemsWithFavorites(mColorSchemes, favColors);

        // 动态读取系统主题颜色，确保在日间模式（白底）与夜间模式（深底）下均拥有最佳对比度
        TypedValue tvPrimary = new TypedValue();
        getTheme().resolveAttribute(android.R.attr.textColorPrimary, tvPrimary, true);
        final int textColorPrimary = tvPrimary.data != 0 ? tvPrimary.data : 0xFF212121;

        TypedValue tvSecondary = new TypedValue();
        getTheme().resolveAttribute(android.R.attr.textColorSecondary, tvSecondary, true);
        final int textColorSecondary = tvSecondary.data != 0 ? tvSecondary.data : 0xFF757575;

        TypedValue tvRipple = new TypedValue();
        getTheme().resolveAttribute(android.R.attr.selectableItemBackground, tvRipple, true);
        final int rippleResId = tvRipple.resourceId;

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);

        TextView hint = new TextView(this);
        hint.setText("💡 点击直接应用，点击星号 ★ 收藏并置顶常用主题");
        hint.setTextSize(12);
        hint.setTextColor(textColorSecondary);
        hint.setPadding((int) (16 * density), (int) (10 * density), (int) (16 * density), (int) (6 * density));
        content.addView(hint);

        ListView listView = new ListView(this);
        listView.setDividerHeight(1);
        // 关键：禁用 fastScrollEnabled，杜绝右侧滑块区域拦截并吞噬收藏星号的点击事件
        listView.setFastScrollEnabled(false);
        LinearLayout.LayoutParams lpList = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        );
        listView.setLayoutParams(lpList);
        content.addView(listView);

        AlertDialog dialog = new AlertDialog.Builder(this)
            .setTitle(R.string.styling_color_scheme_title)
            .setView(content)
            .setNegativeButton(R.string.action_cancel, null)
            .create();

        BaseAdapter adapter = new BaseAdapter() {
            @Override
            public int getCount() {
                return mColorSchemes.size();
            }

            @Override
            public TermuxStyleManager.StyleItem getItem(int position) {
                return mColorSchemes.get(position);
            }

            @Override
            public long getItemId(int position) {
                return position;
            }

            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                LinearLayout row;
                TextView tvName;
                FrameLayout starContainer;
                TextView tvStar;

                if (convertView == null) {
                    row = new LinearLayout(TermuxStyleActivity.this);
                    row.setOrientation(LinearLayout.HORIZONTAL);
                    row.setGravity(Gravity.CENTER_VERTICAL);
                    row.setPadding((int) (16 * density), 0, (int) (4 * density), 0);
                    if (rippleResId != 0) {
                        row.setBackgroundResource(rippleResId);
                    } else {
                        row.setBackgroundResource(android.R.drawable.list_selector_background);
                    }

                    tvName = new TextView(TermuxStyleActivity.this);
                    tvName.setTextSize(14);
                    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
                    tvName.setLayoutParams(lp);
                    row.addView(tvName);

                    // 独立的大面积触摸容器（48dp x 48dp 黄金触摸区，保证点击 100% 灵敏接收）
                    starContainer = new FrameLayout(TermuxStyleActivity.this);
                    LinearLayout.LayoutParams lpStar = new LinearLayout.LayoutParams(
                        (int) (48 * density), (int) (48 * density)
                    );
                    starContainer.setLayoutParams(lpStar);
                    if (rippleResId != 0) {
                        starContainer.setBackgroundResource(rippleResId);
                    }

                    tvStar = new TextView(TermuxStyleActivity.this);
                    tvStar.setTextSize(20);
                    FrameLayout.LayoutParams fpStar = new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER
                    );
                    tvStar.setLayoutParams(fpStar);
                    starContainer.addView(tvStar);
                    row.addView(starContainer);

                    row.setTag(new View[]{tvName, starContainer, tvStar});
                } else {
                    row = (LinearLayout) convertView;
                    View[] holder = (View[]) row.getTag();
                    tvName = (TextView) holder[0];
                    starContainer = (FrameLayout) holder[1];
                    tvStar = (TextView) holder[2];
                }

                TermuxStyleManager.StyleItem item = getItem(position);
                boolean isDefault = TermuxStyleManager.DEFAULT_NAME.equalsIgnoreCase(item.fileName);
                boolean isFav = TermuxStyleManager.isFavoriteColor(TermuxStyleActivity.this, item.fileName);

                if (isFav) {
                    tvName.setText("★ " + item.displayName);
                    tvName.setTextColor(0xFFFFA000); // 鲜亮琥珀金，无论深色还是浅色背景均极致清晰
                    tvStar.setText("★");
                    tvStar.setTextColor(0xFFFFA000);
                } else {
                    tvName.setText(item.displayName);
                    tvName.setTextColor(textColorPrimary); // 跟随系统主题，白天为深黑灰，夜晚为纯白，对比度极高
                    tvStar.setText(isDefault ? "" : "☆");
                    tvStar.setTextColor(textColorSecondary);
                }

                if (isDefault) {
                    starContainer.setVisibility(View.GONE);
                } else {
                    starContainer.setVisibility(View.VISIBLE);
                    starContainer.setOnClickListener(v -> {
                        boolean nowFav = TermuxStyleManager.toggleFavoriteColor(TermuxStyleActivity.this, item.fileName);
                        Set<String> updatedFavs = TermuxStyleManager.getFavoriteColors(TermuxStyleActivity.this);
                        TermuxStyleManager.sortItemsWithFavorites(mColorSchemes, updatedFavs);
                        notifyDataSetChanged();
                        Toast.makeText(TermuxStyleActivity.this, nowFav ? "已收藏并置顶：" + item.displayName : "已取消收藏", Toast.LENGTH_SHORT).show();
                    });
                }

                row.setOnClickListener(v -> {
                    dialog.dismiss();
                    boolean success = TermuxStyleManager.applyColorScheme(TermuxStyleActivity.this, item.fileName);
                    if (success) {
                        mCurrentColorText.setText(item.displayName);
                        updateCurrentState();
                        Toast.makeText(TermuxStyleActivity.this, getString(R.string.styling_applied) + ": " + item.displayName, Toast.LENGTH_SHORT).show();
                    }
                });

                return row;
            }
        };

        listView.setAdapter(adapter);
        dialog.show();
    }

    private void showFontDialog() {
        float density = getResources().getDisplayMetrics().density;
        Set<String> favFonts = TermuxStyleManager.getFavoriteFonts(this);
        TermuxStyleManager.sortItemsWithFavorites(mFonts, favFonts);

        // 动态读取系统主题颜色，确保在日间模式（白底）与夜间模式（深底）下均拥有最佳对比度
        TypedValue tvPrimary = new TypedValue();
        getTheme().resolveAttribute(android.R.attr.textColorPrimary, tvPrimary, true);
        final int textColorPrimary = tvPrimary.data != 0 ? tvPrimary.data : 0xFF212121;

        TypedValue tvSecondary = new TypedValue();
        getTheme().resolveAttribute(android.R.attr.textColorSecondary, tvSecondary, true);
        final int textColorSecondary = tvSecondary.data != 0 ? tvSecondary.data : 0xFF757575;

        TypedValue tvRipple = new TypedValue();
        getTheme().resolveAttribute(android.R.attr.selectableItemBackground, tvRipple, true);
        final int rippleResId = tvRipple.resourceId;

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);

        TextView hint = new TextView(this);
        hint.setText("💡 点击直接应用，点击星号 ★ 收藏并置顶常用字体");
        hint.setTextSize(12);
        hint.setTextColor(textColorSecondary);
        hint.setPadding((int) (16 * density), (int) (10 * density), (int) (16 * density), (int) (6 * density));
        content.addView(hint);

        ListView listView = new ListView(this);
        listView.setDividerHeight(1);
        // 关键：禁用 fastScrollEnabled，杜绝右侧滑块区域拦截并吞噬收藏星号的点击事件
        listView.setFastScrollEnabled(false);
        LinearLayout.LayoutParams lpList = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        );
        listView.setLayoutParams(lpList);
        content.addView(listView);

        AlertDialog dialog = new AlertDialog.Builder(this)
            .setTitle(R.string.styling_font_title)
            .setView(content)
            .setNegativeButton(R.string.action_cancel, null)
            .create();

        BaseAdapter adapter = new BaseAdapter() {
            @Override
            public int getCount() {
                return mFonts.size();
            }

            @Override
            public TermuxStyleManager.StyleItem getItem(int position) {
                return mFonts.get(position);
            }

            @Override
            public long getItemId(int position) {
                return position;
            }

            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                LinearLayout row;
                TextView tvName;
                FrameLayout starContainer;
                TextView tvStar;

                if (convertView == null) {
                    row = new LinearLayout(TermuxStyleActivity.this);
                    row.setOrientation(LinearLayout.HORIZONTAL);
                    row.setGravity(Gravity.CENTER_VERTICAL);
                    row.setPadding((int) (16 * density), 0, (int) (4 * density), 0);
                    if (rippleResId != 0) {
                        row.setBackgroundResource(rippleResId);
                    } else {
                        row.setBackgroundResource(android.R.drawable.list_selector_background);
                    }

                    tvName = new TextView(TermuxStyleActivity.this);
                    tvName.setTextSize(14);
                    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
                    tvName.setLayoutParams(lp);
                    row.addView(tvName);

                    // 独立的大面积触摸容器（48dp x 48dp 黄金触摸区，保证点击 100% 灵敏接收）
                    starContainer = new FrameLayout(TermuxStyleActivity.this);
                    LinearLayout.LayoutParams lpStar = new LinearLayout.LayoutParams(
                        (int) (48 * density), (int) (48 * density)
                    );
                    starContainer.setLayoutParams(lpStar);
                    if (rippleResId != 0) {
                        starContainer.setBackgroundResource(rippleResId);
                    }

                    tvStar = new TextView(TermuxStyleActivity.this);
                    tvStar.setTextSize(20);
                    FrameLayout.LayoutParams fpStar = new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER
                    );
                    tvStar.setLayoutParams(fpStar);
                    starContainer.addView(tvStar);
                    row.addView(starContainer);

                    row.setTag(new View[]{tvName, starContainer, tvStar});
                } else {
                    row = (LinearLayout) convertView;
                    View[] holder = (View[]) row.getTag();
                    tvName = (TextView) holder[0];
                    starContainer = (FrameLayout) holder[1];
                    tvStar = (TextView) holder[2];
                }

                TermuxStyleManager.StyleItem item = getItem(position);
                boolean isDefault = TermuxStyleManager.DEFAULT_NAME.equalsIgnoreCase(item.fileName);
                boolean isFav = TermuxStyleManager.isFavoriteFont(TermuxStyleActivity.this, item.fileName);

                if (isFav) {
                    tvName.setText("★ " + item.displayName);
                    tvName.setTextColor(0xFFFFA000); // 鲜亮琥珀金，无论深色还是浅色背景均极致清晰
                    tvStar.setText("★");
                    tvStar.setTextColor(0xFFFFA000);
                } else {
                    tvName.setText(item.displayName);
                    tvName.setTextColor(textColorPrimary); // 跟随系统主题，白天为深黑灰，夜晚为纯白，对比度极高
                    tvStar.setText(isDefault ? "" : "☆");
                    tvStar.setTextColor(textColorSecondary);
                }

                if (isDefault) {
                    starContainer.setVisibility(View.GONE);
                } else {
                    starContainer.setVisibility(View.VISIBLE);
                    starContainer.setOnClickListener(v -> {
                        boolean nowFav = TermuxStyleManager.toggleFavoriteFont(TermuxStyleActivity.this, item.fileName);
                        Set<String> updatedFavs = TermuxStyleManager.getFavoriteFonts(TermuxStyleActivity.this);
                        TermuxStyleManager.sortItemsWithFavorites(mFonts, updatedFavs);
                        notifyDataSetChanged();
                        Toast.makeText(TermuxStyleActivity.this, nowFav ? "已收藏并置顶：" + item.displayName : "已取消收藏", Toast.LENGTH_SHORT).show();
                    });
                }

                row.setOnClickListener(v -> {
                    dialog.dismiss();
                    boolean success = TermuxStyleManager.applyFont(TermuxStyleActivity.this, item.fileName);
                    if (success) {
                        mCurrentFontText.setText(item.displayName);
                        updateCurrentState();
                        Toast.makeText(TermuxStyleActivity.this, getString(R.string.styling_applied) + ": " + item.displayName, Toast.LENGTH_SHORT).show();
                    }
                });

                return row;
            }
        };

        listView.setAdapter(adapter);
        dialog.show();
    }

    /**
     * 动态命令行样式（Prompt）一体化图形选择器（色彩 + 样式同屏即选即显）
     */
    private void showPromptStyleDialog() {
        final List<TermuxPromptManager.PromptTheme> allThemes = TermuxPromptManager.THEMES;
        final List<String> categories = TermuxPromptManager.getCategories();
        final List<TermuxPromptManager.PromptTheme> displayList = new ArrayList<>(allThemes);
        final List<TermuxPromptManager.PromptColor> colors = TermuxPromptManager.COLORS;

        float density = getResources().getDisplayMetrics().density;

        // 当前选中的颜色（可被顶部药丸动态切换）
        String initColorId = TermuxPromptManager.getCurrentColorId(this);
        final TermuxPromptManager.PromptColor[] currentColorHolder = new TermuxPromptManager.PromptColor[]{
            TermuxPromptManager.getColorById(initColorId)
        };

        TypedValue tvPrimary = new TypedValue();
        getTheme().resolveAttribute(android.R.attr.textColorPrimary, tvPrimary, true);
        final int textColorPrimary = tvPrimary.data != 0 ? tvPrimary.data : 0xFF212121;

        TypedValue tvSecondary = new TypedValue();
        getTheme().resolveAttribute(android.R.attr.textColorSecondary, tvSecondary, true);
        final int textColorSecondary = tvSecondary.data != 0 ? tvSecondary.data : 0xFF757575;

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding((int) (12 * density), (int) (8 * density), (int) (12 * density), (int) (8 * density));

        // 1. 顶部色卡横向滑动切换栏
        TextView colorTitle = new TextView(this);
        colorTitle.setText("🎨 搭配主打色 (点击即时变色)：");
        colorTitle.setTextSize(12);
        colorTitle.setTextColor(textColorSecondary);
        colorTitle.setPadding(0, 0, 0, (int) (4 * density));
        root.addView(colorTitle);

        HorizontalScrollView hsvColor = new HorizontalScrollView(this);
        hsvColor.setOverScrollMode(View.OVER_SCROLL_NEVER);
        hsvColor.setHorizontalScrollBarEnabled(false);
        LinearLayout colorContainer = new LinearLayout(this);
        colorContainer.setOrientation(LinearLayout.HORIZONTAL);
        hsvColor.addView(colorContainer);
        root.addView(hsvColor);

        // 2. 分类横向滑动切换栏
        TextView catTitle = new TextView(this);
        catTitle.setText("⚡ 选择风格 (共 " + allThemes.size() + " 款)：");
        catTitle.setTextSize(12);
        catTitle.setTextColor(textColorSecondary);
        catTitle.setPadding(0, (int) (8 * density), 0, (int) (4 * density));
        root.addView(catTitle);

        HorizontalScrollView hsvCat = new HorizontalScrollView(this);
        hsvCat.setOverScrollMode(View.OVER_SCROLL_NEVER);
        hsvCat.setHorizontalScrollBarEnabled(false);
        LinearLayout catContainer = new LinearLayout(this);
        catContainer.setOrientation(LinearLayout.HORIZONTAL);
        hsvCat.addView(catContainer);
        root.addView(hsvCat);

        // 3. 样式列表控件
        ListView listView = new ListView(this);
        listView.setDividerHeight(0);
        listView.setOverScrollMode(View.OVER_SCROLL_NEVER);
        LinearLayout.LayoutParams lvLp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            (int) (380 * density)
        );
        lvLp.topMargin = (int) (8 * density);
        listView.setLayoutParams(lvLp);
        root.addView(listView);

        AlertDialog dialog = new AlertDialog.Builder(this)
            .setTitle("⚡ 命令行样式工坊 (Prompt Engine)")
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
                    itemLayout.setPadding((int) (10 * density), (int) (8 * density), (int) (10 * density), (int) (8 * density));
                    itemLayout.setBackgroundResource(android.R.drawable.list_selector_background);

                    LinearLayout titleRow = new LinearLayout(TermuxStyleActivity.this);
                    titleRow.setOrientation(LinearLayout.HORIZONTAL);
                    titleRow.setGravity(Gravity.CENTER_VERTICAL);

                    TextView tvName = new TextView(TermuxStyleActivity.this);
                    tvName.setTextSize(14);
                    tvName.setTypeface(null, Typeface.BOLD);
                    tvName.setTextColor(textColorPrimary);
                    LinearLayout.LayoutParams np = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f);
                    tvName.setLayoutParams(np);

                    TextView tvCat = new TextView(TermuxStyleActivity.this);
                    tvCat.setTextSize(11);
                    tvCat.setTextColor(0xFF81D4FA);
                    tvCat.setPadding((int) (6 * density), (int) (2 * density), (int) (6 * density), (int) (2 * density));

                    titleRow.addView(tvName);
                    titleRow.addView(tvCat);
                    itemLayout.addView(titleRow);

                    // 终端真实微缩预览框
                    TextView previewBox = new TextView(TermuxStyleActivity.this);
                    previewBox.setTextSize(12);
                    previewBox.setTypeface(Typeface.MONOSPACE);
                    previewBox.setBackgroundColor(0xFF181818);
                    previewBox.setPadding((int) (10 * density), (int) (6 * density), (int) (10 * density), (int) (6 * density));
                    LinearLayout.LayoutParams pp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    );
                    pp.topMargin = (int) (4 * density);
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
                    // 微缩预览字符颜色随当前色彩选择联动！
                    holder.previewView.setTextColor(currentColorHolder[0].colorInt);
                }

                return itemLayout;
            }
        };

        listView.setAdapter(adapter);

        // 填充色卡药丸按钮
        final List<MaterialButton> colorButtons = new ArrayList<>();
        for (TermuxPromptManager.PromptColor c : colors) {
            MaterialButton btn = new MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle);
            btn.setText(c.displayName);
            btn.setTextSize(11);
            btn.setPadding((int) (8 * density), 0, (int) (8 * density), 0);
            btn.setCornerRadius((int) (16 * density));
            btn.setTextColor(c.colorInt);

            boolean isCurrent = c.id.equalsIgnoreCase(currentColorHolder[0].id);
            if (isCurrent) {
                btn.setStrokeWidth((int) (2 * density));
                btn.setStrokeColor(android.content.res.ColorStateList.valueOf(c.colorInt));
            } else {
                btn.setStrokeWidth((int) (1 * density));
                btn.setStrokeColor(android.content.res.ColorStateList.valueOf(textColorSecondary));
            }

            LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                (int) (32 * density)
            );
            bp.rightMargin = (int) (6 * density);
            btn.setLayoutParams(bp);

            btn.setOnClickListener(v -> {
                currentColorHolder[0] = c;
                for (MaterialButton other : colorButtons) {
                    other.setStrokeWidth((int) (1 * density));
                    other.setStrokeColor(android.content.res.ColorStateList.valueOf(textColorSecondary));
                }
                btn.setStrokeWidth((int) (2 * density));
                btn.setStrokeColor(android.content.res.ColorStateList.valueOf(c.colorInt));
                // 实时联动刷新下方列表的所有预览颜色！
                adapter.notifyDataSetChanged();
            });

            colorButtons.add(btn);
            colorContainer.addView(btn);
        }

        // 填充分类按钮
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

        // 单击列表项：直接以当前选中的颜色一步到位应用！
        listView.setOnItemClickListener((parent, view, position, id) -> {
            TermuxPromptManager.PromptTheme selectedTheme = displayList.get(position);
            TermuxPromptManager.PromptColor selectedColor = currentColorHolder[0];
            dialog.dismiss();
            applyThemeAndColor(selectedTheme, selectedColor);
        });

        dialog.show();
    }

    private void applyThemeAndColor(TermuxPromptManager.PromptTheme theme, TermuxPromptManager.PromptColor color) {
        TerminalSession session = TermuxActivity.sInstance != null ? TermuxActivity.sInstance.getCurrentSession() : null;
        boolean success = TermuxPromptManager.applyPromptStyle(this, session, theme.id, color.id);
        if (success) {
            updateCurrentState();
            TermuxStyleManager.sNeedReloadStyle = true;
            Toast.makeText(this, "已动态应用：" + theme.displayName + " · " + color.displayName + "（一步到位）", Toast.LENGTH_SHORT).show();
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
