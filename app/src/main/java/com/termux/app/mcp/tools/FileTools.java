package com.termux.app.mcp.tools;

import android.content.Context;

import com.termux.app.mcp.TermuxMcpManager;
import com.termux.shared.termux.TermuxConstants;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * 文件管理与 I/O 集合工具：
 * 包含通用 file、read_file、write_file、list_directory 原生读写。
 */
public class FileTools {

    public static String readFileContent(String path) {
        if (path == null || path.trim().isEmpty()) {
            return "错误：文件路径不能为空";
        }
        File file = new File(path);
        if (!file.exists()) {
            return "错误：文件不存在: " + path;
        }
        if (file.isDirectory()) {
            return "错误：指定路径是目录而非文件: " + path;
        }

        try (FileInputStream fis = new FileInputStream(file);
             InputStreamReader isr = new InputStreamReader(fis, StandardCharsets.UTF_8);
             BufferedReader reader = new BufferedReader(isr)) {

            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append("\n");
            }
            return sb.toString();
        } catch (Exception e) {
            return "读取文件失败: " + e.getMessage();
        }
    }

    public static String writeFileContent(String path, String content, boolean append) {
        if (path == null || path.trim().isEmpty()) {
            return "错误：文件路径不能为空";
        }
        File file = new File(path);
        File parent = file.getParentFile();
        if (parent != null && !parent.exists()) {
            parent.mkdirs();
        }

        try (FileOutputStream fos = new FileOutputStream(file, append)) {
            if (content != null) {
                fos.write(content.getBytes(StandardCharsets.UTF_8));
            }
            return (append ? "成功追加内容到文件: " : "成功写入文件: ") + path + " (大小: " + file.length() + " 字节)";
        } catch (Exception e) {
            return "写入文件失败: " + e.getMessage();
        }
    }

    public static String listDirectory(String path) {
        File dir = new File(path != null && !path.isEmpty() ? path : TermuxConstants.TERMUX_HOME_DIR_PATH);
        if (!dir.exists()) {
            return "错误：目录不存在: " + path;
        }
        if (!dir.isDirectory()) {
            return "错误：指定路径不是目录: " + path;
        }

        File[] files = dir.listFiles();
        if (files == null) {
            return "无法访问或列出目录: " + path;
        }

        JSONArray array = new JSONArray();
        for (File f : files) {
            try {
                JSONObject obj = new JSONObject();
                obj.put("name", f.getName());
                obj.put("is_dir", f.isDirectory());
                obj.put("size", f.length());
                obj.put("last_modified", f.lastModified());
                array.put(obj);
            } catch (Exception ignored) {}
        }
        return array.toString();
    }

    /**
     * 通用综合 file 工具
     */
    public static class CombinedFileTool implements McpTool {
        @Override
        public String getName() {
            return "file";
        }

        @Override
        public String getDescription() {
            return "Termux 文件系统读写与管理工具。支持 action=read/write/append/list。";
        }

        @Override
        public String getPreferenceFilterKey() {
            return TermuxMcpManager.PREF_KEY_TOOL_FILE_OPS;
        }

        @Override
        public JSONObject getInputSchema() {
            try {
                JSONObject schema = new JSONObject();
                schema.put("type", "object");
                schema.put("$schema", "http://json-schema.org/draft-07/schema#");

                JSONObject properties = new JSONObject();

                JSONObject actProp = new JSONObject();
                actProp.put("type", "string");
                actProp.put("description", "操作类型：read（读取文件）、write（覆盖写）、append（追加写）、list（列出目录内容）");
                properties.put("action", actProp);

                JSONObject pathProp = new JSONObject();
                pathProp.put("type", "string");
                pathProp.put("description", "目标绝对路径或相对路径（默认为 Termux Home 目录 ~）");
                properties.put("path", pathProp);

                JSONObject contentProp = new JSONObject();
                contentProp.put("type", "string");
                contentProp.put("description", "当 action 为 write 或 append 时写入的具体内容");
                properties.put("content", contentProp);

                schema.put("properties", properties);
                JSONArray required = new JSONArray();
                required.put("action");
                required.put("path");
                schema.put("required", required);

                return schema;
            } catch (Exception e) {
                return new JSONObject();
            }
        }

        @Override
        public String execute(JSONObject args, Context context) throws Exception {
            String action = args.optString("action", "read");
            String path = args.optString("path", TermuxConstants.TERMUX_HOME_DIR_PATH);
            if ("write".equalsIgnoreCase(action) || "append".equalsIgnoreCase(action)) {
                String contentStr = args.optString("content", "");
                boolean append = "append".equalsIgnoreCase(action);
                return writeFileContent(path, contentStr, append);
            } else if ("list".equalsIgnoreCase(action)) {
                return listDirectory(path);
            } else {
                return readFileContent(path);
            }
        }
    }

    /**
     * 单独 read_file 工具
     */
    public static class ReadFileTool implements McpTool {
        @Override
        public String getName() {
            return "read_file";
        }

        @Override
        public String getDescription() {
            return "读取 Termux 文件系统中的指定文本文件内容。";
        }

        @Override
        public String getPreferenceFilterKey() {
            return TermuxMcpManager.PREF_KEY_TOOL_FILE_OPS;
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
                pathProp.put("description", "要读取的文件绝对路径");
                properties.put("path", pathProp);

                schema.put("properties", properties);
                JSONArray required = new JSONArray();
                required.put("path");
                schema.put("required", required);
                return schema;
            } catch (Exception e) {
                return new JSONObject();
            }
        }

        @Override
        public String execute(JSONObject args, Context context) throws Exception {
            String path = args.optString("path", "");
            return readFileContent(path);
        }
    }

    /**
     * 单独 write_file 工具
     */
    public static class WriteFileTool implements McpTool {
        @Override
        public String getName() {
            return "write_file";
        }

        @Override
        public String getDescription() {
            return "向 Termux 文件系统写入或追加文本内容。如果父目录不存在会自动创建。";
        }

        @Override
        public String getPreferenceFilterKey() {
            return TermuxMcpManager.PREF_KEY_TOOL_FILE_OPS;
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
                pathProp.put("description", "目标文件路径");
                properties.put("path", pathProp);

                JSONObject contentProp = new JSONObject();
                contentProp.put("type", "string");
                contentProp.put("description", "要写入的文本内容");
                properties.put("content", contentProp);

                JSONObject appendProp = new JSONObject();
                appendProp.put("type", "boolean");
                appendProp.put("description", "是否以追加模式写入（默认为 false 覆盖写入）");
                properties.put("append", appendProp);

                schema.put("properties", properties);
                JSONArray required = new JSONArray();
                required.put("path");
                required.put("content");
                schema.put("required", required);
                return schema;
            } catch (Exception e) {
                return new JSONObject();
            }
        }

        @Override
        public String execute(JSONObject args, Context context) throws Exception {
            String path = args.optString("path", "");
            String content = args.optString("content", "");
            boolean append = args.optBoolean("append", false);
            return writeFileContent(path, content, append);
        }
    }

    /**
     * 单独 list_directory 工具
     */
    public static class ListDirectoryTool implements McpTool {
        @Override
        public String getName() {
            return "list_directory";
        }

        @Override
        public String getDescription() {
            return "列出 Termux 文件系统中指定目录下的文件与子目录列表。";
        }

        @Override
        public String getPreferenceFilterKey() {
            return TermuxMcpManager.PREF_KEY_TOOL_FILE_OPS;
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
                pathProp.put("description", "目标目录路径（默认为 Termux Home 目录 ~）");
                properties.put("path", pathProp);

                schema.put("properties", properties);
                return schema;
            } catch (Exception e) {
                return new JSONObject();
            }
        }

        @Override
        public String execute(JSONObject args, Context context) throws Exception {
            String path = args.optString("path", TermuxConstants.TERMUX_HOME_DIR_PATH);
            return listDirectory(path);
        }
    }
}
