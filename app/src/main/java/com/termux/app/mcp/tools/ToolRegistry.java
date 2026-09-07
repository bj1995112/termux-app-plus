package com.termux.app.mcp.tools;

import android.content.Context;

import com.termux.app.mcp.TermuxMcpManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * MCP 工具注册中心与调度管理器：
 * 1. 负责注册、发现、过滤与执行所有内置 MCP 工具；
 * 2. 结合 TermuxMcpManager 进行细粒度权限控制；
 * 3. 统一规范化异常捕获与输出格式。
 */
public class ToolRegistry {

    private static volatile ToolRegistry sInstance;

    // 主工具映射 (name -> tool)
    private final Map<String, McpTool> mTools = new LinkedHashMap<>();
    // 别名映射 (alias -> originalName)
    private final Map<String, String> mAliases = new ConcurrentHashMap<>();

    private ToolRegistry() {
        registerDefaultTools();
    }

    public static ToolRegistry getInstance() {
        if (sInstance == null) {
            synchronized (ToolRegistry.class) {
                if (sInstance == null) {
                    sInstance = new ToolRegistry();
                }
            }
        }
        return sInstance;
    }

    /**
     * 注册核心默认工具集合
     */
    private void registerDefaultTools() {
        // 1. Shell 执行
        ShellTool shellTool = new ShellTool();
        registerTool(shellTool);
        registerAlias("shell", "execute_command");

        // 2. 文件系统
        registerTool(new FileTools.CombinedFileTool());
        registerTool(new FileTools.ReadFileTool());
        registerTool(new FileTools.WriteFileTool());
        registerTool(new FileTools.ListDirectoryTool());

        // 3. 系统状态
        registerTool(new SystemInfoTool());
        registerAlias("system", "get_system_info");

        // 4. 剪贴板
        registerTool(new ClipboardTools.GetClipboardTool());
        registerTool(new ClipboardTools.SetClipboardTool());

        // 5. 硬件与多媒体交互
        registerTool(new DeviceHardwareTools.TorchTool());
        registerTool(new DeviceHardwareTools.TtsTool());
        registerTool(new DeviceHardwareTools.ToastTool());
        registerTool(new DeviceHardwareTools.NotifyTool());
        registerTool(new DeviceHardwareTools.VibrateTool());

        // 6. 网络与下载
        registerTool(new NetworkTools.OpenUrlTool());
        registerTool(new NetworkTools.DownloadFileTool());
    }

    public synchronized void registerTool(McpTool tool) {
        if (tool != null) {
            mTools.put(tool.getName(), tool);
        }
    }

    public synchronized void registerAlias(String alias, String originalName) {
        if (alias != null && originalName != null) {
            mAliases.put(alias, originalName);
        }
    }

    public McpTool getTool(String name) {
        if (name == null) return null;
        McpTool tool = mTools.get(name);
        if (tool == null && mAliases.containsKey(name)) {
            String orig = mAliases.get(name);
            if (orig != null) {
                tool = mTools.get(orig);
            }
        }
        return tool;
    }

    public boolean isToolAllowed(String toolName, Context context) {
        McpTool tool = getTool(toolName);
        if (tool == null) return false;
        String prefKey = tool.getPreferenceFilterKey();
        if (prefKey == null || prefKey.isEmpty()) {
            return true;
        }
        return TermuxMcpManager.getInstance().isToolEnabled(context, prefKey);
    }

    /**
     * 生成符合 MCP 标准的 draft-07 工具列表定义
     */
    public JSONArray getMcpToolsDefinition(Context context) {
        JSONArray toolsArray = new JSONArray();
        for (McpTool tool : mTools.values()) {
            String prefKey = tool.getPreferenceFilterKey();
            if (prefKey != null && !prefKey.isEmpty()) {
                if (!TermuxMcpManager.getInstance().isToolEnabled(context, prefKey)) {
                    continue; // 用户关闭该工具，不向 AI 暴露
                }
            }

            try {
                JSONObject toolObj = new JSONObject();
                toolObj.put("name", tool.getName());
                toolObj.put("description", tool.getDescription());

                JSONObject schema = tool.getInputSchema();
                if (schema == null) {
                    schema = new JSONObject();
                    schema.put("type", "object");
                    schema.put("$schema", "http://json-schema.org/draft-07/schema#");
                    schema.put("properties", new JSONObject());
                }
                toolObj.put("inputSchema", schema);

                JSONObject exec = new JSONObject();
                exec.put("taskSupport", "forbidden");
                toolObj.put("execution", exec);

                toolsArray.put(toolObj);
            } catch (Exception ignored) {}
        }
        return toolsArray;
    }

    /**
     * 核心统一调度执行工具
     */
    public JSONObject executeTool(String toolName, JSONObject args, Context context) {
        JSONObject result = new JSONObject();
        JSONArray content = new JSONArray();
        boolean isError = false;
        String textOutput = "";

        if (args == null) {
            args = new JSONObject();
        }

        McpTool tool = getTool(toolName);
        if (tool == null) {
            isError = true;
            textOutput = "未知工具名称: " + toolName;
        } else if (!isToolAllowed(toolName, context)) {
            isError = true;
            textOutput = "权限拒绝：该工具 [" + toolName + "] 已被用户在手机端设置中关闭！";
        } else {
            try {
                textOutput = tool.execute(args, context);
            } catch (Exception e) {
                isError = true;
                textOutput = "执行异常: " + (e.getMessage() != null ? e.getMessage() : e.toString());
            }
        }

        try {
            JSONObject textObj = new JSONObject();
            textObj.put("type", "text");
            textObj.put("text", textOutput);
            content.put(textObj);

            result.put("content", content);
            result.put("isError", isError);
        } catch (Exception ignored) {}

        return result;
    }
}
