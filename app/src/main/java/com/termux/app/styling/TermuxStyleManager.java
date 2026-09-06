package com.termux.app.styling;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.util.AtomicFile;

import androidx.preference.PreferenceManager;

import com.termux.shared.logger.Logger;
import com.termux.shared.termux.TermuxConstants;
import com.termux.shared.termux.TermuxConstants.TERMUX_APP.TERMUX_ACTIVITY;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

public class TermuxStyleManager {

    private static final String LOG_TAG = "TermuxStyleManager";
    public static final String DEFAULT_NAME = "Default";
    public static final String PREF_DRAWER_ADAPT_THEME = "pref_drawer_adapt_theme";

    // 静态标志位，用于跨 Activity 准确通知 TermuxActivity 立即热重载
    public static volatile boolean sNeedReloadStyle = false;

    // 配色方案中文名称映射表
    private static final Map<String, String> COLOR_ZH_MAP = new HashMap<>();
    // 字体中文名称映射表
    private static final Map<String, String> FONT_ZH_MAP = new HashMap<>();

    static {
        // --- 核心流行主题中文映射 ---
        COLOR_ZH_MAP.put("dracula", "德古拉暗黑 (Dracula)");
        COLOR_ZH_MAP.put("nord", "极光北欧 (Nord)");
        COLOR_ZH_MAP.put("gruvbox-dark", "复古暖棕深色 (Gruvbox Dark)");
        COLOR_ZH_MAP.put("gruvbox-light", "复古暖棕浅色 (Gruvbox Light)");
        COLOR_ZH_MAP.put("gruvbox-material-dark-hard", "复古质感纯黑 (Gruvbox Hard)");
        COLOR_ZH_MAP.put("gruvbox-material-dark-medium", "复古质感中黑 (Gruvbox Medium)");
        COLOR_ZH_MAP.put("gruvbox-material-dark-soft", "复古质感柔黑 (Gruvbox Soft)");
        COLOR_ZH_MAP.put("gruvbox-material-light-hard", "复古质感明亮 (Gruvbox Light Hard)");
        COLOR_ZH_MAP.put("gruvbox-material-light-medium", "复古质感柔和浅色 (Gruvbox Light Med)");
        COLOR_ZH_MAP.put("gruvbox-material-light-soft", "复古质感米黄 (Gruvbox Light Soft)");
        COLOR_ZH_MAP.put("catppuccin-mocha", "柔和摩卡暗黑 (Catppuccin Mocha)");
        COLOR_ZH_MAP.put("catppuccin-macchiato", "柔和玛奇朵 (Catppuccin Macchiato)");
        COLOR_ZH_MAP.put("catppuccin-frappe", "柔和法布奇诺 (Catppuccin Frappé)");
        COLOR_ZH_MAP.put("catppuccin-latte", "柔和拿铁明亮 (Catppuccin Latte)");
        COLOR_ZH_MAP.put("tokyonight-dark", "东京之夜深色 (Tokyo Night Dark)");
        COLOR_ZH_MAP.put("tokyonight-day", "东京之日浅色 (Tokyo Night Day)");
        COLOR_ZH_MAP.put("solarized-dark", "经典日晒暗蓝 (Solarized Dark)");
        COLOR_ZH_MAP.put("solarized-light", "经典日晒米白 (Solarized Light)");
        COLOR_ZH_MAP.put("material", "质感设计暗色 (Material)");
        COLOR_ZH_MAP.put("base16-one-dark", "原子暗黑 (Atom One Dark)");
        COLOR_ZH_MAP.put("base16-one-light", "原子明亮 (Atom One Light)");
        COLOR_ZH_MAP.put("tomorrow-night", "明日之夜 (Tomorrow Night)");
        COLOR_ZH_MAP.put("base16-tomorrow-dark", "明日深色 (Tomorrow Dark)");
        COLOR_ZH_MAP.put("base16-tomorrow-light", "明日浅色 (Tomorrow Light)");
        COLOR_ZH_MAP.put("base16-monokai-dark", "经典莫诺卡 (Monokai Dark)");
        COLOR_ZH_MAP.put("rosé-pine", "玫瑰松木暗色 (Rosé Pine)");
        COLOR_ZH_MAP.put("rosé-pine-moon", "玫瑰松木月夜 (Rosé Pine Moon)");
        COLOR_ZH_MAP.put("rosé-pine-dawn", "玫瑰松木拂晓 (Rosé Pine Dawn)");
        COLOR_ZH_MAP.put("iceberg", "冰山极简冷调 (Iceberg)");
        COLOR_ZH_MAP.put("gotham", "哥谭黑夜 (Gotham)");
        COLOR_ZH_MAP.put("ubuntu", "乌班图经典紫黑 (Ubuntu)");
        COLOR_ZH_MAP.put("white-on-black", "纯黑背景白字 (White on Black)");
        COLOR_ZH_MAP.put("black-on-white", "纯白背景黑字 (Black on White)");
        COLOR_ZH_MAP.put("e-ink", "水墨屏黑白护眼 (E-Ink)");
        COLOR_ZH_MAP.put("e-ink-color", "水墨屏彩色护眼 (E-Ink Color)");
        COLOR_ZH_MAP.put("neon", "赛博霓虹 (Neon)");
        COLOR_ZH_MAP.put("snazzy", "炫彩极客黑 (Snazzy)");
        COLOR_ZH_MAP.put("base16-snazzy", "炫彩极客黑 (Base16 Snazzy)");
        COLOR_ZH_MAP.put("spacemacs", "太空宏编辑器 (Spacemacs)");
        COLOR_ZH_MAP.put("zenburn", "禅意低对比护眼 (Zenburn)");
        COLOR_ZH_MAP.put("argonaut", "阿耳戈深海蓝 (Argonaut)");
        COLOR_ZH_MAP.put("wild-cherry", "狂野樱桃粉紫 (Wild Cherry)");
        COLOR_ZH_MAP.put("gnometerm-new", "GNOME 终端新版 (GNOME New)");
        COLOR_ZH_MAP.put("gnometerm", "GNOME 终端经典 (GNOME Classic)");
        COLOR_ZH_MAP.put("nancy", "南希柔和色调 (Nancy)");
        COLOR_ZH_MAP.put("rydgel", "锐德吉尔冷灰 (Rydgel)");
        COLOR_ZH_MAP.put("smyck", "斯密克高对比 (Smyck)");

        // Base16 系列
        COLOR_ZH_MAP.put("base16-default-dark", "Base16 默认深色 (Default Dark)");
        COLOR_ZH_MAP.put("base16-default-light", "Base16 默认浅色 (Default Light)");
        COLOR_ZH_MAP.put("base16-google-dark", "Base16 谷歌四色暗色 (Google Dark)");
        COLOR_ZH_MAP.put("base16-google-light", "Base16 谷歌四色明亮 (Google Light)");
        COLOR_ZH_MAP.put("base16-github", "Base16 代码托管风 (GitHub)");
        COLOR_ZH_MAP.put("base16-grayscale-dark", "Base16 纯灰度深色 (Grayscale Dark)");
        COLOR_ZH_MAP.put("base16-grayscale-light", "Base16 纯灰度浅色 (Grayscale Light)");
        COLOR_ZH_MAP.put("base16-greenscreen-dark", "Base16 经典绿屏复古 (Green Screen)");
        COLOR_ZH_MAP.put("base16-greenscreen-light", "Base16 绿屏浅色 (Green Light)");
        COLOR_ZH_MAP.put("base16-ocean-dark", "Base16 大洋深蓝暗色 (Ocean Dark)");
        COLOR_ZH_MAP.put("base16-ocean-light", "Base16 大洋海风明亮 (Ocean Light)");
        COLOR_ZH_MAP.put("base16-flat-dark", "Base16 扁平质感暗色 (Flat Dark)");
        COLOR_ZH_MAP.put("base16-flat-light", "Base16 扁平质感明亮 (Flat Light)");
        COLOR_ZH_MAP.put("base16-eighties-dark", "Base16 八十年代复古 (Eighties Dark)");
        COLOR_ZH_MAP.put("base16-eighties-light", "Base16 八十年代明亮 (Eighties Light)");
        COLOR_ZH_MAP.put("base16-chalk-dark", "Base16 粉笔黑板深色 (Chalk Dark)");
        COLOR_ZH_MAP.put("base16-chalk-light", "Base16 粉笔灰白浅色 (Chalk Light)");
        COLOR_ZH_MAP.put("base16-codeschool-dark", "Base16 编程学院深色 (CodeSchool Dark)");
        COLOR_ZH_MAP.put("base16-codeschool-light", "Base16 编程学院浅色 (CodeSchool Light)");
        COLOR_ZH_MAP.put("base16-railscasts-dark", "Base16 经典导轨深色 (Railscasts Dark)");
        COLOR_ZH_MAP.put("base16-railscasts-light", "Base16 经典导轨浅色 (Railscasts Light)");
        COLOR_ZH_MAP.put("base16-twilight-dark", "Base16 暮光之城暗色 (Twilight Dark)");
        COLOR_ZH_MAP.put("base16-twilight-light", "Base16 暮光浅色 (Twilight Light)");

        // --- 字体中文映射 ---
        FONT_ZH_MAP.put("JetBrains-Mono", "捷脑极客编程体 (JetBrains Mono)");
        FONT_ZH_MAP.put("Fira-Code", "连字符号首选 (Fira Code)");
        FONT_ZH_MAP.put("Hack", "黑客开源等宽 (Hack)");
        FONT_ZH_MAP.put("CascadiaCode", "微软终端等宽体 (Cascadia Code)");
        FONT_ZH_MAP.put("Source-Code-Pro", "Adobe 源码专业等宽 (Source Code Pro)");
        FONT_ZH_MAP.put("Roboto-Mono", "谷歌安卓原生等宽 (Roboto Mono)");
        FONT_ZH_MAP.put("Inconsolata", "清晰紧凑印刷体 (Inconsolata)");
        FONT_ZH_MAP.put("Ubuntu-Mono", "乌班图系统等宽 (Ubuntu Mono)");
        FONT_ZH_MAP.put("DejaVu-Sans-Mono", "经典通用无衬线等宽 (DejaVu Sans)");
        FONT_ZH_MAP.put("Iosevka", "纤细高密度等宽 (Iosevka)");
        FONT_ZH_MAP.put("Fira-Mono", "火狐无连字等宽 (Fira Mono)");
        FONT_ZH_MAP.put("Go-Mono", "谷歌 Go 官方等宽 (Go Mono)");
        FONT_ZH_MAP.put("Terminus", "终端经典点阵等宽 (Terminus)");
        FONT_ZH_MAP.put("Anonymous-Pro", "匿名极客代码体 (Anonymous Pro)");
        FONT_ZH_MAP.put("Courier-Prime", "打字机复古代码体 (Courier Prime)");
        FONT_ZH_MAP.put("D2-Coding", "D2 编程清晰等宽 (D2 Coding)");
        FONT_ZH_MAP.put("Fantasque-Sans-Mono", "俏皮手写灵动等宽 (Fantasque)");
        FONT_ZH_MAP.put("Hermit", "程序员隐士代码体 (Hermit)");
        FONT_ZH_MAP.put("Liberation-Mono", "自由开源标准等宽 (Liberation Mono)");
        FONT_ZH_MAP.put("Meslo", "经典终端微调等宽 (Meslo)");
        FONT_ZH_MAP.put("Monofur", "圆润复古艺术等宽 (Monofur)");
        FONT_ZH_MAP.put("Monoid", "超紧凑像素优化体 (Monoid)");
        FONT_ZH_MAP.put("Victor-Mono", "斜体艺术连字编程体 (Victor Mono)");
        FONT_ZH_MAP.put("Bedstead-Condensed", "经典窄体复古像素 (Bedstead)");
        FONT_ZH_MAP.put("OpenDyslexic", "阅读障碍友好体 (OpenDyslexic)");
        FONT_ZH_MAP.put("GNU-FreeFont", "GNU 自由开源等宽 (GNU FreeFont)");
    }

    public static class StyleItem implements Comparable<StyleItem> {
        public final String fileName;
        public final String displayName;

        public StyleItem(String fileName, boolean isColor) {
            this.fileName = fileName;
            if (DEFAULT_NAME.equalsIgnoreCase(fileName)) {
                this.displayName = "默认系统原生 (Default)";
            } else {
                String rawName = fileName;
                int dotIndex = rawName.lastIndexOf('.');
                if (dotIndex != -1) {
                    rawName = rawName.substring(0, dotIndex);
                }

                if (isColor) {
                    if (COLOR_ZH_MAP.containsKey(rawName)) {
                        this.displayName = COLOR_ZH_MAP.get(rawName);
                    } else {
                        this.displayName = formatFallbackColorName(rawName);
                    }
                } else {
                    if (FONT_ZH_MAP.containsKey(rawName)) {
                        this.displayName = FONT_ZH_MAP.get(rawName);
                    } else {
                        this.displayName = capitalize(rawName.replace('-', ' ')) + " 等宽字体";
                    }
                }
            }
        }

        private static String formatFallbackColorName(String name) {
            if (name.startsWith("base16-")) {
                String sub = name.substring(7);
                boolean isDark = sub.endsWith("-dark");
                boolean isLight = sub.endsWith("-light");
                String mainPart = sub.replaceAll("-(dark|light)$", "");
                String modeStr = isDark ? "暗色" : (isLight ? "浅色" : "");
                return "Base16: " + capitalize(mainPart.replace('-', ' ')) + " " + modeStr + " (" + name + ")";
            }
            return capitalize(name.replace('-', ' ')) + " 主题 (" + name + ")";
        }

        private static String capitalize(String str) {
            boolean lastWhitespace = true;
            char[] chars = str.toCharArray();
            for (int i = 0; i < chars.length; i++) {
                if (Character.isLetter(chars[i])) {
                    if (lastWhitespace) {
                        chars[i] = Character.toUpperCase(chars[i]);
                    }
                    lastWhitespace = false;
                } else {
                    lastWhitespace = Character.isWhitespace(chars[i]);
                }
            }
            return new String(chars);
        }

        @Override
        public String toString() {
            return displayName;
        }

        @Override
        public int compareTo(StyleItem o) {
            if (DEFAULT_NAME.equalsIgnoreCase(this.fileName)) return -1;
            if (DEFAULT_NAME.equalsIgnoreCase(o.fileName)) return 1;
            return this.displayName.compareToIgnoreCase(o.displayName);
        }
    }

    public static List<StyleItem> getColorSchemes(Context context) {
        List<StyleItem> list = new ArrayList<>();
        list.add(new StyleItem(DEFAULT_NAME, true));
        try {
            String[] files = context.getAssets().list("styling/colors");
            if (files != null) {
                for (String file : files) {
                    if (file.endsWith(".properties")) {
                        list.add(new StyleItem(file, true));
                    }
                }
            }
        } catch (IOException e) {
            Logger.logError(LOG_TAG, "Failed to list color assets: " + e.getMessage());
        }
        Collections.sort(list);
        return list;
    }

    public static List<StyleItem> getFonts(Context context) {
        List<StyleItem> list = new ArrayList<>();
        list.add(new StyleItem(DEFAULT_NAME, false));
        try {
            String[] files = context.getAssets().list("styling/fonts");
            if (files != null) {
                for (String file : files) {
                    if (file.endsWith(".ttf") || file.endsWith(".otf")) {
                        list.add(new StyleItem(file, false));
                    }
                }
            }
        } catch (IOException e) {
            Logger.logError(LOG_TAG, "Failed to list font assets: " + e.getMessage());
        }
        Collections.sort(list);
        return list;
    }

    public static boolean applyColorScheme(Context context, String fileName) {
        boolean ok = copyAssetToTermuxDir(context, "styling/colors", fileName, "colors.properties", true);
        if (ok) {
            sNeedReloadStyle = true;
        }
        return ok;
    }

    public static boolean applyFont(Context context, String fileName) {
        boolean ok = copyAssetToTermuxDir(context, "styling/fonts", fileName, "font.ttf", false);
        if (ok) {
            sNeedReloadStyle = true;
        }
        return ok;
    }

    public static boolean resetToDefault(Context context) {
        boolean c = applyColorScheme(context, DEFAULT_NAME);
        boolean f = applyFont(context, DEFAULT_NAME);
        if (c && f) {
            sNeedReloadStyle = true;
        }
        return c && f;
    }

    private static boolean copyAssetToTermuxDir(Context context, String assetFolder, String fileName, String targetFileName, boolean isColor) {
        try {
            File homeDir = new File(context.getFilesDir(), "home");
            File termuxDir = new File(homeDir, ".termux");
            if (!termuxDir.isDirectory() && !termuxDir.mkdirs()) {
                Logger.logError(LOG_TAG, "Cannot create directory: " + termuxDir.getAbsolutePath());
                return false;
            }

            File destinationFile = new File(termuxDir, targetFileName).getCanonicalFile();
            destinationFile.setWritable(true);
            if (destinationFile.getParentFile() != null) {
                destinationFile.getParentFile().setWritable(true);
                destinationFile.getParentFile().setExecutable(true);
            }

            AtomicFile atomicFile = new AtomicFile(destinationFile);
            FileOutputStream out = atomicFile.startWrite();

            if (DEFAULT_NAME.equalsIgnoreCase(fileName)) {
                if (isColor) {
                    byte[] comment = "# Using default color theme.\n".getBytes(StandardCharsets.UTF_8);
                    out.write(comment);
                } else {
                    // Empty for default font
                }
            } else {
                try (InputStream in = context.getAssets().open(assetFolder + "/" + fileName)) {
                    byte[] buffer = new byte[4096];
                    int len;
                    while ((len = in.read(buffer)) > 0) {
                        out.write(buffer, 0, len);
                    }
                }
            }
            atomicFile.finishWrite(out);

            notifyReloadStyle(context, isColor);
            return true;
        } catch (Exception e) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Failed to write " + targetFileName, e);
            return false;
        }
    }

    public static void notifyReloadStyle(Context context, boolean isColor) {
        Intent reloadIntent = new Intent(TermuxConstants.TERMUX_APP.TERMUX_ACTIVITY.ACTION_RELOAD_STYLE);
        reloadIntent.putExtra(TermuxConstants.TERMUX_APP.TERMUX_ACTIVITY.ACTION_RELOAD_STYLE, isColor ? "colors" : "font");
        reloadIntent.putExtra(TermuxConstants.TERMUX_APP.TERMUX_ACTIVITY.EXTRA_RECREATE_ACTIVITY, false);
        context.sendBroadcast(reloadIntent);
    }

    public static boolean isDrawerThemeAdaptEnabled(Context context) {
        SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences(context);
        return sp.getBoolean(PREF_DRAWER_ADAPT_THEME, false); // 默认不开启
    }

    public static void setDrawerThemeAdaptEnabled(Context context, boolean enabled) {
        SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences(context);
        sp.edit().putBoolean(PREF_DRAWER_ADAPT_THEME, enabled).apply();
    }

    public static int[] getTerminalCurrentColors(Context context) {
        // [0]=background, [1]=foreground, [2]=accent
        int[] colors = new int[]{Color.parseColor("#1E1E1E"), Color.parseColor("#FFFFFF"), Color.parseColor("#2196F3")};
        try {
            File colorsFile = new File(new File(context.getFilesDir(), "home/.termux"), "colors.properties");
            if (colorsFile.exists()) {
                Properties props = new Properties();
                try (InputStream in = new FileInputStream(colorsFile)) {
                    props.load(in);
                }
                String bg = props.getProperty("background");
                String fg = props.getProperty("foreground");
                String accent = props.getProperty("color4", props.getProperty("color12", "#2196F3"));
                if (bg != null) colors[0] = Color.parseColor(bg.trim());
                if (fg != null) colors[1] = Color.parseColor(fg.trim());
                if (accent != null) colors[2] = Color.parseColor(accent.trim());
            }
        } catch (Exception ignored) {
        }
        return colors;
    }

    // --- 收藏功能支持 ---
    public static final String PREF_FAVORITE_COLORS = "pref_favorite_colors";
    public static final String PREF_FAVORITE_FONTS = "pref_favorite_fonts";

    public static Set<String> getFavoriteColors(Context context) {
        SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences(context);
        return new HashSet<>(sp.getStringSet(PREF_FAVORITE_COLORS, Collections.emptySet()));
    }

    public static boolean toggleFavoriteColor(Context context, String fileName) {
        SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences(context);
        Set<String> set = new HashSet<>(sp.getStringSet(PREF_FAVORITE_COLORS, Collections.emptySet()));
        boolean isFav;
        if (set.contains(fileName)) {
            set.remove(fileName);
            isFav = false;
        } else {
            set.add(fileName);
            isFav = true;
        }
        sp.edit().putStringSet(PREF_FAVORITE_COLORS, set).apply();
        return isFav;
    }

    public static boolean isFavoriteColor(Context context, String fileName) {
        SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences(context);
        Set<String> set = sp.getStringSet(PREF_FAVORITE_COLORS, Collections.emptySet());
        return set != null && set.contains(fileName);
    }

    public static Set<String> getFavoriteFonts(Context context) {
        SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences(context);
        return new HashSet<>(sp.getStringSet(PREF_FAVORITE_FONTS, Collections.emptySet()));
    }

    public static boolean toggleFavoriteFont(Context context, String fileName) {
        SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences(context);
        Set<String> set = new HashSet<>(sp.getStringSet(PREF_FAVORITE_FONTS, Collections.emptySet()));
        boolean isFav;
        if (set.contains(fileName)) {
            set.remove(fileName);
            isFav = false;
        } else {
            set.add(fileName);
            isFav = true;
        }
        sp.edit().putStringSet(PREF_FAVORITE_FONTS, set).apply();
        return isFav;
    }

    public static boolean isFavoriteFont(Context context, String fileName) {
        SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences(context);
        Set<String> set = sp.getStringSet(PREF_FAVORITE_FONTS, Collections.emptySet());
        return set != null && set.contains(fileName);
    }

    public static void sortItemsWithFavorites(List<StyleItem> list, Set<String> favs) {
        Collections.sort(list, (a, b) -> {
            if (DEFAULT_NAME.equalsIgnoreCase(a.fileName)) return -1;
            if (DEFAULT_NAME.equalsIgnoreCase(b.fileName)) return 1;
            boolean aFav = favs != null && favs.contains(a.fileName);
            boolean bFav = favs != null && favs.contains(b.fileName);
            if (aFav != bFav) {
                return aFav ? -1 : 1; // 收藏的排在最前
            }
            return a.displayName.compareToIgnoreCase(b.displayName);
        });
    }
}
