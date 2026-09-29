package com.fongmi.php;

import android.util.Log;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Enumeration;

/**
 * 本地 PHP 服务器（监听所有接口随机端口）。
 * 收到请求时直接执行对应 PHP 文件，返回原始输出。
 */
public class PhpServer {

    private static final String TAG = "PhpServer";
    private static final byte[] END = "\r\n\r\n".getBytes(StandardCharsets.US_ASCII);

    private static PhpServer instance;
    private ServerSocket socket;
    private Thread thread;
    private volatile boolean running;

    private PhpServer() {
    }

    public static synchronized PhpServer get() {
        if (instance == null) instance = new PhpServer();
        return instance;
    }

    /**
     * 将 PHP 脚本写入本地目录并返回可访问的 URL。
     */
    public synchronized String register(String scriptName, String scriptContent, String queryString) throws IOException {
        start();
        writeScript(scriptName, scriptContent);
        String host = getLocalIpAddress();
        String url = "http://" + host + ":" + socket.getLocalPort() + "/" + scriptName;
        if (queryString != null && !queryString.isEmpty()) url += "?" + queryString;
        Log.i(TAG, "注册脚本 " + scriptName + " -> " + url);
        return url;
    }

    /**
     * 预热：直接执行一次 PHP 脚本，让 native 库和 cURL 加载完成。
     * 返回脚本输出内容（用于确认预热成功）。
     */
    public String warmUp(String scriptName, String queryString) throws IOException {
        java.io.File scriptFile = new java.io.File(PhpEnv.getScriptDir(), scriptName);
        if (!scriptFile.exists()) throw new IOException("脚本不存在: " + scriptName);
        String result = PhpBridge.runPhpFile(
                scriptFile.getAbsolutePath(),
                PhpEnv.getIniPath(),
                "GET",
                queryString == null ? "" : queryString,
                ""
        );
        return result == null ? "" : result.trim();
    }

    private void writeScript(String name, String content) throws IOException {
        java.io.File dir = PhpEnv.getScriptDir();
        java.io.File file = new java.io.File(dir, name);
        try (java.io.FileOutputStream out = new java.io.FileOutputStream(file)) {
            out.write(content.getBytes(StandardCharsets.UTF_8));
        }
    }

    private String getLocalIpAddress() {
        try {
            String preferred = null;
            Enumeration<NetworkInterface> ifaces = NetworkInterface.getNetworkInterfaces();
            while (ifaces.hasMoreElements()) {
                NetworkInterface iface = ifaces.nextElement();
                if (iface.isLoopback() || !iface.isUp()) continue;
                Enumeration<InetAddress> addrs = iface.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    InetAddress addr = addrs.nextElement();
                    if (addr.isLoopbackAddress() || !(addr instanceof Inet4Address)) continue;
                    String ip = addr.getHostAddress();
                    Log.i(TAG, "网络接口 " + iface.getName() + " -> " + ip);
                    // 优先 192.168.x.x
                    if (ip.startsWith("192.168.")) return ip;
                    // 其次 10.x.x.x
                    if (preferred == null || ip.startsWith("10.")) preferred = ip;
                }
            }
            if (preferred != null) return preferred;
        } catch (Exception e) {
            Log.e(TAG, "获取本机IP失败", e);
        }
        return "127.0.0.1";
    }

    private synchronized void start() throws IOException {
        if (running) return;
        running = true;
        socket = new ServerSocket(0, 50, InetAddress.getByName("0.0.0.0"));
        thread = new Thread(this::acceptLoop, "php-server");
        thread.setDaemon(true);
        thread.start();
        Log.i(TAG, "PHP 服务器启动于端口 " + socket.getLocalPort());
    }

    private void acceptLoop() {
        while (running) {
            try {
                Socket client = socket.accept();
                new Thread(() -> {
                    try {
                        handle(client);
                    } finally {
                        try { client.close(); } catch (Exception ignored) {}
                    }
                }, "php-handler").start();
            } catch (IOException ignored) {
                if (!running) break;
            }
        }
    }

    private void handle(Socket client) {
        OutputStream output = null;
        try {
            client.setSoTimeout(30000);
            InputStream input = client.getInputStream();
            output = client.getOutputStream();

            String requestLine = readRequestLine(input);
            if (requestLine == null || !requestLine.startsWith("GET ")) return;

            Log.i(TAG, "请求: " + requestLine);

            String path = requestLine.substring(4).split(" ")[0];
            String queryString = "";
            int qIndex = path.indexOf('?');
            String scriptName;
            if (qIndex >= 0) {
                scriptName = path.substring(1, qIndex);
                queryString = path.substring(qIndex + 1);
            } else {
                scriptName = path.substring(1);
            }

            // 直接执行 PHP 文件
            java.io.File scriptFile = new java.io.File(PhpEnv.getScriptDir(), scriptName);
            if (!scriptFile.exists()) {
                sendResponse(output, 404, "text/plain", "Not found".getBytes(StandardCharsets.UTF_8));
                return;
            }

            long t0 = System.currentTimeMillis();
            String result = PhpBridge.runPhpFile(
                    scriptFile.getAbsolutePath(),
                    PhpEnv.getIniPath(),
                    "GET",
                    queryString,
                    ""
            );
            long t1 = System.currentTimeMillis();
            Log.i(TAG, "PHP执行耗时: " + (t1 - t0) + "ms");

            if (result == null) result = "";
            result = result.trim();

            // 打印返回内容前200字符，方便排查
            Log.i(TAG, "返回内容前200字符: " + result.substring(0, Math.min(result.length(), 200)));

            String contentType = result.startsWith("#EXTM3U")
                    ? "application/vnd.apple.mpegurl"
                    : "text/html; charset=utf-8";
            Log.i(TAG, "返回 " + contentType + ", 长度=" + result.length());
            sendResponse(output, 200, contentType, result.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            Log.e(TAG, "处理请求失败", e);
            if (output != null) {
                try {
                    sendResponse(output, 500, "text/plain", ("PHP error: " + e.getMessage()).getBytes(StandardCharsets.UTF_8));
                } catch (Exception ignored) {
                }
            }
        } finally {
            try { client.close(); } catch (Exception ignored) {}
        }
    }

    private String readRequestLine(InputStream input) throws IOException {
        StringBuilder sb = new StringBuilder();
        int prev = -1;
        while (true) {
            int b = input.read();
            if (b < 0) return null;
            if (b == '\n' && prev == '\r') {
                sb.setLength(sb.length() - 1);
                break;
            }
            sb.append((char) b);
            prev = b;
            if (sb.length() > 8192) return null;
        }
        readHeaders(input);
        return sb.toString();
    }

    private void readHeaders(InputStream input) throws IOException {
        StringBuilder headers = new StringBuilder();
        int total = 0, matched = 0;
        while (total < 16384) {
            int b = input.read();
            if (b < 0) break;
            total++;
            headers.append((char) b);
            if (b == END[matched]) {
                matched++;
                if (matched == END.length) break;
            } else {
                matched = b == END[0] ? 1 : 0;
            }
        }
        if (headers.length() > 0) Log.i(TAG, "请求头: " + headers.toString().trim());
    }

    private void sendResponse(OutputStream output, int code, String contentType, byte[] body) throws IOException {
        String header = "HTTP/1.1 " + code + " OK\r\n"
                + "Content-Type: " + contentType + "\r\n"
                + "Content-Length: " + body.length + "\r\n"
                + "Cache-Control: no-cache, no-store, must-revalidate\r\n"
                + "Access-Control-Allow-Origin: *\r\n"
                + "Connection: close\r\n\r\n";
        output.write(header.getBytes(StandardCharsets.US_ASCII));
        output.write(body);
        output.flush();
    }
}
