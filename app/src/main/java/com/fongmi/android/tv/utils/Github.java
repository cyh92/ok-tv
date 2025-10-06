package com.fongmi.android.tv.utils;

public class Github {

    public static final String downloadURL = "https://gitee.com/cyh92/live/releases/download";
    public static final String releaseURL = "https://gitee.com/cyh92/live/raw/main";

    private static String getUrl(String URL,String path, String name) {
        return URL + "/" + path + "/" + name;
    }

    public static String getJson(boolean dev, String name) {
        return getUrl(releaseURL,(dev ? "dev" : "release"), name + ".json");
    }

    public static String getApk(boolean dev, String name) {
        return getUrl(downloadURL,(dev ? "dev" : "release"), name + ".apk");
    }
}
