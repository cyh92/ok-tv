package com.github.catvod.utils;

import android.net.Uri;

import com.github.catvod.net.OkHttp;

import java.io.File;

public class Github {

    public static final String downloadURL = "https://gitee.com/cyh92/live/releases/download";
    public static final String releaseURL = "https://gitee.com/cyh92/live/raw/main";
    private static String getUrl(String name) {
        return downloadURL + "/oktv/" + name;
    }

    public static String getJson(boolean dev, String name) {
        return releaseURL + "/release/" + name + "5.json";
    }

    public static String getApk(boolean dev, String name) {
        return getUrl(name + ".apk");
    }

    public static String getSo(String url) {
        try {
            File file = new File(Path.so(), Uri.parse(url).getLastPathSegment());
            if (file.length() < 300) Path.write(file, OkHttp.newCall(url).execute().body().bytes());
            return file.getAbsolutePath();
        } catch (Exception e) {
            e.printStackTrace();
            return "";
        }
    }
}
