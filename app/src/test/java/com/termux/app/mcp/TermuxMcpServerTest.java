package com.termux.app.mcp;

import org.json.JSONObject;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * 针对 MCP Streamable HTTP 协议、会话生命周期、UTF-8 编码与代理环境的完整单元与集成测试套件。
 */
public class TermuxMcpServerTest {

    private static int sPort;
    private static TermuxMcpServer sServer;

    @BeforeClass
    public static void setUp() throws Exception {
        // 分配空闲测试端口
        try (ServerSocket ss = new ServerSocket(0)) {
            sPort = ss.getLocalPort();
        }
        sServer = new TermuxMcpServer(null, sPort, "");
        sServer.start();
        Thread.sleep(300); // 确保监听线程就绪
    }

    @AfterClass
    public static void tearDown() {
        if (sServer != null) {
            sServer.stop();
        }
    }

    private static class HttpResponse {
        final int statusCode;
        final String statusMessage;
        final Map<String, String> headers;
        final String body;

        HttpResponse(int statusCode, String statusMessage, Map<String, String> headers, String body) {
            this.statusCode = statusCode;
            this.statusMessage = statusMessage;
            this.headers = headers;
            this.body = body;
        }
    }

    /**
     * 底层原生 Socket HTTP 请求发送器（真实模拟客户端与 tunnel-client 转发）
     */
    private static HttpResponse sendRawHttpRequest(String method, String path, Map<String, String> headers, String body) throws IOException {
        try (Socket socket = new Socket("127.0.0.1", sPort)) {
            socket.setSoTimeout(5000);
            OutputStream out = socket.getOutputStream();
            BufferedInputStream in = new BufferedInputStream(socket.getInputStream());

            byte[] bodyBytes = (body != null) ? body.getBytes(StandardCharsets.UTF_8) : new byte[0];

            StringBuilder req = new StringBuilder();
            req.append(method).append(" ").append(path).append(" HTTP/1.1\r\n");
            req.append("Host: 127.0.0.1:").append(sPort).append("\r\n");
            req.append("Content-Length: ").append(bodyBytes.length).append("\r\n");
            if (headers != null) {
                for (Map.Entry<String, String> entry : headers.entrySet()) {
                    req.append(entry.getKey()).append(": ").append(entry.getValue()).append("\r\n");
                }
            }
            req.append("Connection: close\r\n\r\n");

            out.write(req.toString().getBytes(StandardCharsets.UTF_8));
            if (bodyBytes.length > 0) {
                out.write(bodyBytes);
            }
            out.flush();

            // 解析状态行
            String statusLine = readLine(in);
            if (statusLine == null || statusLine.isEmpty()) {
                throw new IOException("Empty status line from server");
            }
            String[] statusParts = statusLine.split(" ", 3);
            int statusCode = Integer.parseInt(statusParts[1]);
            String statusMsg = statusParts.length >= 3 ? statusParts[2] : "";

            // 解析响应头
            Map<String, String> respHeaders = new HashMap<>();
            String headerLine;
            int respContentLength = -1;
            while ((headerLine = readLine(in)) != null && !headerLine.isEmpty()) {
                int cIdx = headerLine.indexOf(':');
                if (cIdx != -1) {
                    String hName = headerLine.substring(0, cIdx).trim().toLowerCase();
                    String hVal = headerLine.substring(cIdx + 1).trim();
                    respHeaders.put(hName, hVal);
                    if ("content-length".equals(hName)) {
                        try {
                            respContentLength = Integer.parseInt(hVal);
                        } catch (Exception ignored) {}
                    }
                }
            }

            // 读取响应体
            String respBody = "";
            if (respContentLength > 0) {
                byte[] buf = new byte[respContentLength];
                int total = 0;
                while (total < respContentLength) {
                    int r = in.read(buf, total, respContentLength - total);
                    if (r == -1) break;
                    total += r;
                }
                respBody = new String(buf, 0, total, StandardCharsets.UTF_8);
            } else if (respContentLength == -1) {
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                byte[] buf = new byte[1024];
                int r;
                while ((r = in.read(buf)) != -1) {
                    baos.write(buf, 0, r);
                }
                respBody = baos.toString("UTF-8");
            }

            return new HttpResponse(statusCode, statusMsg, respHeaders, respBody);
        }
    }

    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        int b;
        while ((b = in.read()) != -1) {
            if (b == '\n') break;
            if (b != '\r') baos.write(b);
        }
        if (b == -1 && baos.size() == 0) return null;
        return baos.toString("ISO-8859-1");
    }

    @Test
    public void testInitializeGeneratesSessionIdAndExposesHeader() throws Exception {
        String initPayload = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"2024-11-05\"}}";
        Map<String, String> headers = new HashMap<>();
        headers.put("Content-Type", "application/json");

        HttpResponse resp = sendRawHttpRequest("POST", "/mcp", headers, initPayload);
        Assert.assertEquals(200, resp.statusCode);

        // 验证 Mcp-Session-Id 与 CORS Expose Header
        String sessionId = resp.headers.get("mcp-session-id");
        Assert.assertNotNull("Mcp-Session-Id 响应头必须非空", sessionId);
        Assert.assertFalse("Mcp-Session-Id 响应头不能为空字符串", sessionId.trim().isEmpty());

        String exposeHeaders = resp.headers.get("access-control-expose-headers");
        Assert.assertNotNull(exposeHeaders);
        Assert.assertTrue(exposeHeaders.contains("Mcp-Session-Id"));

        // 验证 JSON 响应内容
        JSONObject json = new JSONObject(resp.body);
        Assert.assertEquals("2.0", json.getString("jsonrpc"));
        Assert.assertEquals(1, json.getInt("id"));
        JSONObject result = json.getJSONObject("result");
        Assert.assertEquals("2024-11-05", result.getString("protocolVersion"));
        Assert.assertTrue(result.getJSONObject("capabilities").getJSONObject("tools").getBoolean("listChanged"));
        Assert.assertEquals("Termux+ Built-in MCP Server", result.getJSONObject("serverInfo").getString("name"));
    }

    @Test
    public void testMissingSessionIdReturns400() throws Exception {
        String pingPayload = "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"ping\"}";
        Map<String, String> headers = new HashMap<>();
        headers.put("Content-Type", "application/json");

        // 未携带 Mcp-Session-Id
        HttpResponse resp = sendRawHttpRequest("POST", "/mcp", headers, pingPayload);
        Assert.assertEquals(400, resp.statusCode);
        Assert.assertTrue(resp.body.contains("Missing Mcp-Session-Id"));
    }

    @Test
    public void testInvalidSessionIdReturns404() throws Exception {
        String pingPayload = "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"ping\"}";
        Map<String, String> headers = new HashMap<>();
        headers.put("Content-Type", "application/json");
        headers.put("Mcp-Session-Id", "invalid-uuid-99999");

        // 携带无效/不存在的 Mcp-Session-Id
        HttpResponse resp = sendRawHttpRequest("POST", "/mcp", headers, pingPayload);
        Assert.assertEquals(404, resp.statusCode);
        Assert.assertTrue(resp.body.contains("Session not found or expired"));
    }

    @Test
    public void testNotificationInitializedReturns202EmptyBody() throws Exception {
        // 1. 初始化会话
        String initPayload = "{\"jsonrpc\":\"2.0\",\"id\":10,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"2024-11-05\"}}";
        HttpResponse initResp = sendRawHttpRequest("POST", "/mcp", null, initPayload);
        String sessionId = initResp.headers.get("mcp-session-id");
        Assert.assertNotNull(sessionId);

        // 2. 发送 notifications/initialized (无 id)
        String notifPayload = "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}";
        Map<String, String> headers = new HashMap<>();
        headers.put("Mcp-Session-Id", sessionId);
        headers.put("Content-Type", "application/json");

        HttpResponse notifResp = sendRawHttpRequest("POST", "/mcp", headers, notifPayload);
        Assert.assertEquals(202, notifResp.statusCode);
        Assert.assertEquals("0", notifResp.headers.get("content-length"));
        Assert.assertEquals("", notifResp.body);
        Assert.assertEquals(sessionId, notifResp.headers.get("mcp-session-id"));
    }

    @Test
    public void testSessionPersistenceAcrossCalls() throws Exception {
        // 1. 初始化会话
        String initPayload = "{\"jsonrpc\":\"2.0\",\"id\":20,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"2024-11-05\"}}";
        HttpResponse initResp = sendRawHttpRequest("POST", "/mcp", null, initPayload);
        String sessionId = initResp.headers.get("mcp-session-id");

        // 2. 使用相同的 session 发送 ping
        Map<String, String> headers = new HashMap<>();
        headers.put("Mcp-Session-Id", sessionId);
        String pingPayload = "{\"jsonrpc\":\"2.0\",\"id\":21,\"method\":\"ping\"}";
        HttpResponse pingResp = sendRawHttpRequest("POST", "/mcp", headers, pingPayload);
        Assert.assertEquals(200, pingResp.statusCode);
        JSONObject pingJson = new JSONObject(pingResp.body);
        Assert.assertEquals(21, pingJson.getInt("id"));

        // 3. 使用相同的 session 发送 tools/list
        String listPayload = "{\"jsonrpc\":\"2.0\",\"id\":22,\"method\":\"tools/list\"}";
        HttpResponse listResp = sendRawHttpRequest("POST", "/mcp", headers, listPayload);
        Assert.assertEquals(200, listResp.statusCode);
        JSONObject listJson = new JSONObject(listResp.body);
        Assert.assertEquals(22, listJson.getInt("id"));
        Assert.assertTrue(listJson.getJSONObject("result").has("tools"));
    }

    @Test
    public void testUtf8PayloadHandlingByteAccurate() throws Exception {
        // 1. 初始化会话
        String initPayload = "{\"jsonrpc\":\"2.0\",\"id\":30,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"2024-11-05\"}}";
        HttpResponse initResp = sendRawHttpRequest("POST", "/mcp", null, initPayload);
        String sessionId = initResp.headers.get("mcp-session-id");

        // 2. 发送包含多字节 UTF-8 中文与 Emoji 的复杂参数（验证字节级读取不会发生长度失配阻塞）
        String utf8Payload = "{\"jsonrpc\":\"2.0\",\"id\":31,\"method\":\"ping\",\"params\":{\"msg\":\"你好，手机终端！⚡🚀 这是一个多字节 UTF-8 测试\"}}";
        Map<String, String> headers = new HashMap<>();
        headers.put("Mcp-Session-Id", sessionId);
        headers.put("Content-Type", "application/json; charset=utf-8");

        long start = System.currentTimeMillis();
        HttpResponse utf8Resp = sendRawHttpRequest("POST", "/mcp", headers, utf8Payload);
        long elapsed = System.currentTimeMillis() - start;

        // 验证读取在几毫秒内完成，绝对没有发生 5000ms/60000ms 的超时阻塞
        Assert.assertTrue("UTF-8 请求处理超时，说明可能发生了字符/字节计数失配阻塞", elapsed < 3000);
        Assert.assertEquals(200, utf8Resp.statusCode);
        JSONObject respJson = new JSONObject(utf8Resp.body);
        Assert.assertEquals(31, respJson.getInt("id"));
    }

    @Test
    public void testAcceptHeaderNegotiation() throws Exception {
        // 1. 初始化会话
        String initPayload = "{\"jsonrpc\":\"2.0\",\"id\":40,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"2024-11-05\"}}";
        HttpResponse initResp = sendRawHttpRequest("POST", "/mcp", null, initPayload);
        String sessionId = initResp.headers.get("mcp-session-id");

        // 2. 显式 Accept: application/json -> 必须返回 application/json
        Map<String, String> jsonHeaders = new HashMap<>();
        jsonHeaders.put("Mcp-Session-Id", sessionId);
        jsonHeaders.put("Accept", "application/json");
        String pingPayload = "{\"jsonrpc\":\"2.0\",\"id\":41,\"method\":\"ping\"}";
        HttpResponse jsonResp = sendRawHttpRequest("POST", "/mcp", jsonHeaders, pingPayload);
        Assert.assertEquals(200, jsonResp.statusCode);
        Assert.assertTrue(jsonResp.headers.get("content-type").contains("application/json"));

        // 3. 显式且排他性 Accept: text/event-stream -> 必须以 text/event-stream 分块流输出
        Map<String, String> sseHeaders = new HashMap<>();
        sseHeaders.put("Mcp-Session-Id", sessionId);
        sseHeaders.put("Accept", "text/event-stream");
        HttpResponse sseResp = sendRawHttpRequest("POST", "/mcp", sseHeaders, pingPayload);
        Assert.assertEquals(200, sseResp.statusCode);
        Assert.assertTrue(sseResp.headers.get("content-type").contains("text/event-stream"));
        Assert.assertTrue(sseResp.body.contains("event: message"));
        Assert.assertTrue(sseResp.body.contains("data:"));
    }

    @Test
    public void testDeleteMcpSession() throws Exception {
        // 1. 初始化会话
        String initPayload = "{\"jsonrpc\":\"2.0\",\"id\":50,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"2024-11-05\"}}";
        HttpResponse initResp = sendRawHttpRequest("POST", "/mcp", null, initPayload);
        String sessionId = initResp.headers.get("mcp-session-id");

        // 2. 发送 DELETE /mcp
        Map<String, String> headers = new HashMap<>();
        headers.put("Mcp-Session-Id", sessionId);
        HttpResponse delResp = sendRawHttpRequest("DELETE", "/mcp", headers, null);
        Assert.assertEquals(204, delResp.statusCode);

        // 3. 再次使用该 session ID 发送请求，必须返回 404
        String pingPayload = "{\"jsonrpc\":\"2.0\",\"id\":51,\"method\":\"ping\"}";
        HttpResponse afterDelResp = sendRawHttpRequest("POST", "/mcp", headers, pingPayload);
        Assert.assertEquals(404, afterDelResp.statusCode);
    }

    @Test
    public void testOpenAiTunnelProxySettings() {
        // 测试 SOCKS5 代理识别
        String socksInput = "socks5://127.0.0.1:10808";
        String cleanSocks = socksInput.replace("socks5://", "");
        String[] parts = cleanSocks.split(":");
        OpenAiTunnelManager.ProxyInfo socksProxy = new OpenAiTunnelManager.ProxyInfo("socks5", parts[0], Integer.parseInt(parts[1]), "SOCKS5测试");
        Assert.assertEquals("socks5", socksProxy.scheme);
        Assert.assertEquals("socks5://127.0.0.1:10808", socksProxy.getUrl());

        // 测试 HTTP 代理识别
        String httpInput = "http://127.0.0.1:7890";
        String cleanHttp = httpInput.replace("http://", "");
        String[] httpParts = cleanHttp.split(":");
        OpenAiTunnelManager.ProxyInfo httpProxy = new OpenAiTunnelManager.ProxyInfo("http", httpParts[0], Integer.parseInt(httpParts[1]), "HTTP测试");
        Assert.assertEquals("http", httpProxy.scheme);
        Assert.assertEquals("http://127.0.0.1:7890", httpProxy.getUrl());
    }

    @Test
    public void testLegacySseAndMessages() throws Exception {
        // 1. 测试 GET /sse 建立连接并获取 endpoint
        try (Socket sseSocket = new Socket("127.0.0.1", sPort)) {
            sseSocket.setSoTimeout(5000);
            OutputStream out = sseSocket.getOutputStream();
            BufferedInputStream in = new BufferedInputStream(sseSocket.getInputStream());

            String req = "GET /sse HTTP/1.1\r\nHost: 127.0.0.1:" + sPort + "\r\nAccept: text/event-stream\r\n\r\n";
            out.write(req.getBytes(StandardCharsets.UTF_8));
            out.flush();

            String statusLine = readLine(in);
            Assert.assertNotNull(statusLine);
            Assert.assertTrue(statusLine.contains("200"));

            // 读取 headers 直至空行
            String h;
            while ((h = readLine(in)) != null && !h.isEmpty()) {}

            // 读取 SSE event
            String line1 = readLine(in); // event: endpoint
            String line2 = readLine(in); // data: /messages?sessionId=...
            Assert.assertNotNull(line1);
            Assert.assertTrue(line1.contains("event: endpoint"));
            Assert.assertNotNull(line2);
            Assert.assertTrue(line2.contains("/messages?sessionId="));

            String sessionId = line2.substring(line2.indexOf("sessionId=") + "sessionId=".length()).trim();
            Assert.assertFalse(sessionId.isEmpty());

            // 2. 向 POST /messages?sessionId=xxx 发送请求
            String msgPayload = "{\"jsonrpc\":\"2.0\",\"id\":60,\"method\":\"ping\"}";
            HttpResponse msgResp = sendRawHttpRequest("POST", "/messages?sessionId=" + sessionId, null, msgPayload);
            Assert.assertEquals(200, msgResp.statusCode);
            JSONObject json = new JSONObject(msgResp.body);
            Assert.assertEquals(60, json.getInt("id"));
        }
    }

    @Test
    public void testMcpGetSseValidation() throws Exception {
        // 1. 未携带 Mcp-Session-Id -> 400
        HttpResponse respNoSession = sendRawHttpRequest("GET", "/mcp", null, null);
        Assert.assertEquals(400, respNoSession.statusCode);

        // 2. 携带无效 Mcp-Session-Id -> 404
        Map<String, String> badHeaders = new HashMap<>();
        badHeaders.put("Mcp-Session-Id", "fake-session-999");
        HttpResponse respBadSession = sendRawHttpRequest("GET", "/mcp", badHeaders, null);
        Assert.assertEquals(404, respBadSession.statusCode);

        // 3. 有效 session -> 建立 GET /mcp SSE 连接并验证返回 200 text/event-stream
        String initPayload = "{\"jsonrpc\":\"2.0\",\"id\":70,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"2024-11-05\"}}";
        HttpResponse initResp = sendRawHttpRequest("POST", "/mcp", null, initPayload);
        String sessionId = initResp.headers.get("mcp-session-id");
        Assert.assertNotNull(sessionId);

        try (Socket sseSocket = new Socket("127.0.0.1", sPort)) {
            sseSocket.setSoTimeout(5000);
            OutputStream out = sseSocket.getOutputStream();
            BufferedInputStream in = new BufferedInputStream(sseSocket.getInputStream());

            String req = "GET /mcp HTTP/1.1\r\nHost: 127.0.0.1:" + sPort + "\r\nMcp-Session-Id: " + sessionId + "\r\nAccept: text/event-stream\r\n\r\n";
            out.write(req.getBytes(StandardCharsets.UTF_8));
            out.flush();

            String statusLine = readLine(in);
            Assert.assertNotNull(statusLine);
            Assert.assertTrue(statusLine.contains("200"));
        }
    }
}
