package com.termux.app.ubuntu;

import android.content.Context;
import android.widget.Toast;

import com.termux.app.TermuxActivity;
import com.termux.terminal.TerminalSession;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public class UbuntuAppManager {

    private static final String ROOTFS_BASE_PATH = "/data/data/com.termux/files/usr/var/lib/proot-distro/installed-rootfs";
    private static String sDefaultDistroName = "ubuntu";

    public static class AppItem {
        public final String id;
        public final String displayName;
        public final String command;
        public final String distroName; // null 表示 Termux 本地

        public AppItem(String id, String displayName, String command, String distroName) {
            this.id = id;
            this.displayName = displayName;
            this.command = command;
            this.distroName = distroName;
        }
    }

    // 核心知名 AI 编程软件与命令行 AI 智能体库
    private static final AppItem[] KNOWN_AI_APPS = new AppItem[]{
        new AppItem("codex", "🤖 Codex", "codex", null),
        new AppItem("pi", "⚡ Pi", "pi", null),
        new AppItem("opencode", "💻 OpenCode", "opencode", null),
        new AppItem("claude", "🧠 Claude", "claude", null),
        new AppItem("agy", "🚀 AGY", "agy", null),
        new AppItem("aider", "🤝 Aider", "aider", null),
        new AppItem("copilot", "✈️ Copilot", "copilot", null),
        new AppItem("gh-copilot", "✈️ Copilot", "gh copilot", null),
        new AppItem("cursor", "🎯 Cursor", "cursor", null),
        new AppItem("gemini", "♊ Gemini", "gemini", null),
        new AppItem("chatgpt", "💬 ChatGPT", "chatgpt", null),
        new AppItem("sgpt", "🐚 SGPT", "sgpt", null),
        new AppItem("interpreter", "🗣️ Interpreter", "interpreter", null),
        new AppItem("open-interpreter", "🗣️ Interpreter", "open-interpreter", null),
        new AppItem("plandex", "📋 Plandex", "plandex", null),
        new AppItem("mentat", "🧬 Mentat", "mentat", null),
        new AppItem("gpt-engineer", "⚙️ GPT-Eng", "gpt-engineer", null),
        new AppItem("ollama", "🦙 Ollama", "ollama", null),
        new AppItem("cody", "🔮 Cody", "cody", null),
        new AppItem("tabby", "🐱 Tabby", "tabby", null),
        new AppItem("continue", "⏩ Continue", "continue", null),
        new AppItem("fabric", "🧵 Fabric", "fabric", null),
        new AppItem("khoj", "🔍 Khoj", "khoj", null),
        new AppItem("codeman", "📦 CodeMan", "codeman", null)
    };

    // AI 编程软件特征关键字（用于动态匹配用户自装的 AI 智能体/CLI 工具）
    private static final String[] AI_KEYWORDS = new String[]{
        "ai", "gpt", "claude", "agent", "code", "llm", "bot", "chat",
        "copilot", "gemini", "deepseek", "qwen", "ollama", "pi", "openai"
    };

    public static List<File> getInstalledDistroRoots() {
        List<File> distros = new ArrayList<>();
        File base = new File(ROOTFS_BASE_PATH);
        if (base.exists() && base.isDirectory()) {
            File[] files = base.listFiles();
            if (files != null) {
                for (File f : files) {
                    if (f.isDirectory()) {
                        distros.add(f);
                        if (sDefaultDistroName == null || sDefaultDistroName.equals("ubuntu")) {
                            sDefaultDistroName = f.getName();
                        }
                    }
                }
            }
        }
        return distros;
    }

    public static boolean isProotDistroInstalled() {
        return !getInstalledDistroRoots().isEmpty();
    }

    public static List<AppItem> getInstalledApps(Context context) {
        List<AppItem> result = new ArrayList<>();
        Set<String> addedIds = new HashSet<>();

        // 1. 探测 proot-distro 容器
        List<File> distroRoots = getInstalledDistroRoots();
        for (File rootfs : distroRoots) {
            String distro = rootfs.getName();
            List<File> dirs = new ArrayList<>();
            dirs.add(new File(rootfs, "usr/bin"));
            dirs.add(new File(rootfs, "usr/local/bin"));
            dirs.add(new File(rootfs, "root/.local/bin"));
            dirs.add(new File(rootfs, "root/.cargo/bin"));
            dirs.add(new File(rootfs, "root/.npm-global/bin"));
            dirs.add(new File(rootfs, "root/go/bin"));

            File home = new File(rootfs, "home");
            if (home.exists() && home.isDirectory()) {
                File[] uHomes = home.listFiles();
                if (uHomes != null) {
                    for (File u : uHomes) {
                        if (u.isDirectory()) {
                            dirs.add(new File(u, ".local/bin"));
                            dirs.add(new File(u, ".cargo/bin"));
                            dirs.add(new File(u, ".npm-global/bin"));
                        }
                    }
                }
            }

            scanDirectoriesForApps(dirs, distro, result, addedIds);
        }

        // 2. 探测 Termux 本地环境
        File filesDir = context.getFilesDir();
        List<File> termuxDirs = new ArrayList<>();
        termuxDirs.add(new File(filesDir, "usr/bin"));
        termuxDirs.add(new File(filesDir, "home/.local/bin"));
        termuxDirs.add(new File(filesDir, "home/.cargo/bin"));
        scanDirectoriesForApps(termuxDirs, null, result, addedIds);

        return result;
    }

    private static void scanDirectoriesForApps(List<File> dirs, String distro, List<AppItem> result, Set<String> addedIds) {
        for (File dir : dirs) {
            if (!dir.exists() || !dir.isDirectory()) continue;

            // 使用 list() 获取目录所有直属文件/软链接名，绝不漏掉容器内软链接
            String[] names = dir.list();
            if (names == null || names.length == 0) continue;

            Set<String> nameSet = new HashSet<>(Arrays.asList(names));

            // A. 匹配已知 AI 软件
            for (AppItem known : KNOWN_AI_APPS) {
                if (nameSet.contains(known.id)) {
                    if (!addedIds.contains(known.id) && !addedIds.contains(known.command)) {
                        result.add(new AppItem(known.id, known.displayName, known.command, distro));
                        addedIds.add(known.id);
                        addedIds.add(known.command);
                    }
                }
            }

            // B. 动态匹配私有目录下符合 AI 关键词的软件
            for (String name : names) {
                if (name.startsWith(".") || addedIds.contains(name)) continue;
                if (name.endsWith(".pyc") || name.endsWith(".bak") || name.endsWith(".txt") || name.endsWith(".so")) continue;

                String lower = name.toLowerCase(Locale.ROOT);
                boolean isAi = false;
                for (String kw : AI_KEYWORDS) {
                    if (lower.contains(kw)) {
                        isAi = true;
                        break;
                    }
                }

                if (isAi) {
                    result.add(new AppItem(name, "🤖 " + name, name, distro));
                    addedIds.add(name);
                }
            }
        }
    }

    public static void launchApp(TermuxActivity activity, AppItem app) {
        TerminalSession session = activity.getCurrentSession();
        if (session == null || !session.isRunning()) {
            Toast.makeText(activity, "请先启动一个终端会话", Toast.LENGTH_SHORT).show();
            return;
        }

        String execCmd;
        if (app.distroName != null) {
            execCmd = "proot-distro login " + app.distroName + " -- " + app.command + "\n";
        } else if (isProotDistroInstalled()) {
            execCmd = "proot-distro login " + sDefaultDistroName + " -- " + app.command + "\n";
        } else {
            execCmd = app.command + "\n";
        }

        session.write(execCmd);
        activity.getDrawer().closeDrawers();
    }
}
