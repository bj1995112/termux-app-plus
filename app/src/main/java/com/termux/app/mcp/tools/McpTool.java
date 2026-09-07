package com.termux.app.mcp.tools;

import android.content.Context;
import org.json.JSONObject;

/**
 * 统一 MCP 工具接口规范：
 * 每个独立的系统工具均实现此接口，由 ToolRegistry 统一管理与生命周期调度。
 */
public interface McpTool {

    /**
     * 工具主标识名称（例如 execute_command, file, get_system_info 等）
     */
    String getName();

    /**
     * 工具功能描述，供 AI 模型理解和选择
     */
    String getDescription();

    /**
     * 工具入参的 JSON Schema 规范（draft-07 标准，包含 type, properties, required）
     */
    JSONObject getInputSchema();

    /**
     * 关联的设置开关 Key（在 TermuxMcpManager 中定义），如无需开关控制返回 null
     */
    String getPreferenceFilterKey();

    /**
     * 执行具体工具
     * @param args AI 传入的实参
     * @param context Android 上下文环境
     * @return 文本执行结果
     */
    String execute(JSONObject args, Context context) throws Exception;
}
