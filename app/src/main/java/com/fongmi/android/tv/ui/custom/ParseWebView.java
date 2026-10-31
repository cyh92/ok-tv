package com.fongmi.android.tv.ui.custom;

import android.app.Activity;
import android.content.Context;
import android.content.DialogInterface;
import android.net.Uri;
import android.text.TextUtils;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.Constant;
import com.fongmi.android.tv.api.config.VodConfig;
import com.fongmi.android.tv.impl.ParseCallback;
import com.fongmi.android.tv.ui.dialog.CloudflareDialog;
import com.fongmi.android.tv.utils.Sniffer;
import com.fongmi.android.tv.utils.UrlUtil;
import com.github.catvod.crawler.Spider;
import com.github.catvod.crawler.SpiderDebug;
import com.google.common.net.HttpHeaders;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.regex.Pattern;

/**
 * 解析源 WebView。
 * <p>
 * 自身为 FrameLayout 容器，实际渲染交给 {@link ParseKernel}，
 * 以便在系统内核与 X5 内核之间切换。
 */
public final class ParseWebView extends FrameLayout implements DialogInterface.OnDismissListener, ParseKernel.Host {

    private static final String TAG = "ParseWebView";

    private static final Pattern PLAYER = Pattern.compile("player.*https?://");
    private static final String BLANK = "about:blank";
    private static final int MAX_URLS = 5;

    private final ParseKernel kernel;
    private final AtomicReference<ParseCallback> callbackRef = new AtomicReference<>();
    private final List<ParseWebView> children = new ArrayList<>();
    private final LinkedHashSet<String> urls = new LinkedHashSet<>();
    private final Runnable timer = () -> stop(true);
    private CloudflareDialog dialog;
    private volatile String userAgent;
    private boolean destroyed;
    private boolean stopped;
    private volatile boolean loadFailed;
    private boolean detect;
    private int pageGeneration;
    private String clickScript;
    private String from;
    private String key;
    private String url;

    public static ParseWebView create(@NonNull Context context) {
        return new ParseWebView(context);
    }

    private ParseWebView(@NonNull Context context) {
        super(context);
        kernel = ParseKernels.create(context);
        kernel.init(this);
        userAgent = kernel.getUserAgentString();
        addView(kernel.getView(), new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
    }

    private View view() {
        return kernel.getView();
    }

    public ParseWebView start(String key, String from, Map<String, String> headers, String url, String click, ParseCallback callback, boolean detect) {
        SpiderDebug.log(TAG, "key=%s, from=%s, click=%s, url=%s, headers=%s", key, from, click, url, headers);
        App.post(timer, Constant.TIMEOUT_PARSE_WEB);
        callbackRef.set(callback);
        this.detect = detect;
        this.clickScript = click;
        this.from = from;
        this.key = key;
        this.url = url;
        Map<String, String> safeHeaders = headers == null ? Collections.emptyMap() : headers;
        WebViewClicker.ensureViewport(view());
        setHeaders(safeHeaders, url);
        kernel.loadUrl(url, safeHeaders);
        return this;
    }

    private void setHeaders(Map<String, String> headers, String url) {
        kernel.setAcceptThirdPartyCookies(true);
        for (Map.Entry<String, String> entry : headers.entrySet()) applyHeader(entry, url);
    }

    private void applyHeader(Map.Entry<String, String> entry, String url) {
        String name = entry.getKey();
        if (HttpHeaders.USER_AGENT.equalsIgnoreCase(UrlUtil.fixHeader(name))) {
            userAgent = entry.getValue();
            kernel.setUserAgentString(userAgent);
        } else if (HttpHeaders.COOKIE.equalsIgnoreCase(name)) {
            kernel.setCookies(url, entry.getValue());
        }
    }

    @Override
    public void onPageStarted() {
        pageGeneration++;
    }

    @Override
    public boolean onIntercept(Uri uri, Map<String, String> headers, boolean isMainFrame) {
        if (callbackRef.get() == null || loadFailed) return false;
        String url = uri.toString();
        String host = uri.getHost();
        if (isLocalDocument(uri)) return false;
        boolean cloudflare = WebViewCloudflare.isResource(uri);
        if (TextUtils.isEmpty(host) || (!cloudflare && WebViewRules.isAd(uri))) return true;
        if (WebViewCloudflare.isTurnstile(uri)) App.post(ParseWebView.this::showDialog);
        if (detect && PLAYER.matcher(url).find() && addUrl(url)) {
            onParseAdd(headers, url);
        } else if (isVideoFormat(url)) {
            App.post(() -> {
                if (!loadFailed) onParseSuccess(headers, url);
            });
        }
        return false;
    }

    @Override
    public void onLoadError(String kind, int code) {
        if (stopped || loadFailed || callbackRef.get() == null) return;
        SpiderDebug.log(TAG, "load error=%s %d", kind, code);
        loadFailed = true;
        kernel.stopLoading();
        if (children.isEmpty()) stop(true);
    }

    @Override
    public void onHttpError(int statusCode, boolean isMainFrame, boolean challenge) {
        if (challenge) App.post(ParseWebView.this::showDialog);
        else if (isMainFrame) onLoadError("HTTP", statusCode);
    }

    @Override
    public void onPageFinished(String url) {
        if (BLANK.equals(url) || stopped || loadFailed) return;
        int generation = pageGeneration;
        BooleanSupplier current = () -> !stopped && !loadFailed && generation == pageGeneration;
        Uri uri = Uri.parse(url);
        kernel.evaluateJavascript(WebViewCloudflare.CHECK_SCRIPT, value -> {
            if (!current.getAsBoolean()) return;
            if ("true".equalsIgnoreCase(value)) showDialog();
            else WebViewRules.apply(view(), kernel::evaluateJavascript, uri, clickScript, current);
        });
    }

    @Override
    public void onRenderProcessGone() {
        stop(true, true);
        destroyView();
    }

    private static boolean isLocalDocument(Uri uri) {
        return "about".equalsIgnoreCase(uri.getScheme()) || "data".equalsIgnoreCase(uri.getScheme());
    }

    private synchronized boolean addUrl(String url) {
        return urls.size() < MAX_URLS && urls.add(url);
    }

    private void showDialog() {
        Activity activity = App.activity();
        if (stopped || callbackRef.get() == null || dialog != null || activity == null || activity.isFinishing() || activity.isDestroyed()) return;
        if (getParent() != null) ((ViewGroup) getParent()).removeView(this);
        dialog = CloudflareDialog.create(activity, this, this).show();
        App.removeCallbacks(timer);
    }

    private void hideDialog() {
        CloudflareDialog current = dialog;
        dialog = null;
        if (current != null) current.dismiss();
    }

    @Override
    public void onDismiss(DialogInterface dialog) {
        this.dialog = null;
        stop(true);
    }

    private boolean isVideoFormat(String url) {
        try {
            if (!detect && url.equals(this.url)) return false;
            Spider spider = VodConfig.get().getSite(key).spider();
            if (spider.manualVideoCheck()) return spider.isVideoFormat(url);
            return Sniffer.isVideoFormat(url);
        } catch (Exception ignored) {
            return Sniffer.isVideoFormat(url);
        }
    }

    private void onParseAdd(Map<String, String> headers, String url) {
        if (callbackRef.get() == null) return;
        App.post(() -> startChild(headers, url));
    }

    private void startChild(Map<String, String> headers, String url) {
        if (stopped || loadFailed || callbackRef.get() == null) return;
        ParseWebView child = ParseWebView.create(App.get());
        children.add(child);
        child.start(key, from, headers, url, clickScript, new ParseCallback() {
            @Override
            public void onParseSuccess(Map<String, String> headers, String url, String from) {
                ParseWebView.this.onParseSuccess(headers, url);
            }

            @Override
            public void onParseError() {
                children.remove(child);
                child.release();
                if (loadFailed && children.isEmpty()) stop(true);
            }
        }, false);
    }

    private void onParseSuccess(Map<String, String> headers, String url) {
        ParseCallback cb = callbackRef.getAndSet(null);
        if (cb == null) return;
        try {
            Uri uri = Uri.parse(url);
            SpiderDebug.log(TAG, "success=%s://%s%s", uri.getScheme(), uri.getHost(), uri.getPath());
            cb.onParseSuccess(getResultHeaders(headers, url), url, from);
        } finally {
            stop(false);
        }
    }

    private Map<String, String> getResultHeaders(Map<String, String> headers, String url) {
        Map<String, String> result = new LinkedHashMap<>(headers == null ? Collections.emptyMap() : headers);
        putHeader(result, HttpHeaders.COOKIE, kernel.getCookie(url));
        putHeader(result, HttpHeaders.USER_AGENT, userAgent);
        return result;
    }

    private static void putHeader(Map<String, String> headers, String name, String value) {
        if (TextUtils.isEmpty(value)) return;
        headers.keySet().removeIf(name::equalsIgnoreCase);
        headers.put(name, value);
    }

    private void stop(boolean error) {
        stop(error, false);
    }

    private void stop(boolean error, boolean rendererGone) {
        if (stopped) return;
        ParseCallback cb = callbackRef.getAndSet(null);
        stopped = true;
        hideDialog();
        if (!rendererGone) {
            kernel.stopLoading();
            kernel.loadUrl(BLANK);
        }
        App.removeCallbacks(timer);
        for (ParseWebView child : children) child.release();
        children.clear();
        synchronized (this) {
            urls.clear();
        }
        if (error && cb != null) cb.onParseError();
    }

    public void release() {
        stop(false);
        destroyView();
    }

    private void destroyView() {
        if (destroyed) return;
        destroyed = true;
        kernel.destroy();
    }
}
