package com.fongmi.php;

import android.util.Log;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * 本地 PHP 服务器（仅监听 127.0.0.1 随机端口，不对外暴露）。
 * 收到请求时直接执行对应 PHP 文件，返回原始输出。
 *
 * 注意：请求由**单线程**工作队列串行处理。PHP embed 运行时是全局单例且非线程安全，
 * 并发执行会踩坏内存直接崩掉进程，所以这里不允许并发。
 *
 * 优化点：
 * 1. 3秒响应缓存：同一脚本+参数重复请求直接返回上次 m3u8，不重复执行 PHP
 * 2. 有界队列(8)：队列满直接拒绝(503)，避免请求堆积导致惊群
 * 3. 仅缓存成功的 m3u8 响应：空响应/错误不缓存，让 ExoPlayer 立即重试
 * 4. LRU 缓存上限(16条)：防止频繁切频道导致内存泄漏
 */
public class PhpServer {

    private static final String TAG = "PhpServer";
    private static final byte[] END = "\r\n\r\n".getBytes(StandardCharsets.US_ASCII);

    /** 读取请求的超时：只用于防止半个连接把唯一的 worker 卡死 */
    private static final int SO_TIMEOUT_MS = 10000;

    /** 响应缓存 TTL（毫秒）：同一脚本+参数在此时长内重复请求直接返回上次结果 */
    private static final long CACHE_TTL_MS = 3000;

    /** 缓存最大条目数：LRU 淘汰 */
    private static final int CACHE_MAX_ENTRIES = 16;

    /** worker 队列最大长度：超过直接拒绝(503)，避免请求堆积 */
    private static final int WORKER_QUEUE_CAPACITY = 8;

    private static class CacheEntry {
        final String body;
        final String contentType;
        final long time;
        CacheEntry(String body, String contentType) {
            this.body = body;
            this.contentType = contentType;
            this.time = System.currentTimeMillis();
        }
        boolean fresh() {
            return System.currentTimeMillis() - time < CACHE_TTL_MS;
        }
    }

    private static PhpServer instance;
    private ServerSocket socket;
    private Thread thread;
    private final ExecutorService worker = new ThreadPoolExecutor(
            1, 1, 0L, TimeUnit.MILLISECONDS,
            new LinkedBlockingQueue<>(WORKER_QUEUE_CAPACITY),
            r -> { Thread t = new Thread(r, "php-handler"); t.setDaemon(true); return t; },
            new ThreadPoolExecutor.AbortPolicy()
    );
    /** LRU 缓存：按访问顺序排列，超过上限淘汰最久未使用的 */
    private final LinkedHashMap<String, CacheEntry> responseCache =
            new LinkedHashMap<String, CacheEntry>(CACHE_MAX_ENTRIES, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, CacheEntry> eldest) {
                    return size() > CACHE_MAX_ENTRIES;
                }
            };
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
        // 新脚本写入时清除该脚本相关的缓存
        synchronized (responseCache) {
            responseCache.keySet().removeIf(key -> key.startsWith(scriptName + "?"));
        }
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
                try {
                    worker.execute(() -> handle(client));
                } catch (java.util.concurrent.RejectedExecutionException e) {
                    // 队列满，直接返回 503，让 ExoPlayer 稍后重试
                    Log.w(TAG, "worker 队列已满，拒绝请求");
                    try {
                        OutputStream out = client.getOutputStream();
                        sendResponse(out, 503, "text/plain", "Service Busy".getBytes(StandardCharsets.UTF_8));
                    } catch (Exception ignored) {
                    } finally {
                        try { client.close(); } catch (Exception ignored) {}
                    }
                }
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

            // 响应缓存检查
            String cacheKey = scriptName + "?" + queryString;
            CacheEntry cached;
            synchronized (responseCache) {
                cached = responseCache.get(cacheKey);
            }
            if (cached != null && cached.fresh()) {
                Log.i(TAG, "缓存命中(" + (System.currentTimeMillis() - cached.time) + "ms), 跳过PHP执行");
                sendResponse(output, 200, cached.contentType, cached.body.getBytes(StandardCharsets.UTF_8));
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

            String contentType = result.startsWith("#EXTM3U")
                    ? "application/vnd.apple.mpegurl"
                    : "text/html; charset=utf-8";

            // 仅缓存成功的 m3u8 响应；空响应/错误不缓存，让 ExoPlayer 立即重试
            if (result.startsWith("#EXTM3U")) {
                synchronized (responseCache) {
                    responseCache.put(cacheKey, new CacheEntry(result, contentType));
                }
            } else {
                Log.w(TAG, "非m3u8响应，不缓存: " + result.substring(0, Math.min(result.length(), 100)));
            }

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
            case 503: return "Service Unavailable";
            default: return "OK";
        }
    }
}
