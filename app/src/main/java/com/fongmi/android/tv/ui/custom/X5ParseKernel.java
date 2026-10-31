package com.fongmi.android.tv.ui.custom;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Bitmap;
import android.net.Uri;
import android.text.TextUtils;
import android.view.View;

import com.fongmi.android.tv.setting.Setting;
import com.github.catvod.crawler.SpiderDebug;
import com.tencent.smtt.export.external.interfaces.SslError;
import com.tencent.smtt.export.external.interfaces.SslErrorHandler;
import com.tencent.smtt.export.external.interfaces.WebResourceError;
import com.tencent.smtt.export.external.interfaces.WebResourceRequest;
import com.tencent.smtt.export.external.interfaces.WebResourceResponse;
import com.tencent.smtt.sdk.CookieManager;
import com.tencent.smtt.sdk.ValueCallback;
import com.tencent.smtt.sdk.WebSettings;
import com.tencent.smtt.sdk.WebView;
import com.tencent.smtt.sdk.WebViewClient;

import java.io.ByteArrayInputStream;
import java.util.Map;

/**
 * X5 内核（com.tencent.smtt.sdk.WebView）。
 * <p>
 * 与系统内核的差异：WebView 继承 FrameLayout；回调参数类型取自
 * {@code com.tencent.smtt.export.external.interfaces}；Cookie 由 X5 自己的
 * CookieManager 管理，与系统内核不共享。
 */
final class X5ParseKernel implements ParseKernel {

    private static final String TAG = "X5ParseKernel";

    private final WebView view;

    X5ParseKernel(WebView view) {
        this.view = view;
    }

    static X5ParseKernel create(Context context) {
        return new X5ParseKernel(new WebView(context));
    }

    @Override
    public View getView() {
        return view;
    }

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    public void init(Host host) {
        WebSettings setting = view.getSettings();
        setting.setSupportZoom(true);
        setting.setUseWideViewPort(true);
        setting.setDatabaseEnabled(true);
        setting.setDomStorageEnabled(true);
        setting.setJavaScriptEnabled(true);
        setting.setBuiltInZoomControls(true);
        setting.setDisplayZoomControls(false);
        setting.setLoadWithOverviewMode(true);
        setting.setUserAgentString(Setting.getUa());
        setting.setMediaPlaybackRequiresUserGesture(false);
        setting.setJavaScriptCanOpenWindowsAutomatically(false);
        // X5 未暴露 MIXED_CONTENT_* 常量，2 对应 COMPATIBILITY_MODE
        setting.setMixedContentMode(2);
        view.setWebViewClient(new Client(host));
    }

    @Override
    public void loadUrl(String url, Map<String, String> headers) {
        view.loadUrl(url, headers);
    }

    @Override
    public void loadUrl(String url) {
        view.loadUrl(url);
    }

    @Override
    public void evaluateJavascript(String script, Callback callback) {
        // X5 的 ValueCallback 继承自 android.webkit.ValueCallback，
        // 但其 evaluateJavascript 参数类型是 X5 自己的，故需显式声明类型避免重载歧义
        ValueCallback<String> cb = value -> callback.onResult(value);
        view.evaluateJavascript(script, cb);
    }

    @Override
    public void stopLoading() {
        view.stopLoading();
    }

    @Override
    public void destroy() {
        view.stopLoading();
        view.setWebViewClient(null);
        view.destroy();
    }

    @Override
    public void setUserAgentString(String userAgent) {
        view.getSettings().setUserAgentString(userAgent);
    }

    @Override
    public String getUserAgentString() {
        return view.getSettings().getUserAgentString();
    }

    @Override
    public void setCookies(String url, String cookies) {
        if (TextUtils.isEmpty(cookies)) return;
        CookieManager manager = CookieManager.getInstance();
        manager.setAcceptCookie(true);
        for (String cookie : cookies.split(";")) {
            cookie = cookie.trim();
            if (!cookie.isEmpty()) manager.setCookie(url, cookie);
        }
        manager.flush();
    }

    @Override
    public String getCookie(String url) {
        String value = CookieManager.getInstance().getCookie(url);
        return value == null ? "" : value;
    }

    @Override
    public void setAcceptThirdPartyCookies(boolean accept) {
        CookieManager.getInstance().setAcceptThirdPartyCookies(view, accept);
    }

    private static WebResourceResponse empty() {
        return new WebResourceResponse("text/plain", "utf-8", new ByteArrayInputStream(new byte[0]));
    }

    private class Client extends WebViewClient {

        private final Host host;

        Client(Host host) {
            this.host = host;
        }

        @Override
        public void onPageStarted(WebView view, String url, Bitmap favicon) {
            super.onPageStarted(view, url, favicon);
            host.onPageStarted();
        }

        @Override
        public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
            Uri uri = request.getUrl();
            if (host.onIntercept(uri, request.getRequestHeaders(), request.isForMainFrame())) return empty();
            return super.shouldInterceptRequest(view, request);
        }

        @Override
        public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
            super.onReceivedError(view, request, error);
            if (request.isForMainFrame()) host.onLoadError("network", error.getErrorCode());
        }

        @Override
        public void onReceivedHttpError(WebView view, WebResourceRequest request, WebResourceResponse errorResponse) {
            super.onReceivedHttpError(view, request, errorResponse);
            host.onHttpError(errorResponse.getStatusCode(), request.isForMainFrame(), WebViewCloudflare.isChallenge(errorResponse.getResponseHeaders()));
        }

        @Override
        public void onPageFinished(WebView view, String url) {
            super.onPageFinished(view, url);
            host.onPageFinished(url);
        }

        @Override
        @SuppressLint("WebViewClientOnReceivedSslError")
        public void onReceivedSslError(WebView view, SslErrorHandler handler, SslError error) {
            handler.proceed();
        }

        @Override
        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            return false;
        }

        // X5 的 WebViewClient 未提供与系统一致的 onRenderProcessGone 签名，
        // 故不覆写，渲染进程异常由 X5 内核自行处理
        public boolean onRenderProcessGone(WebView view, WebViewClient.RenderProcessGoneDetail detail) {
            SpiderDebug.log(TAG, "render process gone");
            host.onRenderProcessGone();
            return true;
        }
    }
}
