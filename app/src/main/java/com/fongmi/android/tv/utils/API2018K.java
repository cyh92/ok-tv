package com.fongmi.android.tv.utils;

import android.util.Base64;

import com.fongmi.android.tv.BuildConfig;
import com.fongmi.android.tv.api.Decoder;
import com.fongmi.android.tv.event.RefreshEvent;
import com.fongmi.android.tv.setting.Setting;
import com.github.catvod.net.OkHttp;

import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

public class API2018K {

    private static final String BASE_URL = "https://api.2018k.cn/v3";
    private static final String ID = "68D9D8C218894DC68ECDF51C42330869";
    private static final String KEY = BuildConfig.OPEN_ID;
    private static JSONObject cache;
    private static long cacheTime;
    private static boolean autoStarted;
    // 缓存有效期：6 小时内直接复用，过期后自动重新请求获取后台最新数据（可按需调整）
    private static final long CACHE_TTL = 6 * 60 * 60 * 1000L;
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();
    private static final android.os.Handler HANDLER = new android.os.Handler(android.os.Looper.getMainLooper());

    // 周期自动刷新任务：每 CACHE_TTL 重新拉取一次后台数据，保证后台参数修改后 app 内能更新
    private static final Runnable REFRESH_TASK = new Runnable() {
        @Override
        public void run() {
            try {
                init(null);
            } finally {
                HANDLER.postDelayed(this, CACHE_TTL);
            }
        }
    };

    // 异步加载数据（必须先调用）。cache 未过期时直接返回缓存，过期后自动重新拉取最新数据
    public static void init(OnDataListener listener) {
        if (cache != null && System.currentTimeMillis() - cacheTime < CACHE_TTL) {
            if (listener != null) listener.onResult(cache);
            return;
        }
        EXECUTOR.execute(() -> {
            JSONObject result = getData();
            // 请求失败返回空对象时不覆盖旧缓存，避免断网时丢失已有数据
            if (result != null && result.length() > 0) {
                cache = result;
                cacheTime = System.currentTimeMillis();
                startAutoRefresh();
                // 数据已更新，通知界面刷新公告等
                RefreshEvent.notice();
            }
            if (listener != null) {
                new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> listener.onResult(result));
            }
        });
    }

    // 启动周期自动刷新（仅启动一次），让后台参数修改后 app 内能自动更新
    private static void startAutoRefresh() {
        if (autoStarted) return;
        autoStarted = true;
        HANDLER.postDelayed(REFRESH_TASK, CACHE_TTL);
    }

    // 强制刷新：清空缓存后立即重新请求，马上获取后台最新数据
    public static void refresh(OnDataListener listener) {
        clearCache();
        init(listener);
    }

    public static JSONObject getData() {
        String url = BASE_URL + "/obtainSoftware?softwareId=" + ID + "&machineCode=123456";
        try {
            String result = OkHttp.string(url);
            JSONObject json = new JSONObject(result);
            String data = json.optString("data");
            JSONObject obj;
            if (!data.isEmpty()) {
                obj = new JSONObject(decryptOpenSslNative(data, KEY));
            } else {
                obj = json;
            }
            saveNotice(obj);
            return obj;
        } catch (Exception e) {
            e.printStackTrace();
            return new JSONObject();
        }
    }

    // 保存公告内容与强制更新开关（仅成功获取远程数据时调用，失败保留旧值）
    private static void saveNotice(JSONObject json) {
        try {
            Setting.putNotice(json.optString("notice"));
            Setting.putNoticeSwitch("y".equalsIgnoreCase(json.optString("mandatoryUpdate")));
        } catch (Throwable e) {
            e.printStackTrace();
        }
    }

    public static void clearCache() {
        cache = null;
    }

    // 下面所有方法，必须在 init() 回调之后使用
    public static boolean hasUpdate(String Ver) {
        String versionNumber = cache != null ? cache.optString("versionNumber") : "";
        return compareVersion(versionNumber, Ver) > 0;
    }

    public static String getDownloadLink() {
        String link = cache != null ? cache.optString("downloadLink") : "";
        if (!link.endsWith("/") && !link.isEmpty()) link += "/";
        return link;
    }

    public static String getApk(String name) {
        return getDownloadLink() + name + ".apk";
    }

    public static String getNotice() {
        return cache != null ? cache.optString("notice") : "";
    }

    public static String getVersionInfo() {
        return cache != null ? cache.optString("versionInformation") : "";
    }

    public static String getVersion() {
        return cache != null ? cache.optString("versionNumber") : "";
    }

    public static JSONObject getJson() {
        return cache != null ? cache : new JSONObject();
    }
    private static int compareVersion(String v1, String v2) {
        if (v1 == null || v2 == null) return 0;
        String[] arr1 = v1.split("\\.");
        String[] arr2 = v2.split("\\.");
        int length = Math.max(arr1.length, arr2.length);
        for (int i = 0; i < length; i++) {
            // 断网时 versionNumber 为空串，"" .split("\\.") 返回 [""]，parseInt("") 会抛 NumberFormatException，需保护
            int n1 = parsePart(i < arr1.length ? arr1[i] : null);
            int n2 = parsePart(i < arr2.length ? arr2[i] : null);
            if (n1 != n2) return n1 - n2;
        }
        return 0;
    }

    private static int parsePart(String part) {
        if (part == null || part.isEmpty()) return 0;
        try {
            return Integer.parseInt(part);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /**
     * 纯原生解密 OpenSSL AES 密文（你的密文专用，无第三方依赖）
     * 解决：Salted__ 开头、无MD5Digest、无BouncyCastle
     */
    public static String decryptOpenSslNative(String data, String passphrase) {
        if (data == null || data.isEmpty() || passphrase == null || passphrase.isEmpty()) {
            return data != null ? data : "";
        }
        try {
            byte[] encrypted = Base64.decode(data, Base64.DEFAULT);
            if (encrypted == null || encrypted.length < 16) {
                return data;
            }

            // 提取Salt头与盐值
            byte[] salt = new byte[8];
            System.arraycopy(encrypted, 8, salt, 0, 8);
            byte[] cipherBytes = new byte[encrypted.length - 16];
            System.arraycopy(encrypted, 16, cipherBytes, 0, cipherBytes.length);

            // 原生MD5生成 KEY + IV (32+16)
            byte[] key = new byte[32];
            byte[] iv = new byte[16];
            deriveKeyAndIv(passphrase.getBytes(StandardCharsets.UTF_8), salt, key, iv);

            // AES-256-CBC 解密
            SecretKeySpec keySpec = new SecretKeySpec(key, "AES");
            IvParameterSpec ivSpec = new IvParameterSpec(iv);
            Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
            cipher.init(Cipher.DECRYPT_MODE, keySpec, ivSpec);

            byte[] decrypted = cipher.doFinal(cipherBytes);
            return new String(decrypted, StandardCharsets.UTF_8);
        } catch (Exception e) {
            e.printStackTrace();
            return data;
        }
    }

    /**
     * 原生 MD5 生成 OpenSSL 密钥+IV（无第三方依赖）
     */
    private static void deriveKeyAndIv(byte[] pass, byte[] salt, byte[] key, byte[] iv) throws Exception {
        java.security.MessageDigest md5 = java.security.MessageDigest.getInstance("MD5");
        byte[] block = new byte[16];
        int offset = 0;

        while (offset < 48) {
            if (offset > 0) md5.update(block, 0, 16);
            md5.update(pass);
            md5.update(salt);
            md5.digest(block, 0, 16);

            int needed = 48 - offset;
            int copy = Math.min(16, needed);

            if (offset < 32) {
                System.arraycopy(block, 0, key, offset, copy);
            } else {
                System.arraycopy(block, 0, iv, offset - 32, copy);
            }
            offset += copy;
        }
    }
    public interface OnDataListener {
        void onResult(JSONObject json);
    }

    public static class Result {
        public String user;
        public String mandatoryUpdate;
        public String softwareMd5;
        public String softwareName;
        public String notice;
        public String versionInformation;
        public String softwareId;
        public String downloadLink;
        public String versionNumber;
        public int numberOfVisits;
        public String miniVersion;
        public Object networkVerificationId;
        public String isItEffective;
        public String numberOfDays;
        public String remarks;
        public Object expirationDate;
        public long timeStamp;

        public static Result from(JSONObject json) {
            Result result = new Result();
            result.user = json.optString("user");
            result.mandatoryUpdate = json.optString("mandatoryUpdate");
            result.softwareMd5 = json.optString("softwareMd5");
            result.softwareName = json.optString("softwareName");
            result.notice = json.optString("notice");
            result.versionInformation = json.optString("versionInformation");
            result.softwareId = json.optString("softwareId");
            result.downloadLink = json.optString("downloadLink");
            result.versionNumber = json.optString("versionNumber");
            result.numberOfVisits = json.optInt("numberOfVisits");
            result.miniVersion = json.optString("miniVersion");
            result.networkVerificationId = json.opt("networkVerificationId");
            result.isItEffective = json.optString("isItEffective");
            result.numberOfDays = json.optString("numberOfDays");
            result.remarks = json.optString("remarks");
            result.expirationDate = json.opt("expirationDate");
            result.timeStamp = json.optLong("timeStamp");
            return result;
        }
    }
}