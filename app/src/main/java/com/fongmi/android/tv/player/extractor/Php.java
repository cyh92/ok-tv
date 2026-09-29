package com.fongmi.android.tv.player.extractor;

import android.net.Uri;
import android.util.Log;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.exception.ExtractException;
import com.fongmi.android.tv.player.ku9.Ku9HttpClient;
import com.fongmi.android.tv.utils.UrlUtil;
import com.fongmi.php.PhpEnv;
import com.fongmi.php.PhpServer;

import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;

/**
 * php:// 远程PHP脚本源。
 * 地址约定：php://{远程PHP脚本HTTP(S)地址}?{参数}
 *
 * 工作流程：
 * 1. 下载远程 PHP 脚本文本（带缓存，同一脚本不重复下载）
 * 2. 注册到本地 PHP 服务器
 * 3. 返回 http://127.0.0.1:端口/xxx.php?参数
 * 4. ExoPlayer 请求本地地址，服务器执行 PHP 脚本并 302 到 m3u8
 */
public class Php implements Source.Extractor {

    /** 脚本缓存过期时间：1小时 */
    private static final long CACHE_TTL = 60 * 60 * 1000L;

    /** 缓存条目：内容 + 下载时间 */
    private static class CacheEntry {
        final String content;
        final long time;
        CacheEntry(String content) {
            this.content = content;
            this.time = System.currentTimeMillis();
        }
        boolean expired() {
            return System.currentTimeMillis() - time > CACHE_TTL;
        }
    }

    /** 脚本内容缓存：脚本URL → 缓存条目 */
    private static final ConcurrentHashMap<String, CacheEntry> scriptCache = new ConcurrentHashMap<>();

    @Override
    public String fetch(String url) throws Exception {
        if (url == null || !url.regionMatches(true, 0, "php://", 0, 6)) {
            throw new ExtractException("无效的PHP地址: " + url);
        }

        String fullUrl = url.substring(6);

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

        // 初始化 PHP 环境
        PhpEnv.get(App.get()).init();

        // 下载 PHP 脚本文本（缓存1小时，过期重新下载）
        CacheEntry entry = scriptCache.get(scriptUrl);
        String scriptContent;
        if (entry != null && !entry.expired()) {
            scriptContent = entry.content;
        } else {
            scriptContent = Ku9HttpClient.get(scriptUrl, null);
            if (scriptContent == null || scriptContent.isEmpty()) {
                throw new ExtractException("下载PHP脚本失败: " + scriptUrl);
            }
            scriptCache.put(scriptUrl, new CacheEntry(scriptContent));
        }

        // 用 URL 哈希作为脚本名，避免冲突
        String scriptName = "script_" + Integer.toHexString(scriptUrl.hashCode()) + ".php";

        // 注册到本地服务器并返回本地 URL
        String localUrl = PhpServer.get().register(scriptName, scriptContent, queryString);

        // 预热：先执行一次 PHP，确保 native 库和 cURL 已加载，避免 ExoPlayer 首次请求超时
        try {
            long t0 = System.currentTimeMillis();
            String warmed = PhpServer.get().warmUp(scriptName, queryString);
            Log.i("Php", "预热完成，耗时=" + (System.currentTimeMillis() - t0) + "ms, 内容长度=" + (warmed == null ? 0 : warmed.length()));
        } catch (Exception e) {
            Log.w("Php", "预热失败（不影响播放）: " + e.getMessage());
        }

        return localUrl;
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
