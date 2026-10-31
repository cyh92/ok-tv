package com.fongmi.android.tv.ui.custom;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Bitmap;
import android.net.Uri;
import android.net.http.SslError;
import android.os.Build;
import android.text.TextUtils;
import android.view.View;
import android.webkit.CookieManager;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.SslErrorHandler;
import android.webkit.ValueCallback;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.annotation.RequiresApi;

import com.fongmi.android.tv.setting.Setting;

import java.io.ByteArrayInputStream;
import java.util.Map;

/** 系统内核（android.webkit.WebView）。 */
final class SystemParseKernel implements ParseKernel {

    private final WebView view;

    SystemParseKernel(WebView view) {
        this.view = view;
    }

    static SystemParseKernel create(Context context) {
        return new SystemParseKernel(new WebView(context));
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
        setting.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        view.setWebViewClient(Build.VERSION.SDK_INT >= Build.VERSION_CODES.O ? new OreoClient(host) : new Client(host));
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
    public void evaluateJavascript(String script, ValueCallback<String> callback) {
        view.evaluateJavascript(script, callback);
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
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private final class OreoClient extends Client {

        OreoClient(Host host) {
            super(host);
        }

        @Override
        public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail detail) {
            host.onRenderProcessGone();
            return true;
        }
    }
}
