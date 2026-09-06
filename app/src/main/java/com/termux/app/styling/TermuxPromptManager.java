package com.termux.app.styling;

import android.content.Context;

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
 * 彻底打通 Termux 宿主沙盒与 proot-distro（Ubuntu 等）容器双端，实现多环境 100% 同步热加载。
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
     * 获取所有配置目标文件（多环境全量穿透：Termux 宿主、proot-distro Ubuntu 及所有已安装容器）
     */
    private static List<File> getAllTargetConfigFiles(Context context) {
        List<File> list = new ArrayList<>();
        File filesDir = context.getFilesDir();

        // 1. Termux 宿主路径
        File hostTermux = new File(filesDir, "home/.termux");
        list.add(new File(hostTermux, "prompt.conf"));

        File hostTwui = new File(filesDir, "home/.config/termux-webui");
        if (hostTwui.exists()) {
            list.add(new File(hostTwui, "prompt.conf"));
        }

        // 2. 扫描 proot-distro 所有容器 rootfs
        File containersDir = new File(filesDir, "usr/var/lib/proot-distro/containers");
        if (containersDir.exists() && containersDir.isDirectory()) {
            File[] distros = containersDir.listFiles();
            if (distros != null) {
                for (File d : distros) {
                    if (d.isDirectory()) {
                        File rootfs = new File(d, "rootfs");
                        if (rootfs.exists() && rootfs.isDirectory()) {
                            // 容器 root 用户路径
                            list.add(new File(rootfs, "root/.termux/prompt.conf"));
                            list.add(new File(rootfs, "root/.config/termux-webui/prompt.conf"));

                            // 容器普通用户路径（若有）
                            File guestHome = new File(rootfs, "home");
                            if (guestHome.exists() && guestHome.isDirectory()) {
                                File[] users = guestHome.listFiles();
                                if (users != null) {
                                    for (File u : users) {
                                        if (u.isDirectory()) {
                                            list.add(new File(u, ".termux/prompt.conf"));
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // 3. 宿主外层容错（当直接在 chroot/proot 或测试环境下运行时）
        File fallbackTermux = new File("/root/.termux/prompt.conf");
        if (!list.contains(fallbackTermux)) list.add(fallbackTermux);
        File fallbackTwui = new File("/root/.config/termux-webui/prompt.conf");
        if (!list.contains(fallbackTwui)) list.add(fallbackTwui);

        return list;
    }

    public static String getCurrentThemeId(Context context) {
        return readConfigValue(context, "theme", "hud");
    }

    public static String getCurrentColorId(Context context) {
        return readConfigValue(context, "color", "cyan");
    }

    private static String readConfigValue(Context context, String key, String defaultValue) {
        List<File> confFiles = getAllTargetConfigFiles(context);
        for (File conf : confFiles) {
            if (conf.exists() && conf.canRead()) {
                try (BufferedReader br = new BufferedReader(new FileReader(conf))) {
                    String line;
                    while ((line = br.readLine()) != null) {
                        line = line.trim();
                        if (line.startsWith(key + "=")) {
                            String val = line.substring(key.length() + 1).trim();
                            if ((val.startsWith("\"") && val.endsWith("\"")) || (val.startsWith("'") && val.endsWith("'"))) {
                                val = val.substring(1, val.length() - 1);
                            }
                            if (!val.isEmpty()) return val;
                        }
                    }
                } catch (Exception ignored) {
                }
            }
        }
        return defaultValue;
    }

    /**
     * 全量安装并覆盖所有环境下的 prompt 引擎脚本及挂钩
     */
    public static void ensureInstalled(Context context) {
        try {
            File filesDir = context.getFilesDir();

            // 1. 部署到 Termux 宿主 .termux/prompt.sh
            File hostHome = new File(filesDir, "home");
            File hostTermux = new File(hostHome, ".termux");
            if (!hostTermux.exists()) hostTermux.mkdirs();
            File hostScript = new File(hostTermux, "prompt.sh");
            copyAssetToFile(context, "styling/prompt/termux_prompt.sh", hostScript);

            // 部署到宿主 /usr/etc/profile.d/termux_prompt.sh
            File hostProfileD = new File(filesDir, "usr/etc/profile.d");
            if (hostProfileD.exists() && hostProfileD.isDirectory()) {
                File hostProfScript = new File(hostProfileD, "termux_prompt.sh");
                copyAssetToFile(context, "styling/prompt/termux_prompt.sh", hostProfScript);
            }

            // 安全挂钩宿主 ~/.bashrc（必须位于 exec proot-distro 之前！）
            File hostBashrc = new File(hostHome, ".bashrc");
            injectHookToHostBashrc(hostBashrc, "[ -f ~/.termux/prompt.sh ] && . ~/.termux/prompt.sh");

            // 2. 扫描并深度穿透 proot-distro 容器（如 Ubuntu）
            File containersDir = new File(filesDir, "usr/var/lib/proot-distro/containers");
            if (containersDir.exists() && containersDir.isDirectory()) {
                File[] distros = containersDir.listFiles();
                if (distros != null) {
                    for (File d : distros) {
                        if (d.isDirectory()) {
                            File rootfs = new File(d, "rootfs");
                            if (rootfs.exists() && rootfs.isDirectory()) {
                                // 关键：覆盖容器内部 /etc/profile.d/termux_prompt.sh！彻底消除旧版硬编码残留！
                                File cProfileD = new File(rootfs, "etc/profile.d");
                                if (cProfileD.exists() && cProfileD.isDirectory()) {
                                    File cProfScript = new File(cProfileD, "termux_prompt.sh");
                                    copyAssetToFile(context, "styling/prompt/termux_prompt.sh", cProfScript);
                                }

                                // 容器 root 家目录
                                File cRootTermux = new File(rootfs, "root/.termux");
                                if (!cRootTermux.exists()) cRootTermux.mkdirs();
                                File cRootScript = new File(cRootTermux, "prompt.sh");
                                copyAssetToFile(context, "styling/prompt/termux_prompt.sh", cRootScript);

                                // 确保容器 /root/.bashrc 拥有加载钩子
                                File cRootBashrc = new File(rootfs, "root/.bashrc");
                                if (cRootBashrc.exists()) {
                                    appendHookIfMissing(cRootBashrc, "[ -r \"$HOME/.termux/prompt.sh\" ] && . \"$HOME/.termux/prompt.sh\"");
                                }
                            }
                        }
                    }
                }
            }

            // 3. 容错部署（当前环境本身在容器内）
            File localProfileD = new File("/etc/profile.d/termux_prompt.sh");
            if (localProfileD.getParentFile() != null && localProfileD.getParentFile().exists()) {
                copyAssetToFile(context, "styling/prompt/termux_prompt.sh", localProfileD);
            }
            File localTermux = new File("/root/.termux");
            if (localTermux.exists()) {
                copyAssetToFile(context, "styling/prompt/termux_prompt.sh", new File(localTermux, "prompt.sh"));
            }

        } catch (Exception ignored) {
        }
    }

    private static void copyAssetToFile(Context context, String assetPath, File dest) {
        try {
            if (dest.getParentFile() != null && !dest.getParentFile().exists()) {
                dest.getParentFile().mkdirs();
            }
            try (InputStream in = context.getAssets().open(assetPath);
                 OutputStream out = new FileOutputStream(dest)) {
                byte[] buf = new byte[4096];
                int len;
                while ((len = in.read(buf)) > 0) {
                    out.write(buf, 0, len);
                }
            }
            dest.setReadable(true, false);
            dest.setExecutable(true, false);
        } catch (Exception ignored) {
        }
    }

    private static void injectHookToHostBashrc(File bashrc, String hookLine) {
        try {
            if (!bashrc.exists()) return;
            StringBuilder sb = new StringBuilder();
            boolean hasHook = false;
            try (BufferedReader br = new BufferedReader(new FileReader(bashrc))) {
                String line;
                while ((line = br.readLine()) != null) {
                    if (line.contains("termux/prompt.sh")) {
                        hasHook = true;
                    }
                    sb.append(line).append("\n");
                }
            }

            if (!hasHook) {
                String content = sb.toString();
                String execMarker = "exec proot-distro login";
                if (content.contains(execMarker)) {
                    int idx = content.indexOf(execMarker);
                    int lineStart = content.lastIndexOf("\n", idx);
                    if (lineStart == -1) lineStart = 0;
                    String newContent = content.substring(0, lineStart) + "\n\n# Termux+ Dynamic Prompt Engine\n" + hookLine + "\n" + content.substring(lineStart);
                    try (FileWriter fw = new FileWriter(bashrc, false)) {
                        fw.write(newContent);
                    }
                } else {
                    try (FileWriter fw = new FileWriter(bashrc, true)) {
                        fw.write("\n# Termux+ Dynamic Prompt Engine\n" + hookLine + "\n");
                    }
                }
            }
        } catch (Exception ignored) {
        }
    }

    private static void appendHookIfMissing(File rcFile, String hook) {
        try {
            if (!rcFile.exists()) return;
            boolean alreadyHooked = false;
            try (BufferedReader br = new BufferedReader(new FileReader(rcFile))) {
                String line;
                while ((line = br.readLine()) != null) {
                    if (line.contains("termux_prompt.sh") || line.contains(".termux/prompt.sh")) {
                        alreadyHooked = true;
                        break;
                    }
                }
            }

            if (!alreadyHooked) {
                try (FileWriter fw = new FileWriter(rcFile, true)) {
                    fw.write("\n# Termux+ Dynamic Prompt Engine\n" + hook + "\n");
                }
            }
        } catch (Exception ignored) {
        }
    }

    /**
     * 保存并在全环境同步应用样式
     */
    public static boolean applyPromptStyle(Context context, TerminalSession session, String themeId, String colorId) {
        try {
            ensureInstalled(context);

            String content = "theme=" + themeId + "\ncolor=" + colorId + "\n";

            List<File> targetFiles = getAllTargetConfigFiles(context);
            for (File conf : targetFiles) {
                try {
                    if (conf.getParentFile() != null && !conf.getParentFile().exists()) {
                        conf.getParentFile().mkdirs();
                    }
                    try (FileWriter fw = new FileWriter(conf, false)) {
                        fw.write(content);
                    }
                    conf.setReadable(true, false);
                } catch (Exception ignored) {
                }
            }

            // 动态触发前台终端重绘（发送绑定的无害转义键 \e[99~，触发 readline 原地重绘）
            if (session != null && session.isRunning()) {
                session.write("\033[99~");
            }

            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
