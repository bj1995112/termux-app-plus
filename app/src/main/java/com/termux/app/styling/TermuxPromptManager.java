package com.termux.app.styling;

import android.content.Context;
import androidx.annotation.NonNull;

import com.termux.terminal.TerminalSession;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Termux+ Dynamic Prompt Manager (Prompt Engine 2.0)
 * 采用“配置分离 + 零注入动态钩子 (No PTY Injection)”架构
 * 支持热加载即刻变色、跨环境（Termux 原生 & Ubuntu 容器）双端同步。
 */
public class TermuxPromptManager {

    public static class PromptTheme {
        public final String id;
        public final String displayName;
        public final String category;
        public final String previewText;

        public PromptTheme(String id, String displayName, String category, String previewText) {
            this.id = id;
            this.displayName = displayName;
            this.category = category;
            this.previewText = previewText;
        }
    }

    public static class PromptColor {
        public final String id;
        public final String displayName;
        public final int colorInt;

        public PromptColor(String id, String displayName, int colorInt) {
            this.id = id;
            this.displayName = displayName;
            this.colorInt = colorInt;
        }
    }

    public static final List<PromptTheme> THEMES = Arrays.asList(
        new PromptTheme("starship", "星际双行 (Starship 风格)", "现代双行", "┌──[~/workspace]\n└── ❯ "),
        new PromptTheme("hud", "HUD 科幻状态栏 (经典炫酷)", "科幻极客", "[root@localhost] ━➤ "),
        new PromptTheme("arrow", "优雅尾翼 (Arrow 单行)", "单行极简", "~/workspace ╰─➤ "),
        new PromptTheme("kali", "Kali 渗透 (经典黑客双行)", "现代双行", "┌──(termux㉿android)-[~]\n└─$ "),
        new PromptTheme("powerline", "Powerline 箭头 (极客胶囊)", "科幻极客", "user  ~  ❯ "),
        new PromptTheme("cyber", "赛博朋克 (重型霓虹)", "科幻极客", "━━━[~/workspace]━━➤ "),
        new PromptTheme("minimal", "极简单行 (成功绿/失败红)", "单行极简", "workspace ➜ "),
        new PromptTheme("ubuntu", "Ubuntu 官方经典 (原生感)", "官方经典", "root@localhost:~$ "),
        new PromptTheme("neon", "双行方括号 (Neon 极客)", "现代双行", "╭─[ termux@android ] - [ ~ ]\n╰──➤ "),
        new PromptTheme("default", "原生默认 (不作任何改写)", "原生默认", "（保持系统初始 PS1，不产生覆盖）")
    );

    public static final List<PromptColor> COLORS = Arrays.asList(
        new PromptColor("cyan", "赛博青 (Cyan)", 0xFF00E5FF),
        new PromptColor("green", "黑客绿 (Green)", 0xFF00E676),
        new PromptColor("blue", "冰川蓝 (Blue)", 0xFF40C4FF),
        new PromptColor("purple", "霓虹紫 (Purple)", 0xFFE040FB),
        new PromptColor("pink", "樱花粉 (Pink)", 0xFFFF4081),
        new PromptColor("yellow", "琥珀黄 (Yellow)", 0xFFFFD600),
        new PromptColor("orange", "晚霞橙 (Orange)", 0xFFFF6D00),
        new PromptColor("red", "热烈红 (Red)", 0xFFFF5252),
        new PromptColor("white", "纯净白 (White)", 0xFFFFFFFF)
    );

    public static List<String> getCategories() {
        List<String> categories = new ArrayList<>();
        categories.add("全部");
        for (PromptTheme theme : THEMES) {
            if (!categories.contains(theme.category)) {
                categories.add(theme.category);
            }
        }
        return categories;
    }

    public static PromptTheme getThemeById(String id) {
        for (PromptTheme t : THEMES) {
            if (t.id.equalsIgnoreCase(id)) return t;
        }
        return THEMES.get(0);
    }

    public static PromptColor getColorById(String id) {
        for (PromptColor c : COLORS) {
            if (c.id.equalsIgnoreCase(id)) return c;
        }
        return COLORS.get(0);
    }

    /**
     * 获取当前生效的主题 ID
     */
    public static String getCurrentThemeId(Context context) {
        return readConfigValue(context, "theme", "starship");
    }

    /**
     * 获取当前生效的色彩 ID
     */
    public static String getCurrentColorId(Context context) {
        return readConfigValue(context, "color", "cyan");
    }

    private static String readConfigValue(Context context, String key, String defaultValue) {
        File conf = getPrimaryConfigFile(context);
        if (!conf.exists() || !conf.canRead()) {
            // 尝试备用路径
            File backup = new File("/root/.config/termux-webui/prompt.conf");
            if (backup.exists() && backup.canRead()) {
                conf = backup;
            } else {
                return defaultValue;
            }
        }

        try (BufferedReader br = new BufferedReader(new FileReader(conf))) {
            String line;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (line.startsWith(key + "=")) {
                    String val = line.substring(key.length() + 1).trim();
                    // 去除可能存在的单双引号
                    if ((val.startsWith("\"") && val.endsWith("\"")) || (val.startsWith("'") && val.endsWith("'"))) {
                        val = val.substring(1, val.length() - 1);
                    }
                    return val;
                }
            }
        } catch (Exception ignored) {
        }
        return defaultValue;
    }

    private static File getPrimaryConfigFile(Context context) {
        File home = new File(context.getFilesDir(), "home");
        File termuxDir = new File(home, ".termux");
        if (!termuxDir.exists()) termuxDir.mkdirs();
        return new File(termuxDir, "prompt.conf");
    }

    /**
     * 安装与同步核心 shell 动态引擎脚本到系统
     */
    public static void ensureInstalled(Context context) {
        try {
            File home = new File(context.getFilesDir(), "home");
            File termuxDir = new File(home, ".termux");
            if (!termuxDir.exists()) termuxDir.mkdirs();

            File targetScript = new File(termuxDir, "prompt.sh");
            // 写入 assets 中的 termux_prompt.sh
            try (InputStream in = context.getAssets().open("styling/prompt/termux_prompt.sh");
                 OutputStream out = new FileOutputStream(targetScript)) {
                byte[] buf = new byte[4096];
                int len;
                while ((len = in.read(buf)) > 0) {
                    out.write(buf, 0, len);
                }
            }
            targetScript.setReadable(true, false);
            targetScript.setExecutable(true, false);

            // 同时部署到 /data/data/com.termux/files/usr/etc/profile.d/termux_prompt.sh（如果目录存在）
            File profileD = new File(context.getFilesDir(), "usr/etc/profile.d");
            if (profileD.exists() && profileD.canWrite()) {
                File profScript = new File(profileD, "termux_prompt.sh");
                try (InputStream in = context.getAssets().open("styling/prompt/termux_prompt.sh");
                     OutputStream out = new FileOutputStream(profScript)) {
                    byte[] buf = new byte[4096];
                    int len;
                    while ((len = in.read(buf)) > 0) {
                        out.write(buf, 0, len);
                    }
                }
                profScript.setReadable(true, false);
                profScript.setExecutable(true, false);
            }

            // 安全挂钩 ~/.bashrc
            File bashrc = new File(home, ".bashrc");
            appendHookIfMissing(bashrc, targetScript.getAbsolutePath());

            // 跨环境支持：若当前检测到 /root/.bashrc，也注入挂钩
            File rootBashrc = new File("/root/.bashrc");
            if (rootBashrc.exists() && rootBashrc.canWrite()) {
                appendHookIfMissing(rootBashrc, targetScript.getAbsolutePath());
            }

        } catch (Exception ignored) {
        }
    }

    private static void appendHookIfMissing(File rcFile, String scriptPath) {
        try {
            boolean alreadyHooked = false;
            if (rcFile.exists()) {
                try (BufferedReader br = new BufferedReader(new FileReader(rcFile))) {
                    String line;
                    while ((line = br.readLine()) != null) {
                        if (line.contains("termux_prompt.sh") || line.contains(".termux/prompt.sh")) {
                            alreadyHooked = true;
                            break;
                        }
                    }
                }
            }

            if (!alreadyHooked) {
                try (FileWriter fw = new FileWriter(rcFile, true)) {
                    fw.write("\n# Termux+ Dynamic Prompt Engine\n");
                    fw.write("[ -r \"" + scriptPath + "\" ] && . \"" + scriptPath + "\"\n");
                }
            }
        } catch (Exception ignored) {
        }
    }

    /**
     * 保存并动态应用样式
     */
    public static boolean applyPromptStyle(Context context, TerminalSession session, String themeId, String colorId) {
        try {
            ensureInstalled(context);

            String content = "theme=" + themeId + "\ncolor=" + colorId + "\n";

            // 1. 写入 Termux 宿主主配置文件
            File mainConf = getPrimaryConfigFile(context);
            try (FileWriter fw = new FileWriter(mainConf, false)) {
                fw.write(content);
            }

            // 2. 双环境支持：如果存在 /root/.config/termux-webui/，同步写入兼容
            File twuiDir = new File("/root/.config/termux-webui");
            if (twuiDir.exists() && twuiDir.canWrite()) {
                File twuiConf = new File(twuiDir, "prompt.conf");
                try (FileWriter fw = new FileWriter(twuiConf, false)) {
                    fw.write(content);
                }
            }

            // 3. 动态触发前台终端重绘（零命令注入，仅发送不可见 Readline 刷新热键 \e[99~）
            if (session != null && session.isRunning()) {
                // 发送绑定的安全转义字符，通知 bash/readline 毫秒级原地重绘
                session.write("\033[99~");
            }

            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
