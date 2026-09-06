package com.termux.app.ubuntu;

import android.content.Context;
import android.widget.Toast;

import com.termux.app.TermuxActivity;
import com.termux.terminal.TerminalSession;

import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
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

    private static final AppItem[] KNOWN_APPS = new AppItem[]{
        new AppItem("codex", "🤖 Codex", "codex"),
        new AppItem("pi", "⚡ Pi", "pi"),
        new AppItem("opencode", "💻 OpenCode", "opencode"),
        new AppItem("claude", "🧠 Claude", "claude"),
        new AppItem("agy", "🚀 AGY", "agy"),
        new AppItem("codeman", "📦 CodeMan", "codeman"),
        new AppItem("htop", "📊 htop", "htop"),
        new AppItem("btop", "📈 btop", "btop"),
        new AppItem("vim", "📝 Vim", "vim"),
        new AppItem("nvim", "✨ NeoVim", "nvim"),
        new AppItem("python3", "🐍 Python", "python3"),
        new AppItem("python", "🐍 Python", "python"),
        new AppItem("node", "🟩 Node.js", "node"),
        new AppItem("tmux", "🔲 Tmux", "tmux"),
        new AppItem("git", "🐙 Git", "git status")
    };

    public static boolean isUbuntuInstalled() {
        File rootfs = new File(UBUNTU_ROOTFS_PATH);
        return rootfs.exists() && rootfs.isDirectory();
    }

    public static List<AppItem> getInstalledApps(Context context) {
        List<AppItem> result = new ArrayList<>();
        Set<String> addedIds = new HashSet<>();
        boolean hasUbuntu = isUbuntuInstalled();

        File[] searchDirs;
        File[] userCustomDirs;

        if (hasUbuntu) {
            searchDirs = new File[]{
                new File(UBUNTU_ROOTFS_PATH, "usr/bin"),
                new File(UBUNTU_ROOTFS_PATH, "usr/local/bin"),
                new File(UBUNTU_ROOTFS_PATH, "root/.local/bin"),
                new File(UBUNTU_ROOTFS_PATH, "home/ubuntu/.local/bin")
            };
            userCustomDirs = new File[]{
                new File(UBUNTU_ROOTFS_PATH, "root/.local/bin"),
                new File(UBUNTU_ROOTFS_PATH, "usr/local/bin"),
                new File(UBUNTU_ROOTFS_PATH, "home/ubuntu/.local/bin")
            };
        } else {
            searchDirs = new File[]{
                new File(context.getFilesDir(), "usr/bin"),
                new File(context.getFilesDir(), "home/.local/bin")
            };
            userCustomDirs = new File[]{
                new File(context.getFilesDir(), "home/.local/bin")
            };
        }

        // 1. 优先匹配具有专属显示名称和图标的预置已知软件
        for (AppItem app : KNOWN_APPS) {
            for (File dir : searchDirs) {
                File bin = new File(dir, app.id);
                if (bin.exists() && !bin.isDirectory()) {
                    if (!addedIds.contains(app.command)) {
                        result.add(app);
                        addedIds.add(app.command);
                        addedIds.add(app.id);
                    }
                    break;
                }
            }
        }

        // 2. 动态扫描用户私有目录（如 pip/npm/cargo 等自行安装在 .local/bin 或 /usr/local/bin 的自定义软件）
        for (File dir : userCustomDirs) {
            if (dir.exists() && dir.isDirectory()) {
                File[] files = dir.listFiles();
                if (files != null) {
                    for (File f : files) {
                        String name = f.getName();
                        if (f.isFile() && !name.startsWith(".") && !addedIds.contains(name)) {
                            // 过滤掉常见非直接命令脚本或临时文件
                            if (!name.endsWith(".pyc") && !name.endsWith(".bak")) {
                                result.add(new AppItem(name, "⚙️ " + name, name));
                                addedIds.add(name);
                            }
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
