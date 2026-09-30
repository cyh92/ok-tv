package com.fongmi.php;

import android.util.Log;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 本地 PHP 服务器（仅监听 127.0.0.1 随机端口，不对外暴露）。
 * 收到请求时直接执行对应 PHP 文件，返回原始输出。
 *
 * 注意：请求由**单线程**工作队列串行处理。PHP embed 运行时是全局单例且非线程安全，
 * 并发执行会踩坏内存直接崩掉进程，所以这里不允许并发。
 */
public class PhpServer {

    private static final String TAG = "PhpServer";
    private static final byte[] END = "\r\n\r\n".getBytes(StandardCharsets.US_ASCII);

    /** 读取请求的超时：只用于防止半个连接把唯一的 worker 卡死 */
    private static final int SO_TIMEOUT_MS = 10000;

    private static PhpServer instance;
    private ServerSocket socket;
    private Thread thread;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "php-handler");
        t.setDaemon(true);
        return t;
    });
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
        String url = "http://127.0.0.1:" + socket.getLocalPort() + "/" + scriptName;
        if (queryString != null && !queryString.isEmpty()) url += "?" + queryString;
        Log.i(TAG, "注册脚本 " + scriptName + " -> " + url);
        return url;
    }

    private void writeScript(String name, String content) throws IOException {
        java.io.File dir = PhpEnv.getScriptDir();
        java.io.File file = new java.io.File(dir, name);
        try (java.io.FileOutputStream out = new java.io.FileOutputStream(file)) {
            out.write(content.getBytes(StandardCharsets.UTF_8));
        }
    }

    private synchronized void start() throws IOException {
        if (running) return;
        running = true;
        socket = new ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"));
        thread = new Thread(this::acceptLoop, "php-server");
        thread.setDaemon(true);
        thread.start();
        Log.i(TAG, "PHP 服务器启动于 127.0.0.1:" + socket.getLocalPort());
    }

    private void acceptLoop() {
        while (running) {
            try {
                Socket client = socket.accept();
                // 交给单线程 worker 串行处理：既避免并发跑 PHP，也避免线程无限堆积
                worker.execute(() -> handle(client));
            } catch (IOException ignored) {
                if (!running) break;
            }
        }
    }

    private void handle(Socket client) {
        OutputStream output = null;
        try {
            client.setSoTimeout(SO_TIMEOUT_MS);
            InputStream input = client.getInputStream();
            output = client.getOutputStream();

            String requestLine = readRequestLine(input);
            if (requestLine == null) return;
            if (!requestLine.startsWith("GET ")) {
                Log.w(TAG, "不支持的方法: " + requestLine);
                sendResponse(output, 405, "text/plain", "Method Not Allowed".getBytes(StandardCharsets.UTF_8));
                return;
            }

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
            String result = PhpBridge.runPhpFileExclusive(
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
        String header = "HTTP/1.1 " + code + " " + reason(code) + "\r\n"
                + "Content-Type: " + contentType + "\r\n"
                + "Content-Length: " + body.length + "\r\n"
                + "Cache-Control: no-cache, no-store, must-revalidate\r\n"
                + "Access-Control-Allow-Origin: *\r\n"
                + "Connection: close\r\n\r\n";
        output.write(header.getBytes(StandardCharsets.US_ASCII));
        output.write(body);
        output.flush();
    }

    private static String reason(int code) {
        switch (code) {
            case 200: return "OK";
            case 404: return "Not Found";
            case 405: return "Method Not Allowed";
            case 500: return "Internal Server Error";
            default: return "OK";
        }
    }
}
