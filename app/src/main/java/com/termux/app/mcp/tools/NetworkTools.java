package com.termux.app.mcp.tools;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;

import com.termux.app.mcp.TermuxMcpManager;
import com.termux.shared.termux.TermuxConstants;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;

/**
 * 网络与文件下载交互集合工具：
 * 支持通过系统浏览器打开链接、以及通过 curl 引擎执行断点高速下载。
 */
public class NetworkTools {

    public static String escapeShellArg(String arg) {
        if (arg == null) return "''";
        return "'" + arg.replace("'", "'\\''") + "'";
    }

    public static String openUrl(String url, Context context) {
        if (url == null || url.trim().isEmpty()) return "URL 不能为空";
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            url = "https://" + url;
        }
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
            return "已在手机浏览器中打开网页: " + url;
        } catch (Exception e) {
            return "打开网页失败: " + e.getMessage();
        }
    }

    public static String downloadFile(String url, String destPath, Context context) {
        if (url == null || url.trim().isEmpty()) return "下载链接不能为空";
        if (destPath == null || destPath.trim().isEmpty()) {
            String filename = "downloaded_file";
            int slashIdx = url.lastIndexOf('/');
            if (slashIdx != -1 && slashIdx < url.length() - 1) {
                String potential = url.substring(slashIdx + 1);
                if (potential.contains("?")) potential = potential.substring(0, potential.indexOf('?'));
                if (!potential.isEmpty()) filename = potential;
            }
            destPath = "/sdcard/Download/" + filename;
        }
        String cmd = "curl -sSL -L -o " + escapeShellArg(destPath) + " " + escapeShellArg(url);
        ShellTool shellTool = new ShellTool();
        String output = shellTool.runShellCommand(cmd, TermuxConstants.TERMUX_HOME_DIR_PATH, 120000);
        File dest = new File(destPath);
        if (dest.exists() && dest.length() > 0) {
            return "下载成功！文件已保存至: " + destPath + " (大小: " + dest.length() + " 字节)";
        } else {
            return "下载完成（或未检测到目标文件），回显如下:\n" + output;
        }
    }

    public static class OpenUrlTool implements McpTool {
        @Override
        public String getName() {
            return "open_url";
        }

        @Override
        public String getDescription() {
            return "调用系统默认浏览器打开指定的 HTTP/HTTPS 网页链接。";
        }

        @Override
        public String getPreferenceFilterKey() {
            return TermuxMcpManager.PREF_KEY_TOOL_OPEN_URL;
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
                urlProp.put("description", "需要打开的目标网页 URL 链接");
                properties.put("url", urlProp);

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
            String url = args.optString("url", "");
            return openUrl(url, context);
        }
    }

    public static class DownloadFileTool implements McpTool {
        @Override
        public String getName() {
            return "download_file";
        }

        @Override
        public String getDescription() {
            return "在 Termux 后台通过高速网络下载远程网络文件，默认保存至手机 Download 共享目录。";
        }

        @Override
        public String getPreferenceFilterKey() {
            return TermuxMcpManager.PREF_KEY_TOOL_DOWNLOAD;
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
                urlProp.put("description", "远程文件下载直链 URL");
                properties.put("url", urlProp);

                JSONObject destProp = new JSONObject();
                destProp.put("type", "string");
                destProp.put("description", "本地存储目标绝对路径（默认 /sdcard/Download/文件名）");
                properties.put("dest_path", destProp);

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
            String url = args.optString("url", "");
            String dest = args.optString("dest_path", "");
            return downloadFile(url, dest, context);
        }
    }
}
