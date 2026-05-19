package com.fongmi.android.tv.utils;

import android.util.Base64;

import com.fongmi.android.tv.BuildConfig;
import com.fongmi.android.tv.api.Decoder;
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
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();

    // 异步加载数据（必须先调用）
    public static void init(OnDataListener listener) {
        if (cache != null) {
            if (listener != null) listener.onResult(cache);
            return;
        }
        EXECUTOR.execute(() -> {
            JSONObject result = getData();
            cache = result;
            if (listener != null) {
                new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> listener.onResult(result));
            }
        });
    }

    public static JSONObject getData() {
        String url = BASE_URL + "/obtainSoftware?softwareId=" + ID + "&machineCode=123456";
        try {
            String result = OkHttp.string(url);
            JSONObject json = new JSONObject(result);
            String data = json.optString("data");
            if (!data.isEmpty()) {
                return new JSONObject(decryptOpenSslNative(data, KEY));
            } else {
                return json;
            }
        } catch (Exception e) {
            e.printStackTrace();
            return new JSONObject();
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
            int n1 = i < arr1.length ? Integer.parseInt(arr1[i]) : 0;
            int n2 = i < arr2.length ? Integer.parseInt(arr2[i]) : 0;
            if (n1 != n2) return n1 - n2;
        }
        return 0;
    }

    /**
     * 纯原生解密 OpenSSL AES 密文（你的密文专用，无第三方依赖）
     * 解决：Salted__ 开头、无MD5Digest、无BouncyCastle
     */
    public static String decryptOpenSslNative(String data, String passphrase) throws Exception {
        byte[] encrypted = Base64.decode(data, Base64.DEFAULT);

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