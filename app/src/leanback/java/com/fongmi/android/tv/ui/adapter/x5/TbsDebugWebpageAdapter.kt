package com.fongmi.android.tv.ui.adapter.x5;

import com.fongmi.android.tv.ui.custom.WebpageAdapterWebView


class TbsDebugWebpageAdapter : WebpageAdapter() {
    override fun isAdaptedUrl(url: String): Boolean {
        return url.contains("debugtbs.qq.com") || url.contains("res.imtt.qq.com")
    }

    override fun javascript(): String {
        return ""
    }

    override suspend fun enterFullscreen(webView: WebpageAdapterWebView) {
    }
}