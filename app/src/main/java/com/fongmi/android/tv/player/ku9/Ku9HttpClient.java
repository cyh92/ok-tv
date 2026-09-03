package com.fongmi.android.tv.player.ku9;

import com.github.catvod.net.OkHttp;

import org.json.JSONObject;
import org.json.JSONTokener;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

import okhttp3.Headers;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * 供 ku9:// 脚本同步使用的极简 HTTP 适配层。
 * 复用项目 catvod 的 OkHttp 客户端（含 UA/代理/DNS 等全局拦截器）。
 * 语义与 WebViewTvLive 的 Ku9HttpClient 对齐：
 * - get：GET，非 2xx 抛异常，返回响应正文文本（8MB 上限）；
 * - post：POST，非 2xx 抛异常，若响应 JSON 含字符串 body 字段则取该字段，否则返回全文；
 * - request：通用方法，返回 {code, body, url, headers, error} 结构，任何异常都包装进 error，不向外抛。
 */
public class Ku9HttpClient {

    private static final long TIMEOUT = 15000L;
    private static final int MAX_BODY = 8 * 1024 * 1024;

    public static String get(String url, String headersJson) throws IOException {
        JSONObject json = request(url, "GET", headersJson, null, true);
        int code = json.optInt("code");
        if (code < 200 || code >= 300) throw new IOException("HTTP " + code);
        return json.optString("body");
    }

    public static String post(String url, String body, String headersJson) throws IOException {
        JSONObject json = request(url, "POST", headersJson, body, true);
        int code = json.optInt("code");
        if (code < 200 || code >= 300) throw new IOException("HTTP " + code);
        String text = json.optString("body");
        try {
            Object value = new JSONTokener(text).nextValue();
            if (value instanceof JSONObject) {
                Object inner = ((JSONObject) value).opt("body");
                if (inner instanceof String) return (String) inner;
            }
        } catch (Exception ignored) {
        }
        return text;
    }

    public static JSONObject request(String url, String method, String headersJson, String body, boolean followRedirects) {
        JSONObject result = new JSONObject();
        try {
            OkHttpClient client = OkHttp.client(followRedirects, TIMEOUT);
            Request.Builder builder = new Request.Builder().url(url);
            Map<String, String> headers = parseHeaders(headersJson);
            for (Map.Entry<String, String> entry : headers.entrySet()) builder.header(entry.getKey(), entry.getValue());
            String m = method == null ? "GET" : method.trim().toUpperCase(Locale.ROOT);
            if ("GET".equals(m) || "HEAD".equals(m)) {
                builder.method(m, null);
            } else {
                builder.method(m, RequestBody.create(body == null ? "" : body, mediaType(headers)));
            }
            try (Response response = client.newCall(builder.build()).execute()) {
                result.put("code", response.code());
                result.put("url", response.request().url().toString());
                result.put("body", readBody(response));
                JSONObject hs = new JSONObject();
                Headers responseHeaders = response.headers();
                for (int i = 0; i < responseHeaders.size(); i++) {
                    String name = responseHeaders.name(i);
                    if (!hs.has(name)) hs.put(name, responseHeaders.value(i));
                }
                result.put("headers", hs);
            }
        } catch (Throwable e) {
            try {
                JSONObject error = new JSONObject();
                error.put("code", 0);
                error.put("body", "");
                error.put("url", url == null ? "" : url);
                error.put("error", e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
                return error;
            } catch (Exception ignored) {
            }
        }
        return result;
    }

    /** 发起请求并返回 {code, url, headers}，不读取响应体（仅用于取响应头）。 */
    public static JSONObject getHeaders(String url, String headersJson, boolean followRedirects, String method, String body) {
        JSONObject result = new JSONObject();
        try {
            OkHttpClient client = OkHttp.client(followRedirects, TIMEOUT);
            Request.Builder builder = new Request.Builder().url(url);
            Map<String, String> headers = parseHeaders(headersJson);
            for (Map.Entry<String, String> entry : headers.entrySet()) builder.header(entry.getKey(), entry.getValue());
            String m = method == null ? "GET" : method.trim().toUpperCase(Locale.ROOT);
            if ("GET".equals(m) || "HEAD".equals(m)) {
                builder.method(m, null);
            } else {
                builder.method(m, RequestBody.create(body == null ? "" : body, mediaType(headers)));
            }
            try (Response response = client.newCall(builder.build()).execute()) {
                result.put("code", response.code());
                result.put("url", response.request().url().toString());
                JSONObject hs = new JSONObject();
                Headers responseHeaders = response.headers();
                for (int i = 0; i < responseHeaders.size(); i++) {
                    String name = responseHeaders.name(i);
                    if (!hs.has(name)) hs.put(name, responseHeaders.value(i));
                }
                result.put("headers", hs);
            }
        } catch (Throwable e) {
            try {
                result.put("code", 0);
                result.put("url", url == null ? "" : url);
                result.put("error", e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
            } catch (Exception ignored) {
            }
        }
        return result;
    }

    public static Map<String, String> parseHeaders(String json) {
        Map<String, String> map = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        if (json == null || json.isEmpty()) return map;
        try {
            Object value = new JSONTokener(json).nextValue();
            if (!(value instanceof JSONObject)) return map;
            JSONObject object = (JSONObject) value;
            for (Iterator<String> it = object.keys(); it.hasNext(); ) {
                String key = it.next();
                Object v = object.opt(key);
                if (v == null) continue;
                map.put(key, v instanceof String ? (String) v : v.toString());
            }
        } catch (Exception ignored) {
        }
        return map;
    }

    public static String md5(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("MD5");
            byte[] data = digest.digest((text == null ? "" : text).getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : data) sb.append(String.format(Locale.ROOT, "%02x", b & 0xff));
            return sb.toString();
        } catch (Exception e) {
            return String.valueOf((text == null ? "" : text).hashCode());
        }
    }

    private static MediaType mediaType(Map<String, String> headers) {
        String type = null;
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            if ("content-type".equalsIgnoreCase(entry.getKey())) {
                type = entry.getValue();
                break;
            }
        }
        if (type == null) type = "application/x-www-form-urlencoded; charset=utf-8";
        try {
            MediaType mediaType = MediaType.parse(type);
            if (mediaType != null) return mediaType;
        } catch (Exception ignored) {
        }
        return MediaType.parse("application/octet-stream");
    }

    private static String readBody(Response response) throws IOException {
        InputStream input = response.body().byteStream();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        long total = 0;
        int len;
        while ((len = input.read(buffer)) > 0) {
            total += len;
            if (total > MAX_BODY) throw new IOException("响应超过 8MB 上限");
            output.write(buffer, 0, len);
        }
        return output.toString("UTF-8");
    }
}
