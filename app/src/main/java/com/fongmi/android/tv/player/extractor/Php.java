package com.fongmi.android.tv.player.extractor;

import android.net.Uri;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.exception.ExtractException;
import com.fongmi.android.tv.player.ku9.Ku9HttpClient;
import com.fongmi.android.tv.utils.UrlUtil;
import com.fongmi.php.PhpEnv;

import org.json.JSONObject;
import org.json.JSONTokener;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * php:// 远程PHP脚本源解析器。
 * 地址约定：php://{远程PHP脚本HTTP(S)地址}?{参数}
 *
 * 工作流程：
 * 1. 下载远程 PHP 脚本文本（不执行）
 * 2. 将 header("Location: ...") 重定向转换为 echo 输出（embed模式下header不被捕获）
 * 3. 在本地通过 PHP embed JNI 执行脚本
 * 4. 解析返回内容，提取播放地址
 */
public class Php implements Source.Extractor {

    private static final String RESULT_PREFIX = "PHP_PLAY_URL:";

    @Override
    public String fetch(String url) throws Exception {
        if (url == null || !url.regionMatches(true, 0, "php://", 0, 5)) {
            throw new ExtractException("无效的PHP地址: " + url);
        }

        // 去掉 php:// 前缀
        String fullUrl = url.substring(5);

        // 分离脚本地址和查询参数
        String scriptUrl;
        String queryString;
        int qIndex = fullUrl.indexOf('?');
        if (qIndex >= 0) {
            scriptUrl = fullUrl.substring(0, qIndex);
            queryString = fullUrl.substring(qIndex + 1);
        } else {
            scriptUrl = fullUrl;
            queryString = "";
        }

        if (!scriptUrl.startsWith("http://") && !scriptUrl.startsWith("https://")) {
            throw new ExtractException("PHP脚本地址必须以 http(s) 开头: " + scriptUrl);
        }

        // 下载 PHP 脚本文本
        String scriptContent = Ku9HttpClient.get(scriptUrl, null);
        if (scriptContent == null || scriptContent.isEmpty()) {
            throw new ExtractException("下载PHP脚本失败: " + scriptUrl);
        }

        // 将 header("Location: ...", true, 302) 转换为 echo 输出
        String processedScript = convertRedirectToEcho(scriptContent);

        // 在本地执行 PHP 脚本
        String output = PhpEnv.get(App.get()).execute(processedScript, queryString);

        if (output == null || output.trim().isEmpty()) {
            throw new ExtractException("PHP脚本执行无输出");
        }

        return parseOutput(output.trim());
    }

    /**
     * 将 header("Location: ...", true, 30X) 转换为 echo 输出。
     * embed 模式下 header() 不会被捕获，需要转成 echo。
     *
     * 匹配模式：
     *   header("Location: {$m3u8}", true, 302);
     *   header("Location: " . $m3u8, true, 302);
     *   header("Location: " . $url, true, 301);
     */
    private String convertRedirectToEcho(String script) {
        // 匹配 header("Location: " . $variable, true, 30X);
        Pattern pattern = Pattern.compile(
                "header\\s*\\(\\s*[\"']Location:\\s*[\"']\\s*\\.\\s*\\$(\\w+)\\s*,\\s*true\\s*,\\s*30[1237]\\s*\\)\\s*;",
                Pattern.DOTALL
        );
        Matcher matcher = pattern.matcher(script);
        StringBuffer sb = new StringBuffer();
        while (matcher.find()) {
            matcher.appendReplacement(sb,
                    "echo \"" + RESULT_PREFIX + "\" . $" + matcher.group(1) + ";");
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    /**
     * 解析 PHP 输出内容。
     * 支持：
     * 1. PHP_PLAY_URL:http://xxx.m3u8（转换后的重定向）
     * 2. 纯 URL 文本
     * 3. JSON {"url":"http://xxx.m3u8"}
     * 4. #EXTM3U 内容（暂不支持）
     */
    private String parseOutput(String output) throws ExtractException {
        // 1. 转换后的重定向输出
        for (String line : output.split("\n")) {
            line = line.trim();
            if (line.startsWith(RESULT_PREFIX)) {
                String url = line.substring(RESULT_PREFIX.length()).trim();
                if (url.startsWith("http://") || url.startsWith("https://")) return url;
            }
        }

        // 2. 纯 URL 文本
        if (output.startsWith("http://") || output.startsWith("https://")) {
            String firstLine = output.split("\n")[0].trim();
            if (firstLine.startsWith("http://") || firstLine.startsWith("https://")) return firstLine;
        }

        // 3. JSON
        try {
            Object value = new JSONTokener(output).nextValue();
            if (value instanceof JSONObject) {
                JSONObject json = (JSONObject) value;
                String url = json.optString("url", json.optString("playUrl", json.optString("m3u8", "")));
                if (!url.isEmpty()) return url;
            }
        } catch (Exception ignored) {
        }

        // 4. #EXTM3U
        if (output.startsWith("#EXTM3U")) {
            throw new ExtractException("PHP脚本返回m3u8动态列表，暂不支持");
        }

        throw new ExtractException("PHP返回无法识别的内容: " +
                (output.length() > 100 ? output.substring(0, 100) + "..." : output));
    }

    @Override
    public boolean match(Uri uri) {
        return "php".equals(UrlUtil.scheme(uri).toLowerCase(Locale.ROOT));
    }

    @Override
    public void stop() {
    }

    @Override
    public void exit() {
    }
}
