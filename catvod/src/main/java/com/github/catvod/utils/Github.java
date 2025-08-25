package com.github.catvod.utils;

public class Github {

    public static final String URL = "https://gitee.com/cyh92/ok-tv/releases/download";

    private static String getUrl(String path, String name) {
        return URL + "/" + path + "/" + name;
    }

    public static String getJson(boolean dev, String name) {
        return getUrl((dev ? "dev" : "release"), name + ".json");
    }

    public static String getApk(boolean dev, String name) {
        return getUrl((dev ? "dev" : "release"), name + ".apk");
    }
}
