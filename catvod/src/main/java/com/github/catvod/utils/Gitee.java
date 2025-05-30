package com.github.catvod.utils;

import com.github.catvod.net.OkHttp;

import org.json.JSONArray;
import org.json.JSONObject;

public class Gitee {
    public static final String GITEE_REPO_OWNER = "cyh92";
    public static final String GITEE_REPO_NAME = "ok-tv";
    private static final String GITEE_API_URL = "https://gitee.com/api/v5/repos/%s/%s/releases/latest";
    private static JSONObject getReleaseInfo() throws Exception {
        String url = String.format(GITEE_API_URL, GITEE_REPO_OWNER, GITEE_REPO_NAME);
        return new JSONObject(OkHttp.string(url));
    }
    public static String getJson() {
        try {
            JSONObject response = getReleaseInfo();
            // 解析API响应数据
            String versionCode = response.optString("tag_name");
            String versionName = response.optString("name");
            String description = response.optString("body");
            JSONArray assets = response.optJSONArray("assets");
            // 封装为指定格式
            JSONObject result = new JSONObject();
            result.put("code", versionCode);
            result.put("name", versionName);
            result.put("desc", description);
            result.put("assets", assets);

            return result.toString();
        } catch (Exception e) {
            // 返回标准格式的错误信息
//            JSONObject error = new JSONObject();
//            error.put("code", "error");
//            error.put("name", "error");
//            error.put("desc", e.getMessage());
            return e.getMessage();
        }
    }
    public static String getApk(JSONArray assets, String name) {
        try {
            String targetName = name + ".apk";
            String resultUrl="";
            if (assets != null) {
                for (int i = 0; i < assets.length(); i++) {
                    JSONObject asset = assets.optJSONObject(i);
                    if (asset != null && targetName.equals(asset.optString("name"))) {
                        resultUrl=asset.optString("browser_download_url");
                        return resultUrl;
                    }
                }
            }
            return resultUrl;
        } catch (Exception e) {
            return e.getMessage();
        }
    }

}
