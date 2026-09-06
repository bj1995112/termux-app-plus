package com.termux.app.ubuntu;

import android.content.Context;
import android.widget.Toast;

import com.termux.app.TermuxActivity;
import com.termux.terminal.TerminalSession;

import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public class UbuntuAppManager {

    private static final String UBUNTU_ROOTFS_PATH = "/data/data/com.termux/files/usr/var/lib/proot-distro/installed-rootfs/ubuntu";

    public static class AppItem {
        public final String id;
        public final String displayName;
        public final String command;

        public AppItem(String id, String displayName, String command) {
            this.id = id;
            this.displayName = displayName;
            this.command = command;
        }
    }

    // 核心知名 AI 编程软件与命令行 AI 智能体库
    private static final AppItem[] KNOWN_AI_APPS = new AppItem[]{
        new AppItem("codex", "🤖 Codex", "codex"),
        new AppItem("pi", "⚡ Pi", "pi"),
        new AppItem("opencode", "💻 OpenCode", "opencode"),
        new AppItem("claude", "🧠 Claude", "claude"),
        new AppItem("agy", "🚀 AGY", "agy"),
        new AppItem("aider", "🤝 Aider", "aider"),
        new AppItem("copilot", "✈️ Copilot", "copilot"),
        new AppItem("gh-copilot", "✈️ Copilot", "gh copilot"),
        new AppItem("cursor", "🎯 Cursor", "cursor"),
        new AppItem("gemini", "♊ Gemini", "gemini"),
        new AppItem("chatgpt", "💬 ChatGPT", "chatgpt"),
        new AppItem("sgpt", "🐚 SGPT", "sgpt"),
        new AppItem("interpreter", "🗣️ Interpreter", "interpreter"),
        new AppItem("open-interpreter", "🗣️ Interpreter", "open-interpreter"),
        new AppItem("plandex", "📋 Plandex", "plandex"),
        new AppItem("mentat", "🧬 Mentat", "mentat"),
        new AppItem("gpt-engineer", "⚙️ GPT-Eng", "gpt-engineer"),
        new AppItem("ollama", "🦙 Ollama", "ollama"),
        new AppItem("cody", "🔮 Cody", "cody"),
        new AppItem("tabby", "🐱 Tabby", "tabby"),
        new AppItem("continue", "⏩ Continue", "continue"),
        new AppItem("fabric", "🧵 Fabric", "fabric"),
        new AppItem("khoj", "🔍 Khoj", "khoj"),
        new AppItem("codeman", "📦 CodeMan", "codeman")
    };

    // AI 编程软件特征关键字（用于动态匹配用户自装的 AI 智能体/CLI 工具）
    private static final String[] AI_KEYWORDS = new String[]{
        "ai", "gpt", "claude", "agent", "code", "llm", "bot", "chat",
        "copilot", "gemini", "deepseek", "qwen", "ollama", "pi"
    };

    public static boolean isUbuntuInstalled() {
        File rootfs = new File(UBUNTU_ROOTFS_PATH);
        return rootfs.exists() && rootfs.isDirectory();
    }

    public static List<AppItem> getInstalledApps(Context context) {
        List<AppItem> result = new ArrayList<>();
        Set<String> addedIds = new HashSet<>();
        boolean hasUbuntu = isUbuntuInstalled();

        List<File> searchDirs = new ArrayList<>();
        List<File> userCustomDirs = new ArrayList<>();

        if (hasUbuntu) {
            File ubuntuRoot = new File(UBUNTU_ROOTFS_PATH);
            searchDirs.add(new File(ubuntuRoot, "usr/bin"));
            searchDirs.add(new File(ubuntuRoot, "usr/local/bin"));
            searchDirs.add(new File(ubuntuRoot, "root/.local/bin"));
            searchDirs.add(new File(ubuntuRoot, "root/.cargo/bin"));
            searchDirs.add(new File(ubuntuRoot, "root/.npm-global/bin"));
            searchDirs.add(new File(ubuntuRoot, "root/go/bin"));

            userCustomDirs.add(new File(ubuntuRoot, "root/.local/bin"));
            userCustomDirs.add(new File(ubuntuRoot, "root/.cargo/bin"));
            userCustomDirs.add(new File(ubuntuRoot, "root/.npm-global/bin"));
            userCustomDirs.add(new File(ubuntuRoot, "root/go/bin"));
            userCustomDirs.add(new File(ubuntuRoot, "usr/local/bin"));

            // 动态遍历 /home/* 目录下的个人 bin 目录
            File homeDir = new File(ubuntuRoot, "home");
            if (homeDir.exists() && homeDir.isDirectory()) {
                File[] userHomes = homeDir.listFiles();
                if (userHomes != null) {
                    for (File userHome : userHomes) {
                        if (userHome.isDirectory()) {
                            File uLocalBin = new File(userHome, ".local/bin");
                            searchDirs.add(uLocalBin);
                            userCustomDirs.add(uLocalBin);
                            File uCargoBin = new File(userHome, ".cargo/bin");
                            searchDirs.add(uCargoBin);
                            userCustomDirs.add(uCargoBin);
                        }
                    }
                }
            }
        }

        // 同时检查 Termux 本地安装目录
        File termuxFiles = context.getFilesDir();
        searchDirs.add(new File(termuxFiles, "usr/bin"));
        searchDirs.add(new File(termuxFiles, "home/.local/bin"));
        userCustomDirs.add(new File(termuxFiles, "home/.local/bin"));

        // 1. 扫描已知知名 AI 编程软件
        for (AppItem app : KNOWN_AI_APPS) {
            for (File dir : searchDirs) {
                if (!dir.exists() || !dir.isDirectory()) continue;
                File bin = new File(dir, app.id);
                if (bin.exists() && !bin.isDirectory()) {
                    if (!addedIds.contains(app.id) && !addedIds.contains(app.command)) {
                        result.add(app);
                        addedIds.add(app.id);
                        addedIds.add(app.command);
                    }
                    break;
                }
            }
        }

        // 2. 动态识别用户自行在私有目录中安装的 AI 编程软件（只匹配含 AI/编程智能体特征的文件）
        for (File dir : userCustomDirs) {
            if (!dir.exists() || !dir.isDirectory()) continue;
            File[] files = dir.listFiles();
            if (files != null) {
                for (File f : files) {
                    String name = f.getName();
                    if (!f.isFile() || name.startsWith(".")) continue;
                    if (addedIds.contains(name)) continue;

                    // 过滤掉非命令扩展名
                    if (name.endsWith(".pyc") || name.endsWith(".bak") || name.endsWith(".txt")) continue;

                    // 校验是否符合 AI 编程软件命名特征
                    String lower = name.toLowerCase(Locale.ROOT);
                    boolean isAiTool = false;
                    for (String kw : AI_KEYWORDS) {
                        if (lower.contains(kw)) {
                            isAiTool = true;
                            break;
                        }
                    }

                    if (isAiTool) {
                        result.add(new AppItem(name, "🤖 " + name, name));
                        addedIds.add(name);
                    }
                }
            }
        }

        return result;
    }

    public static void launchApp(TermuxActivity activity, AppItem app) {
        TerminalSession session = activity.getCurrentSession();
        if (session == null || !session.isRunning()) {
            Toast.makeText(activity, "请先启动一个终端会话", Toast.LENGTH_SHORT).show();
            return;
        }

        String execCmd;
        if (isUbuntuInstalled()) {
            // 穿透拉起 Ubuntu 容器内软件
            execCmd = "proot-distro login ubuntu -- " + app.command + "\n";
        } else {
            execCmd = app.command + "\n";
        }

        session.write(execCmd);
        activity.getDrawer().closeDrawers();
    }
}
