package com.termux.app.mcp.tools;

import android.app.Notification;
import android.app.NotificationManager;
import android.content.Context;
import android.hardware.camera2.CameraManager;
import android.os.Handler;
import android.os.Looper;
import android.os.Vibrator;
import android.speech.tts.TextToSpeech;
import android.widget.Toast;

import com.termux.R;
import com.termux.app.mcp.TermuxMcpManager;
import com.termux.shared.notification.NotificationUtils;
import com.termux.shared.termux.TermuxConstants;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Locale;

/**
 * 手机硬件设备交互集合工具：
 * 包括手电筒、语音朗读 TTS、屏幕气泡 Toast、系统通知、马达震动等。
 */
public class DeviceHardwareTools {

    private static volatile TextToSpeech sTextToSpeech;

    private static synchronized void initTtsIfNeeded(Context context) {
        if (sTextToSpeech == null) {
            new Handler(Looper.getMainLooper()).post(() -> {
                sTextToSpeech = new TextToSpeech(context.getApplicationContext(), status -> {
                    if (status == TextToSpeech.SUCCESS && sTextToSpeech != null) {
                        sTextToSpeech.setLanguage(Locale.CHINESE);
                    }
                });
            });
        }
    }

    public static synchronized void releaseTts() {
        if (sTextToSpeech != null) {
            try {
                sTextToSpeech.stop();
                sTextToSpeech.shutdown();
            } catch (Exception ignored) {}
            sTextToSpeech = null;
        }
    }

    public static String toggleTorch(boolean enable, Context context) {
        try {
            CameraManager cm = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
            if (cm == null) return "设备不支持相机管理服务";
            String[] ids = cm.getCameraIdList();
            if (ids == null || ids.length == 0) return "未检测到可用摄像头与闪光灯";
            cm.setTorchMode(ids[0], enable);
            return enable ? "手机手电筒已打开 🔦" : "手机手电筒已关闭";
        } catch (Exception e) {
            return "手电筒控制失败: " + e.getMessage();
        }
    }

    public static String speakTts(String text, Context context) {
        if (text == null || text.trim().isEmpty()) return "朗读内容不能为空";
        try {
            initTtsIfNeeded(context);
            new Handler(Looper.getMainLooper()).post(() -> {
                if (sTextToSpeech != null) {
                    sTextToSpeech.speak(text, TextToSpeech.QUEUE_ADD, null, "TermuxMcpUtterance");
                }
            });
            return "已通过扬声器开始朗读: " + text;
        } catch (Exception e) {
            return "语音朗读失败: " + e.getMessage();
        }
    }

    public static String showToast(String message, Context context) {
        if (message == null || message.trim().isEmpty()) {
            return "提示内容不能为空";
        }
        try {
            new Handler(Looper.getMainLooper()).post(() -> {
                Toast.makeText(context.getApplicationContext(), message, Toast.LENGTH_SHORT).show();
            });
            return "屏幕气泡已弹出: " + message;
        } catch (Exception e) {
            return "弹出屏幕气泡失败: " + e.getMessage();
        }
    }

    public static String postNotification(String title, String content, Context context) {
        try {
            NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) {
                NotificationUtils.setupNotificationChannel(context, TermuxConstants.TERMUX_APP_NOTIFICATION_CHANNEL_ID, "Termux+ Notifications", NotificationManager.IMPORTANCE_HIGH);
                Notification.Builder builder = NotificationUtils.geNotificationBuilder(context, TermuxConstants.TERMUX_APP_NOTIFICATION_CHANNEL_ID, Notification.PRIORITY_HIGH, title, content, content, null, null, NotificationUtils.NOTIFICATION_MODE_ALL);
                if (builder != null) {
                    builder.setSmallIcon(R.drawable.ic_terminal);
                    nm.notify((int) System.currentTimeMillis(), builder.build());
                    return "成功在手机状态栏弹出系统通知: " + title;
                }
            }
            return "通知发送失败：无法初始化通知构建器";
        } catch (Exception e) {
            return "发送通知异常: " + e.getMessage();
        }
    }

    public static String doVibrate(int durationMs, Context context) {
        try {
            Vibrator v = (Vibrator) context.getSystemService(Context.VIBRATOR_SERVICE);
            if (v != null) {
                v.vibrate(Math.min(durationMs, 5000));
                return "手机成功震动 " + durationMs + " 毫秒";
            }
            return "未找到震动马达硬件";
        } catch (Exception e) {
            return "震动调用失败: " + e.getMessage();
        }
    }

    public static class TorchTool implements McpTool {
        @Override
        public String getName() {
            return "termux_torch";
        }

        @Override
        public String getDescription() {
            return "开关 Android 手机后置闪光灯/手电筒。";
        }

        @Override
        public String getPreferenceFilterKey() {
            return TermuxMcpManager.PREF_KEY_TOOL_TORCH;
        }

        @Override
        public JSONObject getInputSchema() {
            try {
                JSONObject schema = new JSONObject();
                schema.put("type", "object");
                schema.put("$schema", "http://json-schema.org/draft-07/schema#");

                JSONObject properties = new JSONObject();
                JSONObject enabledProp = new JSONObject();
                enabledProp.put("type", "boolean");
                enabledProp.put("description", "true 打开手电筒，false 关闭手电筒");
                properties.put("enabled", enabledProp);

                schema.put("properties", properties);
                JSONArray required = new JSONArray();
                required.put("enabled");
                schema.put("required", required);
                return schema;
            } catch (Exception e) {
                return new JSONObject();
            }
        }

        @Override
        public String execute(JSONObject args, Context context) throws Exception {
            boolean enabled = args.optBoolean("enabled", true);
            return toggleTorch(enabled, context);
        }
    }

    public static class TtsTool implements McpTool {
        @Override
        public String getName() {
            return "termux_tts_speak";
        }

        @Override
        public String getDescription() {
            return "通过 Android 手机扬声器使用 TTS（文字转语音）引擎大声朗读文本。";
        }

        @Override
        public String getPreferenceFilterKey() {
            return TermuxMcpManager.PREF_KEY_TOOL_TTS;
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
                textProp.put("description", "需要扬声器朗读的文本文字");
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
            return speakTts(text, context);
        }
    }

    public static class ToastTool implements McpTool {
        @Override
        public String getName() {
            return "termux_toast";
        }

        @Override
        public String getDescription() {
            return "在 Android 手机屏幕底部弹出简短的 Toast 气泡提醒消息。";
        }

        @Override
        public String getPreferenceFilterKey() {
            return TermuxMcpManager.PREF_KEY_TOOL_FEEDBACK;
        }

        @Override
        public JSONObject getInputSchema() {
            try {
                JSONObject schema = new JSONObject();
                schema.put("type", "object");
                schema.put("$schema", "http://json-schema.org/draft-07/schema#");

                JSONObject properties = new JSONObject();
                JSONObject msgProp = new JSONObject();
                msgProp.put("type", "string");
                msgProp.put("description", "需要在屏幕底部弹出的短消息");
                properties.put("message", msgProp);

                schema.put("properties", properties);
                JSONArray required = new JSONArray();
                required.put("message");
                schema.put("required", required);
                return schema;
            } catch (Exception e) {
                return new JSONObject();
            }
        }

        @Override
        public String execute(JSONObject args, Context context) throws Exception {
            String msg = args.optString("message", "");
            return showToast(msg, context);
        }
    }

    public static class NotifyTool implements McpTool {
        @Override
        public String getName() {
            return "termux_notify";
        }

        @Override
        public String getDescription() {
            return "在 Android 顶部下拉通知栏发送常驻系统提醒通知。";
        }

        @Override
        public String getPreferenceFilterKey() {
            return TermuxMcpManager.PREF_KEY_TOOL_FEEDBACK;
        }

        @Override
        public JSONObject getInputSchema() {
            try {
                JSONObject schema = new JSONObject();
                schema.put("type", "object");
                schema.put("$schema", "http://json-schema.org/draft-07/schema#");

                JSONObject properties = new JSONObject();
                JSONObject titleProp = new JSONObject();
                titleProp.put("type", "string");
                titleProp.put("description", "通知标题");
                properties.put("title", titleProp);

                JSONObject contentProp = new JSONObject();
                contentProp.put("type", "string");
                contentProp.put("description", "通知正文详细内容");
                properties.put("content", contentProp);

                schema.put("properties", properties);
                JSONArray required = new JSONArray();
                required.put("content");
                schema.put("required", required);
                return schema;
            } catch (Exception e) {
                return new JSONObject();
            }
        }

        @Override
        public String execute(JSONObject args, Context context) throws Exception {
            String title = args.optString("title", "Termux+ MCP 提醒");
            String content = args.optString("content", "");
            return postNotification(title, content, context);
        }
    }

    public static class VibrateTool implements McpTool {
        @Override
        public String getName() {
            return "termux_vibrate";
        }

        @Override
        public String getDescription() {
            return "让 Android 手机硬件马达发生物理震动（用于引起用户物理注意）。";
        }

        @Override
        public String getPreferenceFilterKey() {
            return TermuxMcpManager.PREF_KEY_TOOL_FEEDBACK;
        }

        @Override
        public JSONObject getInputSchema() {
            try {
                JSONObject schema = new JSONObject();
                schema.put("type", "object");
                schema.put("$schema", "http://json-schema.org/draft-07/schema#");

                JSONObject properties = new JSONObject();
                JSONObject durProp = new JSONObject();
                durProp.put("type", "integer");
                durProp.put("description", "震动时长（毫秒，默认 500，最大限制 5000）");
                properties.put("duration_ms", durProp);

                schema.put("properties", properties);
                return schema;
            } catch (Exception e) {
                return new JSONObject();
            }
        }

        @Override
        public String execute(JSONObject args, Context context) throws Exception {
            int duration = args.optInt("duration_ms", 500);
            return doVibrate(duration, context);
        }
    }
}
