package com.fongmi.android.tv.player.extractor;

import android.net.Uri;

import com.fongmi.android.tv.exception.ExtractException;
import com.fongmi.android.tv.player.ku9.Ku9Resolver;
import com.fongmi.android.tv.utils.UrlUtil;

/**
 * ku9:// 脚本源解析器。
 * 地址约定：ku9://{脚本 HTTP(S) 地址}，例如 ku9://https://cdn.xxx.com/a/ku9/js/x.js?ch=1
 * 薄壳：真正执行在 com.fongmi.android.tv.player.ku9 包（系统 WebView + JS 桥）。
 */
public class Ku9 implements Source.Extractor {

    @Override
    public String fetch(String url) throws Exception {
        if (url == null || !url.regionMatches(true, 0, "ku9://", 0, 6)) throw new ExtractException("无效的JS地址: " + url);
        return Ku9Resolver.get().fetch(url.substring(6));
    }

    @Override
    public boolean match(Uri uri) {
        return "ku9".equals(UrlUtil.scheme(uri));
    }

    @Override
    public void stop() {
        Ku9Resolver.get().stop();
    }

    @Override
    public void exit() {
        Ku9Resolver.get().destroy();
    }
}
