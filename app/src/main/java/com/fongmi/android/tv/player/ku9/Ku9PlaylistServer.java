package com.fongmi.android.tv.player.ku9;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * 动态 m3u8 的本地轮询服务器（仅监听 127.0.0.1 随机端口）。
 * 用于酷9脚本"每次执行都会生成全新直播列表"的场景：
 * 播放器拿到稳定的 http://127.0.0.1:{port}/live.m3u8，内容由脚本周期性重跑后 update() 更新。
 *
 * 过期检测：正常直播流每次刷新 m3u8 都会追加新分片、移除旧分片，内容必然变化。
 * 如果连续 MAX_STALE_UPDATES 次 update 内容完全相同（或为空），说明脚本返回的分片 URL
 * 已停滞/过期，此时返回 503 让播放器报错，触发上层自动切线路重连。
 */
public class Ku9PlaylistServer {

    private static final byte[] END = "\r\n\r\n".getBytes(StandardCharsets.US_ASCII);
    private static final int MAX_STALE_UPDATES = 6;

    private ServerSocket socket;
    private Thread thread;
    private volatile byte[] content = new byte[0];
    private volatile byte[] lastHash = new byte[0];
    private volatile int staleCount;
    private volatile boolean running;

    public synchronized void start() throws IOException {
        if (running) return;
        running = true;
        socket = new ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"));
        thread = new Thread(this::acceptLoop, "ku9-playlist");
        thread.setDaemon(true);
        thread.start();
    }

    public synchronized void update(String content) {
        byte[] bytes = content == null ? new byte[0] : content.getBytes(StandardCharsets.UTF_8);
        this.content = bytes;

        byte[] hash = md5(bytes);
        if (MessageDigest.isEqual(hash, lastHash)) {
            staleCount++;
        } else {
            lastHash = hash;
            staleCount = 0;
        }
    }

    public String url() {
        return "http://127.0.0.1:" + socket.getLocalPort() + "/live.m3u8";
    }

    public synchronized void close() {
        running = false;
        try {
            if (socket != null) socket.close();
        } catch (IOException ignored) {
        }
        if (thread != null) thread.interrupt();
        socket = null;
        thread = null;
    }

    private void acceptLoop() {
        while (running) {
            try (Socket client = socket.accept()) {
                handle(client);
            } catch (IOException ignored) {
                if (!running) break;
            }
        }
    }

    private void handle(Socket client) {
        try {
            client.setSoTimeout(3000);
            InputStream input = client.getInputStream();
            readHeaders(input);
            OutputStream output = client.getOutputStream();

            // 分片列表连续多次未变化，说明脚本返回的 URL 已过期，返回 503 触发重连
            if (staleCount >= MAX_STALE_UPDATES) {
                String err = "HTTP/1.1 503 Service Unavailable\r\n"
                        + "Content-Type: application/vnd.apple.mpegurl\r\n"
                        + "Cache-Control: no-store\r\n"
                        + "Connection: close\r\n"
                        + "Content-Length: 0\r\n\r\n";
                output.write(err.getBytes(StandardCharsets.US_ASCII));
                output.flush();
                return;
            }

            byte[] body = content;
            String head = "HTTP/1.1 200 OK\r\n"
                    + "Content-Type: application/vnd.apple.mpegurl\r\n"
                    + "Cache-Control: no-store\r\n"
                    + "Connection: close\r\n"
                    + "Content-Length: " + body.length + "\r\n\r\n";
            output.write(head.getBytes(StandardCharsets.US_ASCII));
            output.write(body);
            output.flush();
        } catch (Exception ignored) {
        }
    }

    private void readHeaders(InputStream input) throws IOException {
        int total = 0;
        int matched = 0;
        while (total < 16384) {
            int b = input.read();
            if (b < 0) break;
            total++;
            if (b == END[matched]) {
                matched++;
                if (matched == END.length) break;
            } else {
                matched = b == END[0] ? 1 : 0;
            }
        }
    }

    private static byte[] md5(byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            return md.digest(data);
        } catch (Exception e) {
            return new byte[0];
        }
    }
}
