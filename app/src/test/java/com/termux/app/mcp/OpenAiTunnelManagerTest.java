package com.termux.app.mcp;

import org.junit.Assert;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 针对 OpenAiTunnelManager 的核心单元测试：
 * 1. 验证 LocalHttpToSocks5Bridge 的 HTTP CONNECT 转换与 SOCKS5 域名透传（ATYP=0x03 远端解析 DNS）；
 * 2. 验证端口与协议探针识别（HTTP、SOCKS5、未开启端口）；
 * 3. 验证日志状态研判逻辑（规避 CONNECTING 假死与精准报错）；
 * 4. 验证 ProxyInfo 数据结构与边界条件。
 */
public class OpenAiTunnelManagerTest {

    @Test
    public void testProxyInfoProperties() {
        OpenAiTunnelManager.ProxyInfo httpProxy = new OpenAiTunnelManager.ProxyInfo("http", "127.0.0.1", 7890, "Clash");
        Assert.assertEquals("http", httpProxy.scheme);
        Assert.assertEquals("127.0.0.1", httpProxy.host);
        Assert.assertEquals(7890, httpProxy.port);
        Assert.assertEquals("http://127.0.0.1:7890", httpProxy.getUrl());
        Assert.assertTrue(httpProxy.toString().contains("Clash"));

        OpenAiTunnelManager.ProxyInfo socksProxy = new OpenAiTunnelManager.ProxyInfo("socks5", "127.0.0.1", 10808, "v2rayNG");
        Assert.assertEquals("socks5://127.0.0.1:10808", socksProxy.getUrl());
    }

    @Test
    public void testProbeProxyPort() throws Exception {
        // 1. 模拟一个 HTTP 代理服务器
        ServerSocket httpMockServer = new ServerSocket(0);
        int httpPort = httpMockServer.getLocalPort();
        Thread httpThread = new Thread(() -> {
            try {
                while (!httpMockServer.isClosed()) {
                    Socket s = httpMockServer.accept();
                    InputStream in = s.getInputStream();
                    OutputStream out = s.getOutputStream();
                    byte[] buf = new byte[1024];
                    int n = in.read(buf);
                    if (n > 0) {
                        out.write("HTTP/1.1 200 Connection Established\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
                        out.flush();
                    }
                    s.close();
                }
            } catch (Exception ignored) {}
        });
        httpThread.start();

        OpenAiTunnelManager.ProxyInfo probedHttp = OpenAiTunnelManager.probeProxyPort("127.0.0.1", httpPort);
        Assert.assertNotNull(probedHttp);
        Assert.assertEquals("http", probedHttp.scheme);
        Assert.assertEquals(httpPort, probedHttp.port);
        httpMockServer.close();

        // 2. 模拟一个 SOCKS5 代理服务器
        ServerSocket socksMockServer = new ServerSocket(0);
        int socksPort = socksMockServer.getLocalPort();
        Thread socksThread = new Thread(() -> {
            try {
                while (!socksMockServer.isClosed()) {
                    Socket s = socksMockServer.accept();
                    InputStream in = s.getInputStream();
                    OutputStream out = s.getOutputStream();
                    byte[] buf = new byte[3];
                    int n = in.read(buf);
                    if (n == 3 && buf[0] == 0x05 && buf[1] == 0x01 && buf[2] == 0x00) {
                        out.write(new byte[]{0x05, 0x00});
                        out.flush();
                    }
                    s.close();
                }
            } catch (Exception ignored) {}
        });
        socksThread.start();

        OpenAiTunnelManager.ProxyInfo probedSocks = OpenAiTunnelManager.probeProxyPort("127.0.0.1", socksPort);
        Assert.assertNotNull(probedSocks);
        Assert.assertEquals("socks5", probedSocks.scheme);
        Assert.assertEquals(socksPort, probedSocks.port);
        socksMockServer.close();

        // 3. 模拟返回 405 Method Not Allowed 的 Web 控制面板（严防误判为代理）
        ServerSocket web405Server = new ServerSocket(0);
        int web405Port = web405Server.getLocalPort();
        Thread web405Thread = new Thread(() -> {
            try {
                while (!web405Server.isClosed()) {
                    Socket s = web405Server.accept();
                    InputStream in = s.getInputStream();
                    OutputStream out = s.getOutputStream();
                    byte[] buf = new byte[1024];
                    int n = in.read(buf);
                    if (n > 0) {
                        out.write("HTTP/1.1 405 Method Not Allowed\r\nAllow: GET\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
                        out.flush();
                    }
                    s.close();
                }
            } catch (Exception ignored) {}
        });
        web405Thread.start();

        OpenAiTunnelManager.ProxyInfo probed405 = OpenAiTunnelManager.probeProxyPort("127.0.0.1", web405Port);
        Assert.assertNull("Web server returning 405 should NOT be recognized as proxy", probed405);
        web405Server.close();

        // 4. 探测未监听端口
        OpenAiTunnelManager.ProxyInfo probedNone = OpenAiTunnelManager.probeProxyPort("127.0.0.1", 59999);
        Assert.assertNull(probedNone);
    }

    @Test
    public void testLocalHttpToSocks5Bridge() throws Exception {
        // 启动一个模拟的上游 SOCKS5 代理服务端
        ServerSocket socksServer = new ServerSocket(0);
        int socksPort = socksServer.getLocalPort();

        AtomicReference<String> requestedDomain = new AtomicReference<>("");
        AtomicReference<Integer> requestedPort = new AtomicReference<>(0);
        CountDownLatch handshakeLatch = new CountDownLatch(1);

        Thread socksWorker = new Thread(() -> {
            try {
                Socket client = socksServer.accept();
                InputStream in = client.getInputStream();
                OutputStream out = client.getOutputStream();

                // 1. 验证 SOCKS5 认证握手
                byte[] authReq = new byte[3];
                in.read(authReq);
                Assert.assertEquals(0x05, authReq[0]);
                Assert.assertEquals(0x01, authReq[1]);
                Assert.assertEquals(0x00, authReq[2]);

                out.write(new byte[]{0x05, 0x00});
                out.flush();

                // 2. 验证 SOCKS5 CONNECT (检查 ATYP=0x03 域名模式)
                byte[] cmdHead = new byte[4];
                in.read(cmdHead);
                Assert.assertEquals(0x05, cmdHead[0]); // VER
                Assert.assertEquals(0x01, cmdHead[1]); // CMD = CONNECT
                Assert.assertEquals(0x00, cmdHead[2]); // RSV
                Assert.assertEquals(0x03, cmdHead[3]); // ATYP = DOMAINNAME (关键！域名由远端解析)

                int dlen = in.read();
                byte[] domainBytes = new byte[dlen];
                in.read(domainBytes);
                requestedDomain.set(new String(domainBytes, StandardCharsets.US_ASCII));

                int pHi = in.read();
                int pLo = in.read();
                int port = ((pHi & 0xFF) << 8) | (pLo & 0xFF);
                requestedPort.set(port);

                // 回复 SOCKS5 CONNECT 成功
                byte[] reply = new byte[]{
                    0x05, 0x00, 0x00, 0x01,
                    127, 0, 0, 1,
                    (byte) ((port >> 8) & 0xFF), (byte) (port & 0xFF)
                };
                out.write(reply);
                out.flush();

                handshakeLatch.countDown();

                // 3. Echo 数据回显
                byte[] echoBuf = new byte[256];
                int n = in.read(echoBuf);
                if (n > 0) {
                    out.write(echoBuf, 0, n);
                    out.flush();
                }

                client.close();
            } catch (Exception ignored) {}
        });
        socksWorker.start();

        // 启动本地桥接
        OpenAiTunnelManager.LocalHttpToSocks5Bridge bridge = new OpenAiTunnelManager.LocalHttpToSocks5Bridge("127.0.0.1", socksPort);
        int bridgePort = bridge.start();
        Assert.assertTrue("Bridge port should be > 0", bridgePort > 0);

        // 客户端发起 HTTP CONNECT 请求至本地桥接
        Socket httpClient = new Socket("127.0.0.1", bridgePort);
        OutputStream clientOut = httpClient.getOutputStream();
        InputStream clientIn = httpClient.getInputStream();

        String connectReq = "CONNECT api.openai.com:443 HTTP/1.1\r\nHost: api.openai.com:443\r\nUser-Agent: test\r\n\r\n";
        clientOut.write(connectReq.getBytes(StandardCharsets.US_ASCII));
        clientOut.flush();

        // 校验上游 SOCKS5 握手并确认收到的目标域名
        boolean ok = handshakeLatch.await(3, TimeUnit.SECONDS);
        Assert.assertTrue("SOCKS5 handshake should complete", ok);
        Assert.assertEquals("api.openai.com", requestedDomain.get());
        Assert.assertEquals(443, requestedPort.get().intValue());

        // 验证客户端收到的 HTTP 响应
        byte[] respBuf = new byte[1024];
        int readLen = clientIn.read(respBuf);
        String respStr = new String(respBuf, 0, readLen, StandardCharsets.US_ASCII);
        Assert.assertTrue("Should receive 200 Connection Established", respStr.contains("200 Connection Established"));

        // 验证全双工数据传输
        String testData = "PING_STREAMABLE_DATA";
        clientOut.write(testData.getBytes(StandardCharsets.UTF_8));
        clientOut.flush();

        byte[] echoResp = new byte[128];
        int echoLen = clientIn.read(echoResp);
        String echoStr = new String(echoResp, 0, echoLen, StandardCharsets.UTF_8);
        Assert.assertEquals("Echoed data should match", testData, echoStr);

        httpClient.close();
        bridge.stop();
        socksServer.close();
    }

    @Test
    public void testInspectLogLineStateTransitions() throws Exception {
        OpenAiTunnelManager manager = OpenAiTunnelManager.getInstance();
        Method inspectMethod = OpenAiTunnelManager.class.getDeclaredMethod("inspectLogLine", String.class);
        inspectMethod.setAccessible(true);

        // 1. 成功连接日志
        inspectMethod.invoke(manager, "starting control-plane poller for tunnel test-id");
        Assert.assertEquals(OpenAiTunnelManager.TunnelState.CONNECTED, manager.getState());

        // 2. DNS 解析拒绝错误日志（解决以往卡在 CONNECTING 的根源）
        inspectMethod.invoke(manager, "failed to dial control plane: dial tcp: lookup api.openai.com on [::1]:53: read: connection refused");
        Assert.assertEquals(OpenAiTunnelManager.TunnelState.ERROR, manager.getState());
        Assert.assertTrue(manager.getLastError().contains("connection refused"));

        // 3. 再次成功恢复
        inspectMethod.invoke(manager, "registered tunnel successfully, listening for requests");
        Assert.assertEquals(OpenAiTunnelManager.TunnelState.CONNECTED, manager.getState());

        // 4. 退避重试失败
        inspectMethod.invoke(manager, "poll failed; backing off for 2.5s: 401 Unauthorized");
        Assert.assertEquals(OpenAiTunnelManager.TunnelState.ERROR, manager.getState());
    }

    @Test
    public void testClearLogs() throws Exception {
        OpenAiTunnelManager manager = OpenAiTunnelManager.getInstance();
        Method appendMethod = OpenAiTunnelManager.class.getDeclaredMethod("appendLog", String.class);
        appendMethod.setAccessible(true);

        appendMethod.invoke(manager, "log line 1");
        appendMethod.invoke(manager, "log line 2");
        Assert.assertTrue(manager.getRecentLogs().contains("log line 1"));

        manager.clearLogs();
        Assert.assertEquals("暂无隧道运行日志", manager.getRecentLogs());
    }

    @Test
    public void testParseAndProbeProxyFormats() {
        // 验证各种用户输入格式的正确解析
        OpenAiTunnelManager.ProxyInfo pi1 = OpenAiTunnelManager.parseAndProbeProxy("10808");
        Assert.assertNotNull(pi1);
        Assert.assertEquals(10808, pi1.port);
        Assert.assertEquals("127.0.0.1", pi1.host);

        OpenAiTunnelManager.ProxyInfo pi2 = OpenAiTunnelManager.parseAndProbeProxy("http://127.0.0.1:7890");
        Assert.assertNotNull(pi2);
        Assert.assertEquals(7890, pi2.port);
        Assert.assertEquals("http", pi2.scheme);

        OpenAiTunnelManager.ProxyInfo pi3 = OpenAiTunnelManager.parseAndProbeProxy("socks5://127.0.0.1:10808");
        Assert.assertNotNull(pi3);
        Assert.assertEquals(10808, pi3.port);
        Assert.assertEquals("socks5", pi3.scheme);

        Assert.assertNull(OpenAiTunnelManager.parseAndProbeProxy(""));
        Assert.assertNull(OpenAiTunnelManager.parseAndProbeProxy(null));
    }

    @Test
    public void testSmartLocalProxyGatewayFailover() throws Exception {
        // 1. 模拟上游 HTTP 代理服务 A
        ServerSocket httpMock = new ServerSocket(0);
        int httpPort = httpMock.getLocalPort();
        System.setProperty("http.proxyHost", "127.0.0.1");
        System.setProperty("http.proxyPort", String.valueOf(httpPort));

        Thread httpWorker = new Thread(() -> {
            try {
                while (!httpMock.isClosed()) {
                    Socket s = httpMock.accept();
                    InputStream in = s.getInputStream();
                    OutputStream out = s.getOutputStream();
                    byte[] buf = new byte[1024];
                    int n = in.read(buf);
                    if (n > 0) {
                        out.write("HTTP/1.1 200 Connection Established\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
                        out.flush();
                        // echo
                        int len = in.read(buf);
                        if (len > 0) {
                            out.write(buf, 0, len);
                            out.flush();
                        }
                    }
                    s.close();
                }
            } catch (Exception ignored) {}
        });
        httpWorker.start();

        // 启动网关
        OpenAiTunnelManager.SmartLocalProxyGateway gateway = new OpenAiTunnelManager.SmartLocalProxyGateway(null);
        int gwPort = gateway.start();
        Assert.assertTrue("Gateway dynamic port should be > 0", gwPort > 0);

        // 客户端发请求给网关，验证连通上游 A
        Socket c1 = new Socket("127.0.0.1", gwPort);
        c1.getOutputStream().write("CONNECT api.openai.com:443 HTTP/1.1\r\nHost: api.openai.com:443\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
        c1.getOutputStream().flush();
        byte[] resp1 = new byte[512];
        int r1 = c1.getInputStream().read(resp1);
        Assert.assertTrue(new String(resp1, 0, r1, StandardCharsets.US_ASCII).contains("200 Connection Established"));
        c1.close();

        // 2. 模拟切换代理：关闭上游 A，开启上游 SOCKS5 服务 B
        httpMock.close();
        System.clearProperty("http.proxyHost");
        System.clearProperty("http.proxyPort");

        ServerSocket socksMock = new ServerSocket(0);
        int socksPort = socksMock.getLocalPort();
        System.setProperty("socksProxyHost", "127.0.0.1");
        System.setProperty("socksProxyPort", String.valueOf(socksPort));

        Thread socksWorker = new Thread(() -> {
            while (!socksMock.isClosed()) {
                try {
                    Socket s = socksMock.accept();
                    new Thread(() -> {
                        try {
                            InputStream in = s.getInputStream();
                            OutputStream out = s.getOutputStream();

                            // SOCKS5 握手
                            int v = in.read();
                            if (v != 0x05) { s.close(); return; }
                            int nm = in.read();
                            if (nm > 0) {
                                byte[] m = new byte[nm];
                                in.read(m);
                            }
                            out.write(new byte[]{0x05, 0x00});
                            out.flush();

                            // SOCKS5 CONNECT
                            byte[] cmdHead = new byte[4];
                            int r = in.read(cmdHead);
                            if (r < 4) { s.close(); return; }
                            if (cmdHead[3] == 0x03) {
                                int dlen = in.read();
                                if (dlen > 0) {
                                    byte[] domainBytes = new byte[dlen];
                                    in.read(domainBytes);
                                }
                            } else if (cmdHead[3] == 0x01) {
                                byte[] ipBytes = new byte[4];
                                in.read(ipBytes);
                            }
                            in.read(); in.read(); // port
                            out.write(new byte[]{0x05, 0x00, 0x00, 0x01, 127, 0, 0, 1, 1, (byte) 187});
                            out.flush();

                            // echo
                            byte[] buf = new byte[256];
                            int len = in.read(buf);
                            if (len > 0) {
                                out.write(buf, 0, len);
                                out.flush();
                            }
                            s.close();
                        } catch (Exception ignored) {
                            try { s.close(); } catch (Exception e) {}
                        }
                    }).start();
                } catch (Exception ignored) {}
            }
        });
        socksWorker.start();

        // 客户端再次发请求给网关，网关应自动识别上游 A 已死，毫秒级故障转移至上游 B！
        Socket c2 = new Socket("127.0.0.1", gwPort);
        c2.getOutputStream().write("CONNECT api.openai.com:443 HTTP/1.1\r\nHost: api.openai.com:443\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
        c2.getOutputStream().flush();
        byte[] resp2 = new byte[512];
        int r2 = c2.getInputStream().read(resp2);
        Assert.assertTrue(new String(resp2, 0, r2, StandardCharsets.US_ASCII).contains("200 Connection Established"));

        // 发送数据测试回显
        c2.getOutputStream().write("HELLO_FAILOVER".getBytes(StandardCharsets.UTF_8));
        c2.getOutputStream().flush();
        byte[] echoBuf = new byte[128];
        int echoLen = c2.getInputStream().read(echoBuf);
        Assert.assertEquals("HELLO_FAILOVER", new String(echoBuf, 0, echoLen, StandardCharsets.UTF_8));

        c2.close();
        gateway.stop();
        socksMock.close();
        System.clearProperty("socksProxyHost");
        System.clearProperty("socksProxyPort");
    }
}
