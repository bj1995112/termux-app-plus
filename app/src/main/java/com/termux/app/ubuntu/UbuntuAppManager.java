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
import java.util.Set;

public class UbuntuAppManager {

    private static final String ROOTFS_BASE_PATH = "/data/data/com.termux/files/usr/var/lib/proot-distro/installed-rootfs";
    private static String sDefaultDistroName = "ubuntu";

    public static class AppItem {
        public final String id;
        public final String displayName;
        public final String command;
        public final String distroName;

        public AppItem(String id, String displayName, String command, String distroName) {
            this.id = id;
            this.displayName = displayName;
            this.command = command;
            this.distroName = distroName;
        }
    }

    // 核心知名 AI 编程软件（必须精确全等，绝无模糊子串误伤）
    private static final AppItem[] KNOWN_AI_APPS = new AppItem[]{
        new AppItem("pi", "⚡ Pi", "pi", "ubuntu"),
        new AppItem("codex", "🤖 Codex", "codex", "ubuntu"),
        new AppItem("opencode", "💻 OpenCode", "opencode", "ubuntu"),
        new AppItem("claude", "🧠 Claude", "claude", "ubuntu"),
        new AppItem("agy", "🚀 AGY", "agy", "ubuntu"),
        new AppItem("aider", "🤝 Aider", "aider", "ubuntu"),
        new AppItem("copilot", "✈️ Copilot", "copilot", "ubuntu"),
        new AppItem("gh-copilot", "✈️ Copilot", "gh copilot", "ubuntu"),
        new AppItem("cursor", "🎯 Cursor", "cursor", "ubuntu"),
        new AppItem("gemini", "♊ Gemini", "gemini", "ubuntu"),
        new AppItem("chatgpt", "💬 ChatGPT", "chatgpt", "ubuntu"),
        new AppItem("sgpt", "🐚 SGPT", "sgpt", "ubuntu"),
        new AppItem("interpreter", "🗣️ Interpreter", "interpreter", "ubuntu"),
        new AppItem("open-interpreter", "🗣️ Interpreter", "open-interpreter", "ubuntu"),
        new AppItem("plandex", "📋 Plandex", "plandex", "ubuntu"),
        new AppItem("mentat", "🧬 Mentat", "mentat", "ubuntu"),
        new AppItem("gpt-engineer", "⚙️ GPT-Eng", "gpt-engineer", "ubuntu"),
        new AppItem("ollama", "🦙 Ollama", "ollama", "ubuntu"),
        new AppItem("cody", "🔮 Cody", "cody", "ubuntu"),
        new AppItem("tabby", "🐱 Tabby", "tabby", "ubuntu"),
        new AppItem("continue", "⏩ Continue", "continue", "ubuntu"),
        new AppItem("fabric", "🧵 Fabric", "fabric", "ubuntu"),
        new AppItem("khoj", "🔍 Khoj", "khoj", "ubuntu"),
        new AppItem("codeman", "📦 CodeMan", "codeman", "ubuntu")
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

        // 仅深入扫描 Ubuntu 及所有 proot-distro 容器
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

            for (File dir : dirs) {
                if (!dir.exists() || !dir.isDirectory()) continue;

                String[] names = dir.list();
                if (names == null || names.length == 0) continue;

                Set<String> nameSet = new HashSet<>(Arrays.asList(names));

                // 严格 100% 精确全等匹配（Exact Match），绝对杜绝 pip、gzip 等杂质
                for (AppItem known : KNOWN_AI_APPS) {
                    if (nameSet.contains(known.id)) {
                        if (!addedIds.contains(known.id)) {
                            result.add(new AppItem(known.id, known.displayName, known.command, distro));
                            addedIds.add(known.id);
                        }
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
        String distro = app.distroName != null ? app.distroName : sDefaultDistroName;
        // 穿透拉起 Ubuntu 软件
        execCmd = "proot-distro login " + distro + " -- " + app.command + "\n";

        session.write(execCmd);
        activity.getDrawer().closeDrawers();
    }
}
