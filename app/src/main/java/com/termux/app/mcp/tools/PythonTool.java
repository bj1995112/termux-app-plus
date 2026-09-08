package com.termux.app.mcp.tools;

import android.content.Context;

import com.termux.app.mcp.TermuxMcpManager;
import com.termux.shared.termux.TermuxConstants;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Python 代码与脚本执行工具：
 * 支持直接执行 Python 代码片段或外部 .py 脚本文件，原生捕获 stdout/stderr/exit_code/duration。
 */
public class PythonTool implements McpTool {

    @Override
    public String getName() {
        return "python_run";
    }

    @Override
    public String getDescription() {
        return "在 Termux 环境中执行 Python 代码或 Python 脚本文件。支持 code 源码字符串或 file 脚本路径，支持传入参数，捕获完整 stdout/stderr、退出码与执行耗时。";
    }

    @Override
    public String getPreferenceFilterKey() {
        return TermuxMcpManager.PREF_KEY_TOOL_PYTHON;
    }

    @Override
    public JSONObject getInputSchema() {
        try {
            JSONObject schema = new JSONObject();
            schema.put("type", "object");
            schema.put("$schema", "http://json-schema.org/draft-07/schema#");

            JSONObject properties = new JSONObject();

            JSONObject codeProp = new JSONObject();
            codeProp.put("type", "string");
            codeProp.put("description", "要直接执行的 Python 代码字符串（如 print('hello')）");
            properties.put("code", codeProp);

            JSONObject fileProp = new JSONObject();
            fileProp.put("type", "string");
            fileProp.put("description", "要执行的 Python 脚本文件绝对路径（如 /data/data/com.termux/files/home/test.py）");
            properties.put("file", fileProp);

            JSONObject argsProp = new JSONObject();
            argsProp.put("type", "array");
            JSONObject itemsProp = new JSONObject();
            itemsProp.put("type", "string");
            argsProp.put("items", itemsProp);
            argsProp.put("description", "传递给 Python 脚本的命令行参数列表（可选）");
            properties.put("args", argsProp);

            JSONObject cwdProp = new JSONObject();
            cwdProp.put("type", "string");
            cwdProp.put("description", "执行目录（可选，默认 Termux 用户家目录）");
            properties.put("cwd", cwdProp);

            JSONObject timeoutProp = new JSONObject();
            timeoutProp.put("type", "integer");
            timeoutProp.put("description", "执行超时时间（毫秒，默认遵循服务端配置，如 60000）");
            properties.put("timeout_ms", timeoutProp);

            schema.put("properties", properties);
            return schema;
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    @Override
    public String execute(JSONObject args, Context context) throws Exception {
        String code = args.optString("code", "");
        String file = args.optString("file", "");
        String cwdStr = args.optString("cwd", TermuxConstants.TERMUX_HOME_DIR_PATH);

        int defaultTimeoutMs = TermuxMcpManager.getInstance().getExecTimeoutSec(context) * 1000;
        int timeoutMs = args.optInt("timeout_ms", defaultTimeoutMs);
        if (timeoutMs <= 0) {
            timeoutMs = defaultTimeoutMs;
        }

        if (code.trim().isEmpty() && file.trim().isEmpty()) {
            JSONObject err = new JSONObject();
            err.put("success", false);
            err.put("stdout", "");
            err.put("stderr", "错误：必须提供 code（Python 代码）或 file（脚本路径）参数");
            err.put("exit_code", -1);
            err.put("duration", "0.000s");
            return err.toString();
        }

        // 探测 Python 解释器二进制
        File py3Bin = new File(TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH, "python3");
        File pyBin = new File(TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH, "python");
        String pythonExe = null;
        if (py3Bin.exists()) {
            pythonExe = py3Bin.getAbsolutePath();
        } else if (pyBin.exists()) {
            pythonExe = pyBin.getAbsolutePath();
        } else {
            JSONObject err = new JSONObject();
            err.put("success", false);
            err.put("stdout", "");
            err.put("stderr", "未在 Termux 中检测到 Python 解释器。请在终端执行 'pkg install -y python' 安装。");
            err.put("exit_code", -1);
            err.put("duration", "0.000s");
            return err.toString();
        }

        File tempScriptFile = null;
        try {
            StringBuilder cmdBuilder = new StringBuilder();
            cmdBuilder.append(pythonExe);

            if (!code.trim().isEmpty()) {
                // 通过临时文件安全执行代码，彻底规避引号转义引发的 Shell 注入与截断问题
                File tmpDir = new File(TermuxConstants.TERMUX_TMP_PREFIX_DIR_PATH);
                if (!tmpDir.exists()) {
                    tmpDir.mkdirs();
                }
                tempScriptFile = new File(tmpDir, "mcp_py_" + UUID.randomUUID().toString() + ".py");
                try (FileOutputStream fos = new FileOutputStream(tempScriptFile)) {
                    fos.write(code.getBytes(StandardCharsets.UTF_8));
                }
                cmdBuilder.append(" \"").append(tempScriptFile.getAbsolutePath()).append("\"");
            } else {
                File script = new File(file);
                if (!script.exists()) {
                    JSONObject err = new JSONObject();
                    err.put("success", false);
                    err.put("stdout", "");
                    err.put("stderr", "指定的 Python 脚本文件不存在: " + file);
                    err.put("exit_code", -1);
                    err.put("duration", "0.000s");
                    return err.toString();
                }
                cmdBuilder.append(" \"").append(file).append("\"");
            }

            // 附加额外运行参数
            JSONArray extraArgs = args.optJSONArray("args");
            if (extraArgs != null) {
                for (int i = 0; i < extraArgs.length(); i++) {
                    String arg = extraArgs.optString(i, "");
                    // 转义双引号
                    String escapedArg = arg.replace("\"", "\\\"");
                    cmdBuilder.append(" \"").append(escapedArg).append("\"");
                }
            }

            ShellTool.CommandResult res = ShellTool.run(cmdBuilder.toString(), cwdStr, timeoutMs);
            return res.toJsonString();

        } finally {
            if (tempScriptFile != null && tempScriptFile.exists()) {
                //noinspection ResultOfMethodCallIgnored
                tempScriptFile.delete();
            }
        }
    }
}
