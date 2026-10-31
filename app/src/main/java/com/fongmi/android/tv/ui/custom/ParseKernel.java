package com.fongmi.android.tv.ui.custom;

import android.net.Uri;
import android.view.View;

import java.util.Map;

/**
 * 解析源 WebView 内核抽象。
 * <p>
 * 系统内核用 {@code android.webkit.WebView}，X5 内核用 {@code com.tencent.smtt.sdk.WebView}。
 * 两者 API 名称一致，但回调参数类型分属不同包
 * （X5 用 {@code com.tencent.smtt.export.external.interfaces} 下的
 * WebResourceRequest / WebResourceError / WebResourceResponse / SslError），
 * 且 X5 的 WebView 继承 FrameLayout 而非 android.webkit.WebView，
 * 因此业务逻辑统一通过本接口与 {@link Host} 回调通信，不直接接触具体内核类型。
 */
interface ParseKernel {

    /** 业务回调宿主，由 ParseWebView 实现。 */
    interface Host {

        void onPageStarted();

        /**
         * 资源请求拦截。
         * 返回 true 表示该请求应被拦截，由内核返回空响应。
         */
        boolean onIntercept(Uri uri, Map<String, String> headers, boolean isMainFrame);

        void onLoadError(String kind, int code);

        void onHttpError(int statusCode, boolean isMainFrame, boolean challenge);

        void onPageFinished(String url);

        void onRenderProcessGone();
    }

    /**
     * 脚本执行结果回调。
     * <p>
     * 不用 android.webkit.ValueCallback：X5 的 evaluateJavascript 用的是
     * com.tencent.smtt.sdk.ValueCallback，两者互不兼容，故由本接口统一。
     */
    interface Callback {

        void onResult(String value);
    }

    /** 实际承载渲染的 View，用于布局与触摸分发。 */
    View getView();

    void init(Host host);

    void loadUrl(String url, Map<String, String> headers);

    void loadUrl(String url);

    void evaluateJavascript(String script, Callback callback);

    void stopLoading();

    void destroy();

    void setUserAgentString(String userAgent);

    String getUserAgentString();

    /** 写入 Cookie，cookies 以分号分隔的多个键值对。 */
    void setCookies(String url, String cookies);

    String getCookie(String url);

    void setAcceptThirdPartyCookies(boolean accept);
}
