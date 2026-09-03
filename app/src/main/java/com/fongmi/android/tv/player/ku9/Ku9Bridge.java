package com.fongmi.android.tv.player.ku9;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;
import android.webkit.JavascriptInterface;

import com.fongmi.android.tv.App;

/**
 * 注入离屏 WebView 的 JS 桥（addJavascriptInterface 对象名 "ku9Bridge"）。
 * 只暴露白名单方法；get/post 失败返回空串不向 JS 抛异常，
 * request 方法始终返回 {code, body, url, headers, error} 结构，由脚本自行判断。
 * 同步网络请求运行在 WebView 的 JavaBridge 线程，不阻塞主线程。
 */
public class Ku9Bridge {

    static final String NAME = "ku9Bridge";
    private static final String PREF = "ku9_cache";

    private final Ku9Resolver resolver;
    private final SharedPreferences sp;

    Ku9Bridge(Ku9Resolver resolver) {
        this.resolver = resolver;
        this.sp = App.get().getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }

    @JavascriptInterface
    public void complete(String json) {
        resolver.onComplete(json);
    }

    @JavascriptInterface
    public void fail(String error) {
        resolver.onFail(error);
    }

    @JavascriptInterface
    public String get(String url, String headers) {
        try {
            return Ku9HttpClient.get(url, headers);
        } catch (Throwable e) {
            return "";
        }
    }

    @JavascriptInterface
    public String post(String url, String body, String headers) {
        try {
            return Ku9HttpClient.post(url, body, headers);
        } catch (Throwable e) {
            return "";
        }
    }

    @JavascriptInterface
    public String request(String url, String method, String headers, String body, boolean followRedirects) {
        return Ku9HttpClient.request(url, method, headers, body, followRedirects).toString();
    }

    @JavascriptInterface
    public String getCache(String key) {
        String cacheKey = cacheKey(key);
        long expires = sp.getLong(cacheKey + "__expires", 0);
        if (expires != 0 && expires < System.currentTimeMillis()) {
            sp.edit().remove(cacheKey).remove(cacheKey + "__expires").apply();
            return "";
        }
        return sp.getString(cacheKey, "");
    }

    @JavascriptInterface
    public void setCache(String key, String value, long ttl) {
        String cacheKey = cacheKey(key);
        SharedPreferences.Editor editor = sp.edit().putString(cacheKey, value == null ? "" : value);
        if (ttl > 0) editor.putLong(cacheKey + "__expires", System.currentTimeMillis() + ttl);
        else editor.remove(cacheKey + "__expires");
        editor.apply();
    }

    @JavascriptInterface
    public String md5(String text) {
        return Ku9HttpClient.md5(text);
    }

    @JavascriptInterface
    public void log(String msg) {
        Log.d("Ku9", msg == null ? "null" : msg);
    }

    // ---------- 新增：响应头 ----------

    @JavascriptInterface
    public String getHeaders(String url, String headers, boolean followRedirects, String method, String body) {
        return Ku9HttpClient.getHeaders(url, headers, followRedirects, method, body).toString();
    }

    // ---------- 新增：哈希 ----------

    @JavascriptInterface
    public String sha1(String value) {
        return Ku9Util.sha1(value);
    }

    @JavascriptInterface
    public String sha256(String value) {
        return Ku9Util.sha256(value);
    }

    @JavascriptInterface
    public String sha512(String value) {
        return Ku9Util.sha512(value);
    }

    // ---------- 新增：OpenSSL ----------

    @JavascriptInterface
    public String opensslEncrypt(String data, String type, String key, double outputType, String iv) {
        return Ku9Util.opensslEncrypt(data, type, key, outputType, iv);
    }

    @JavascriptInterface
    public String opensslDecrypt(String data, String type, String key, double inputType, String iv) {
        return Ku9Util.opensslDecrypt(data, type, key, inputType, iv);
    }

    // ---------- 新增：RC4 ----------

    @JavascriptInterface
    public String rc4Encrypt(String data, String key, double inputFormat, double outputFormat, String charset) {
        return Ku9Util.rc4Encrypt(data, key, inputFormat, outputFormat, charset);
    }

    @JavascriptInterface
    public String rc4Decrypt(String data, String key, double inputFormat, double outputFormat, String charset) {
        return Ku9Util.rc4Decrypt(data, key, inputFormat, outputFormat, charset);
    }

    // ---------- 新增：Base64 ----------

    @JavascriptInterface
    public String encodeBase64(String value) {
        return Ku9Util.encodeBase64(value);
    }

    @JavascriptInterface
    public String decodeBase64(String value) {
        return Ku9Util.decodeBase64(value);
    }

    // ---------- 新增：类型判断 ----------

    @JavascriptInterface
    public boolean isBase64(String value) {
        return Ku9Util.isBase64(value);
    }

    @JavascriptInterface
    public boolean isJsonObject(String value) {
        return Ku9Util.isJsonObject(value);
    }

    @JavascriptInterface
    public boolean isJsonArray(String value) {
        return Ku9Util.isJsonArray(value);
    }

    // ---------- 新增：时间 ----------

    @JavascriptInterface
    public String toTimestamp(String dateStr, String inputFormat, String timezone) {
        return Ku9Util.toTimestamp(dateStr, inputFormat, timezone);
    }

    @JavascriptInterface
    public String toDate(double timestamp, String outputFormat, String timezone) {
        return Ku9Util.toDate(timestamp, outputFormat, timezone);
    }

    @JavascriptInterface
    public String formatDateTime(String dateStr, String inputFormat, String outputFormat, double daysOffset, String inputTimezone, String outputTimezone) {
        return Ku9Util.formatDateTime(dateStr, inputFormat, outputFormat, daysOffset, inputTimezone, outputTimezone);
    }

    private String cacheKey(String key) {
        return "k_" + Ku9HttpClient.md5(key);
    }
}
