package com.fongmi.android.tv.utils;

import com.github.catvod.net.OkHttp;
import org.json.JSONObject;
import java.util.HashMap;
import java.util.Map;

public class API2018K {

    private static final String BASE_URL = "https://api.2018k.cn/v3";
    private static final String ID = "68D9D8C218894DC68ECDF51C42330869";
    private static JSONObject cache;

    private static JSONObject getData() {
        if (cache != null) return cache;
        String url = BASE_URL + "/obtainSoftware?softwareId=" + ID + "&machineCode=123456";
        try {
            String result = OkHttp.string(url);
            cache = new JSONObject(result);
            return cache;
        } catch (Exception e) {
            e.printStackTrace();
            return new JSONObject();
        }
    }

    public static void clearCache() {
        cache = null;
    }

    public static boolean hasUpdate(String Ver) {
        String versionNumber = getData().optString("versionNumber");
        return compareVersion(versionNumber, Ver) > 0;
    }

    public static String getDownloadLink() {
        String link = getData().optString("downloadLink");
        if (!link.endsWith("/")) link += "/";
        return link;
    }

    public static String getApk(String name) {
        return getDownloadLink() + name + ".apk";
    }

    public static String getNotice() {
        return getData().optString("notice");
    }

    public static String getVersionInfo() {
        return getData().optString("versionInformation");
    }

    public static String getVersion() {
        return getData().optString("versionNumber");
    }

    public static JSONObject getJson() {
        return getData();
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