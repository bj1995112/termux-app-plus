package com.termux.app.mcp.tools;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import com.termux.app.mcp.TermuxMcpManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

/**
 * 手机系统剪贴板管理工具：
 * 支持通过主线程安全访问与写入系统剪贴板。
 */
public class ClipboardTools {

    public static String getClipboardContent(Context context) {
        try {
            FutureTask<String> task = new FutureTask<>(() -> {
                ClipboardManager cm = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
                if (cm != null && cm.hasPrimaryClip()) {
                    ClipData data = cm.getPrimaryClip();
                    if (data != null && data.getItemCount() > 0) {
                        CharSequence text = data.getItemAt(0).getText();
                        return text != null ? text.toString() : "";
                    }
                }
                return "";
            });
            new Handler(Looper.getMainLooper()).post(task);
            String result = task.get(3, TimeUnit.SECONDS);
            return (result == null || result.isEmpty()) ? "（手机剪贴板为空）" : result;
        } catch (Exception e) {
            return "读取剪贴板异常: " + e.getMessage();
        }
    }

    public static String setClipboardContent(String text, Context context) {
        if (text == null) text = "";
        final String copyText = text;
        try {
            new Handler(Looper.getMainLooper()).post(() -> {
                ClipboardManager cm = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
                if (cm != null) {
                    ClipData clip = ClipData.newPlainText("Termux MCP", copyText);
                    cm.setPrimaryClip(clip);
                }
            });
            return "已成功将 " + text.length() + " 个字符写入手机系统剪贴板！";
        } catch (Exception e) {
            return "写入剪贴板异常: " + e.getMessage();
        }
    }

    public static class GetClipboardTool implements McpTool {
        @Override
        public String getName() {
            return "get_clipboard";
        }

        @Override
        public String getDescription() {
            return "获取 Android 手机系统剪贴板当前存储的最新文本内容。";
        }

        @Override
        public String getPreferenceFilterKey() {
            return TermuxMcpManager.PREF_KEY_TOOL_CLIPBOARD;
        }

        @Override
        public JSONObject getInputSchema() {
            try {
                JSONObject schema = new JSONObject();
                schema.put("type", "object");
                schema.put("$schema", "http://json-schema.org/draft-07/schema#");
                schema.put("properties", new JSONObject());
                return schema;
            } catch (Exception e) {
                return new JSONObject();
            }
        }

        @Override
        public String execute(JSONObject args, Context context) throws Exception {
            return getClipboardContent(context);
        }
    }

    public static class SetClipboardTool implements McpTool {
        @Override
        public String getName() {
            return "set_clipboard";
        }

        @Override
        public String getDescription() {
            return "将指定的文本写入 Android 手机系统剪贴板，方便用户在手机上直接粘贴。";
        }

        @Override
        public String getPreferenceFilterKey() {
            return TermuxMcpManager.PREF_KEY_TOOL_CLIPBOARD;
        }

        @Override
        public JSONObject getInputSchema() {
            try {
                JSONObject schema = new JSONObject();
                schema.put("type", "object");
                schema.put("$schema", "http://json-schema.org/draft-07/schema#");

                JSONObject properties = new JSONObject();
                JSONObject textProp = new JSONObject();
                textProp.put("type", "string");
                textProp.put("description", "要写入手机剪贴板的文本内容");
                properties.put("text", textProp);

                schema.put("properties", properties);
                JSONArray required = new JSONArray();
                required.put("text");
                schema.put("required", required);
                return schema;
            } catch (Exception e) {
                return new JSONObject();
            }
        }

        @Override
        public String execute(JSONObject args, Context context) throws Exception {
            String text = args.optString("text", "");
            return setClipboardContent(text, context);
        }
    }
}
