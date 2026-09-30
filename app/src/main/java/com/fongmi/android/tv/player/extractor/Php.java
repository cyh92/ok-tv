package com.fongmi.android.tv.player.extractor;

import android.net.Uri;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.exception.ExtractException;
import com.fongmi.android.tv.player.ku9.Ku9HttpClient;
import com.fongmi.android.tv.utils.UrlUtil;
import com.fongmi.php.PhpEnv;
import com.fongmi.php.PhpServer;

import java.io.IOException;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;

/**
 * php:// 远程PHP脚本源。
 * 地址约定：php://{远程PHP脚本HTTP(S)地址}?{参数}
 *
 * 工作流程只有三步，中间不做任何额外操作：
 * 1. 下载远程 PHP 脚本文本
 * 2. 写入本地 PHP 运行环境（内部存储 files/php/）
 * 3. 返回 http://127.0.0.1:{端口}/{脚本名}?{参数} 给播放器
 *
 * 之后播放器每次请求该本地地址，本地服务执行一次脚本并把输出返回给它。
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

        // 初始化 PHP 环境（失败时透出原因，避免静默切线路）
        PhpEnv.get(App.get()).init();
        if (!PhpEnv.isReady()) {
            throw new ExtractException("PHP 环境初始化失败: " + PhpEnv.getInitError());
        }

        // 1. 下载 PHP 脚本文本（缓存1小时，过期重新下载）
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

        // 2 + 3. 写入本地 PHP 目录，并把本地地址交给播放器
        try {
            return PhpServer.get().register(scriptName, scriptContent, queryString);
        } catch (IOException e) {
            throw new ExtractException("PHP 本地服务注册失败: " + e.getMessage());
        }
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
