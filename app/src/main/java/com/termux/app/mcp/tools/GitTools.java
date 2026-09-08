package com.termux.app.mcp.tools;

import android.content.Context;

import com.termux.app.mcp.TermuxMcpManager;
import com.termux.shared.termux.TermuxConstants;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;

/**
 * Git 常用版本管理工具集合：
 * 提供 git_status, git_pull, git_clone, git_log, git_diff 等标准操作，适配 Termux 环境。
 */
public class GitTools {

    private static boolean isGitInstalled() {
        File gitBin = new File(TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH, "git");
        return gitBin.exists();
    }

    private static JSONObject makeGitNotInstalledError() {
        JSONObject err = new JSONObject();
        try {
            err.put("success", false);
            err.put("stdout", "");
            err.put("stderr", "未在 Termux 中检测到 Git。请在终端执行 'pkg install -y git' 安装。");
            err.put("exit_code", -1);
            err.put("duration", "0.000s");
        } catch (Exception ignored) {}
        return err;
    }

    /**
     * 1. git_status
     */
    public static class GitStatusTool implements McpTool {

        @Override
        public String getName() {
            return "git_status";
        }

        @Override
        public String getDescription() {
            return "查看指定 Git 仓库的当前分支、改动文件列表、未跟踪文件等状态信息。";
        }

        @Override
        public String getPreferenceFilterKey() {
            return TermuxMcpManager.PREF_KEY_TOOL_GIT;
        }

        @Override
        public JSONObject getInputSchema() {
            try {
                JSONObject schema = new JSONObject();
                schema.put("type", "object");
                schema.put("$schema", "http://json-schema.org/draft-07/schema#");

                JSONObject properties = new JSONObject();
                JSONObject pathProp = new JSONObject();
                pathProp.put("type", "string");
                pathProp.put("description", "Git 仓库本地目录绝对路径（可选，默认 Termux 用户家目录）");
                properties.put("path", pathProp);

                schema.put("properties", properties);
                return schema;
            } catch (Exception e) {
                return new JSONObject();
            }
        }

        @Override
        public String execute(JSONObject args, Context context) throws Exception {
            if (!isGitInstalled()) {
                return makeGitNotInstalledError().toString();
            }

            String repoPath = args.optString("path", TermuxConstants.TERMUX_HOME_DIR_PATH);
            int defaultTimeoutMs = TermuxMcpManager.getInstance().getExecTimeoutSec(context) * 1000;

            ShellTool.CommandResult res = ShellTool.run("git status", repoPath, defaultTimeoutMs);
            JSONObject json = res.toJsonObject();
            json.put("path", repoPath);

            String stdout = res.stdout;
            boolean isClean = stdout.contains("nothing to commit, working tree clean");
            json.put("is_clean", isClean);

            // 尝试提取当前分支名
            String branch = "unknown";
            if (res.success) {
                ShellTool.CommandResult branchRes = ShellTool.run("git branch --show-current", repoPath, 5000);
                if (branchRes.success && !branchRes.stdout.trim().isEmpty()) {
                    branch = branchRes.stdout.trim();
                } else {
                    // 兼容 detached HEAD 或旧版本 git
                    String[] lines = stdout.split("\n");
                    if (lines.length > 0 && lines[0].startsWith("On branch ")) {
                        branch = lines[0].substring("On branch ".length()).trim();
                    }
                }
            }
            json.put("branch", branch);
            json.put("raw_status", stdout);

            return json.toString();
        }
    }

    /**
     * 2. git_pull
     */
    public static class GitPullTool implements McpTool {

        @Override
        public String getName() {
            return "git_pull";
        }

        @Override
        public String getDescription() {
            return "从远程 Git 仓库拉取最新提交并合并到当前分支。";
        }

        @Override
        public String getPreferenceFilterKey() {
            return TermuxMcpManager.PREF_KEY_TOOL_GIT;
        }

        @Override
        public JSONObject getInputSchema() {
            try {
                JSONObject schema = new JSONObject();
                schema.put("type", "object");
                schema.put("$schema", "http://json-schema.org/draft-07/schema#");

                JSONObject properties = new JSONObject();
                JSONObject pathProp = new JSONObject();
                pathProp.put("type", "string");
                pathProp.put("description", "Git 仓库本地目录路径（必填或可选，默认 Termux 用户家目录）");
                properties.put("path", pathProp);

                JSONObject remoteProp = new JSONObject();
                remoteProp.put("type", "string");
                remoteProp.put("description", "远程仓库名称（可选，如 origin）");
                properties.put("remote", remoteProp);

                JSONObject branchProp = new JSONObject();
                branchProp.put("type", "string");
                branchProp.put("description", "要拉取的分支名（可选，如 main 或 master）");
                properties.put("branch", branchProp);

                schema.put("properties", properties);
                return schema;
            } catch (Exception e) {
                return new JSONObject();
            }
        }

        @Override
        public String execute(JSONObject args, Context context) throws Exception {
            if (!isGitInstalled()) {
                return makeGitNotInstalledError().toString();
            }

            String repoPath = args.optString("path", TermuxConstants.TERMUX_HOME_DIR_PATH);
            String remote = args.optString("remote", "").trim();
            String branch = args.optString("branch", "").trim();

            StringBuilder cmd = new StringBuilder("git pull");
            if (!remote.isEmpty()) {
                cmd.append(" ").append(remote);
                if (!branch.isEmpty()) {
                    cmd.append(" ").append(branch);
                }
            }

            int defaultTimeoutMs = TermuxMcpManager.getInstance().getExecTimeoutSec(context) * 1000;
            // pull 网络操作时间可能较长，默认至少给 60s
            int timeoutMs = Math.max(defaultTimeoutMs, 60000);

            ShellTool.CommandResult res = ShellTool.run(cmd.toString(), repoPath, timeoutMs);
            return res.toJsonString();
        }
    }

    /**
     * 3. git_clone
     */
    public static class GitCloneTool implements McpTool {

        @Override
        public String getName() {
            return "git_clone";
        }

        @Override
        public String getDescription() {
            return "克隆远程 Git 仓库至本地目录，支持配置克隆深度 depth 快速拉取。";
        }

        @Override
        public String getPreferenceFilterKey() {
            return TermuxMcpManager.PREF_KEY_TOOL_GIT;
        }

        @Override
        public JSONObject getInputSchema() {
            try {
                JSONObject schema = new JSONObject();
                schema.put("type", "object");
                schema.put("$schema", "http://json-schema.org/draft-07/schema#");

                JSONObject properties = new JSONObject();
                JSONObject urlProp = new JSONObject();
                urlProp.put("type", "string");
                urlProp.put("description", "Git 仓库远程地址（必填，如 https://github.com/user/repo.git）");
                properties.put("url", urlProp);

                JSONObject pathProp = new JSONObject();
                pathProp.put("type", "string");
                pathProp.put("description", "克隆目标目录或父目录路径（可选，默认在当前目录克隆）");
                properties.put("path", pathProp);

                JSONObject depthProp = new JSONObject();
                depthProp.put("type", "integer");
                depthProp.put("description", "克隆深度（可选，如 1 表示浅克隆，加快拉取速度）");
                properties.put("depth", depthProp);

                schema.put("properties", properties);
                JSONArray required = new JSONArray();
                required.put("url");
                schema.put("required", required);
                return schema;
            } catch (Exception e) {
                return new JSONObject();
            }
        }

        @Override
        public String execute(JSONObject args, Context context) throws Exception {
            if (!isGitInstalled()) {
                return makeGitNotInstalledError().toString();
            }

            String url = args.optString("url", "").trim();
            if (url.isEmpty()) {
                JSONObject err = new JSONObject();
                err.put("success", false);
                err.put("stdout", "");
                err.put("stderr", "错误：必须指定 Git 仓库地址 url");
                err.put("exit_code", -1);
                err.put("duration", "0.000s");
                return err.toString();
            }

            String destPath = args.optString("path", "").trim();
            int depth = args.optInt("depth", 0);

            StringBuilder cmd = new StringBuilder("git clone");
            if (depth > 0) {
                cmd.append(" --depth ").append(depth);
            }
            cmd.append(" \"").append(url).append("\"");
            if (!destPath.isEmpty()) {
                cmd.append(" \"").append(destPath).append("\"");
            }

            int defaultTimeoutMs = TermuxMcpManager.getInstance().getExecTimeoutSec(context) * 1000;
            // clone 网络耗时较长，默认至少 120s
            int timeoutMs = Math.max(defaultTimeoutMs, 120000);

            ShellTool.CommandResult res = ShellTool.run(cmd.toString(), TermuxConstants.TERMUX_HOME_DIR_PATH, timeoutMs);
            return res.toJsonString();
        }
    }

    /**
     * 4. git_log
     */
    public static class GitLogTool implements McpTool {

        @Override
        public String getName() {
            return "git_log";
        }

        @Override
        public String getDescription() {
            return "查看 Git 提交历史记录，支持自定义条数 limit 与 oneline 紧凑模式。";
        }

        @Override
        public String getPreferenceFilterKey() {
            return TermuxMcpManager.PREF_KEY_TOOL_GIT;
        }

        @Override
        public JSONObject getInputSchema() {
            try {
                JSONObject schema = new JSONObject();
                schema.put("type", "object");
                schema.put("$schema", "http://json-schema.org/draft-07/schema#");

                JSONObject properties = new JSONObject();
                JSONObject pathProp = new JSONObject();
                pathProp.put("type", "string");
                pathProp.put("description", "Git 仓库本地目录路径（可选，默认 Termux 用户家目录）");
                properties.put("path", pathProp);

                JSONObject limitProp = new JSONObject();
                limitProp.put("type", "integer");
                limitProp.put("description", "获取的提交条数（可选，默认 10，上限 50）");
                properties.put("limit", limitProp);

                JSONObject onelineProp = new JSONObject();
                onelineProp.put("type", "boolean");
                onelineProp.put("description", "是否仅显示单行紧凑摘要（可选，默认 false）");
                properties.put("oneline", onelineProp);

                schema.put("properties", properties);
                return schema;
            } catch (Exception e) {
                return new JSONObject();
            }
        }

        @Override
        public String execute(JSONObject args, Context context) throws Exception {
            if (!isGitInstalled()) {
                return makeGitNotInstalledError().toString();
            }

            String repoPath = args.optString("path", TermuxConstants.TERMUX_HOME_DIR_PATH);
            int limit = args.optInt("limit", 10);
            if (limit <= 0) limit = 10;
            if (limit > 50) limit = 50;

            boolean oneline = args.optBoolean("oneline", false);

            StringBuilder cmd = new StringBuilder("git log -n ").append(limit);
            if (oneline) {
                cmd.append(" --oneline");
            }

            int defaultTimeoutMs = TermuxMcpManager.getInstance().getExecTimeoutSec(context) * 1000;
            ShellTool.CommandResult res = ShellTool.run(cmd.toString(), repoPath, defaultTimeoutMs);
            return res.toJsonString();
        }
    }

    /**
     * 5. git_diff
     */
    public static class GitDiffTool implements McpTool {

        @Override
        public String getName() {
            return "git_diff";
        }

        @Override
        public String getDescription() {
            return "查看 Git 工作区或暂存区的代码修改对比（diff），支持针对特定文件查看。";
        }

        @Override
        public String getPreferenceFilterKey() {
            return TermuxMcpManager.PREF_KEY_TOOL_GIT;
        }

        @Override
        public JSONObject getInputSchema() {
            try {
                JSONObject schema = new JSONObject();
                schema.put("type", "object");
                schema.put("$schema", "http://json-schema.org/draft-07/schema#");

                JSONObject properties = new JSONObject();
                JSONObject pathProp = new JSONObject();
                pathProp.put("type", "string");
                pathProp.put("description", "Git 仓库本地目录路径（可选，默认 Termux 用户家目录）");
                properties.put("path", pathProp);

                JSONObject cachedProp = new JSONObject();
                cachedProp.put("type", "boolean");
                cachedProp.put("description", "是否对比暂存区（--cached/staged），默认 false（查看未暂存改动）");
                properties.put("cached", cachedProp);

                JSONObject fileProp = new JSONObject();
                fileProp.put("type", "string");
                fileProp.put("description", "指定比对的单文件路径（可选）");
                properties.put("file", fileProp);

                schema.put("properties", properties);
                return schema;
            } catch (Exception e) {
                return new JSONObject();
            }
        }

        @Override
        public String execute(JSONObject args, Context context) throws Exception {
            if (!isGitInstalled()) {
                return makeGitNotInstalledError().toString();
            }

            String repoPath = args.optString("path", TermuxConstants.TERMUX_HOME_DIR_PATH);
            boolean cached = args.optBoolean("cached", false) || args.optBoolean("staged", false);
            String file = args.optString("file", "").trim();

            StringBuilder cmd = new StringBuilder("git diff");
            if (cached) {
                cmd.append(" --cached");
            }
            if (!file.isEmpty()) {
                cmd.append(" -- \"").append(file).append("\"");
            }

            int defaultTimeoutMs = TermuxMcpManager.getInstance().getExecTimeoutSec(context) * 1000;
            ShellTool.CommandResult res = ShellTool.run(cmd.toString(), repoPath, defaultTimeoutMs);
            return res.toJsonString();
        }
    }
}
