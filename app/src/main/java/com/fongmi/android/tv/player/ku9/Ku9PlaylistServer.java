package com.fongmi.android.tv.player.ku9;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/**
 * 动态 m3u8 的本地轮询服务器（仅监听 127.0.0.1 随机端口）。
 * 用于酷9脚本"每次执行都会生成全新直播列表"的场景：
 * 播放器拿到稳定的 http://127.0.0.1:{port}/live.m3u8，内容由脚本周期性重跑后 update() 更新。
 */
public class Ku9PlaylistServer {

    private static final byte[] END = "\r\n\r\n".getBytes(StandardCharsets.US_ASCII);

    private ServerSocket socket;
    private Thread thread;
    private volatile byte[] content = new byte[0];
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
        this.content = content == null ? new byte[0] : content.getBytes(StandardCharsets.UTF_8);
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
}
