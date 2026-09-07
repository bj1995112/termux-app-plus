package com.termux.app.mcp.tools;

import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Environment;
import android.os.StatFs;

import com.termux.app.mcp.TermuxMcpManager;

import org.json.JSONObject;

/**
 * Android 设备及 Termux 系统状态监测工具
 */
public class SystemInfoTool implements McpTool {

    @Override
    public String getName() {
        return "get_system_info";
    }

    @Override
    public String getDescription() {
        return "获取 Android 设备的实时系统状态（包括电池电量、内存占用、内部存储空间、设备型号、架构及网络 IP 等）。";
    }

    @Override
    public String getPreferenceFilterKey() {
        return TermuxMcpManager.PREF_KEY_TOOL_SYSTEM_INFO;
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
        return getSystemStatusJson(context);
    }

    public static String getSystemStatusJson(Context context) {
        try {
            JSONObject obj = new JSONObject();
            obj.put("device", Build.MANUFACTURER + " " + Build.MODEL);
            obj.put("androidVersion", Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")");
            obj.put("architecture", Build.SUPPORTED_ABIS != null && Build.SUPPORTED_ABIS.length > 0 ? Build.SUPPORTED_ABIS[0] : "unknown");

            // 运存信息
            ActivityManager am = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
            if (am != null) {
                ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
                am.getMemoryInfo(mi);
                long availMb = mi.availMem / (1024 * 1024);
                long totalMb = mi.totalMem / (1024 * 1024);
                obj.put("ram", availMb + "MB 可用 / " + totalMb + "MB 总量");
            }

            // 存储空间
            StatFs stat = new StatFs(Environment.getDataDirectory().getPath());
            long availStorageMb = (stat.getAvailableBlocksLong() * stat.getBlockSizeLong()) / (1024 * 1024);
            long totalStorageMb = (stat.getBlockCountLong() * stat.getBlockSizeLong()) / (1024 * 1024);
            obj.put("storage", availStorageMb + "MB 剩余 / " + totalStorageMb + "MB 总量");

            // 电池状态
            Intent batteryIntent = context.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            if (batteryIntent != null) {
                int level = batteryIntent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
                int scale = batteryIntent.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
                int status = batteryIntent.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
                boolean charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL;
                float pct = (scale > 0) ? ((level * 100f) / scale) : 0f;
                obj.put("battery", (int) pct + "%" + (charging ? " (⚡ 充电中)" : " (放电中)"));
            }

            obj.put("localIp", TermuxMcpManager.getLocalIpAddress());
            obj.put("mcpPort", TermuxMcpManager.getInstance().getPort(context));
            return obj.toString(2);
        } catch (Exception e) {
            return "{\"error\": \"" + e.getMessage() + "\"}";
        }
    }
}
