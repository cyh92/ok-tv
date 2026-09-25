package com.fongmi.android.tv.player.ku9;

import com.fongmi.android.tv.App;
import com.github.catvod.net.OkHttp;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import okhttp3.Response;

/**
 * ku9:// 脚本本体的下载与缓存。
 * 缓存目录：cacheDir/ku9_scripts/{md5(地址)}.js，30 分钟内命中直接读取；
 * 网络失败但存在陈旧缓存时回退用缓存，保证断网可播。
 * 脚本大小上限 2MB。
 */
public class Ku9ScriptLoader {

    private static final long MAX_SCRIPT_BYTES = 2 * 1024 * 1024L;
    private static final long CACHE_TTL = 30 * 60 * 1000L;

    private final File dir;

    private static class Loader {
        static volatile Ku9ScriptLoader INSTANCE = new Ku9ScriptLoader();
    }

    public static Ku9ScriptLoader get() {
        return Loader.INSTANCE;
    }

    private Ku9ScriptLoader() {
        dir = new File(App.get().getCacheDir(), "ku9_scripts");
    }

    /** 返回脚本内容；下载失败且无可用缓存时抛异常。 */
    public String load(String url) throws IOException {
        if (url == null || !(url.startsWith("http://") || url.startsWith("https://"))) throw new IOException("非法的脚本地址: " + url);
        File file = new File(dir, Ku9HttpClient.md5(url) + ".js");
        if (file.isFile() && System.currentTimeMillis() - file.lastModified() < CACHE_TTL) {
            String cached = readFile(file);
            if (cached != null) return cached;
        }
        String remote = download(url);
        if (remote == null) {
            String cached = readFile(file);
            if (cached != null) return cached;
            throw new IOException("下载JS脚本失败: " + url);
        }
        writeFile(file, remote);
        return remote;
    }

    private String download(String url) {
        try (Response response = OkHttp.newCall(OkHttp.client(15000), url).execute()) {
            if (response.code() / 100 != 2) return null;
            String text = readLimited(response, MAX_SCRIPT_BYTES);
            return text == null ? null : text.trim();
        } catch (Exception e) {
            return null;
        }
    }

    private String readFile(File file) {
        if (!file.isFile() || file.length() > MAX_SCRIPT_BYTES) return null;
        try (InputStream input = new FileInputStream(file)) {
            return readLimited(input, MAX_SCRIPT_BYTES);
        } catch (Exception e) {
            return null;
        }
    }

    private void writeFile(File file, String content) throws IOException {
        if (!dir.exists() && !dir.mkdirs()) throw new IOException("创建脚本缓存目录失败");
        File tmp = new File(dir, file.getName() + ".tmp");
        try (FileOutputStream output = new FileOutputStream(tmp)) {
            output.write(content.getBytes(StandardCharsets.UTF_8));
        }
        if (!tmp.renameTo(file) && file.exists()) {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
        }
    }

    private String readLimited(Response response, long maxBytes) throws IOException {
        InputStream input = response.body().byteStream();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        long total = 0;
        int len;
        while ((len = input.read(buffer)) > 0) {
            total += len;
            if (total > maxBytes) throw new IOException("响应超过大小上限");
            output.write(buffer, 0, len);
        }
        return output.toString("UTF-8");
    }

    private String readLimited(InputStream input, long maxBytes) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        long total = 0;
        int len;
        while ((len = input.read(buffer)) > 0) {
            total += len;
            if (total > maxBytes) throw new IOException("响应超过大小上限");
            output.write(buffer, 0, len);
        }
        return output.toString("UTF-8");
    }
}
