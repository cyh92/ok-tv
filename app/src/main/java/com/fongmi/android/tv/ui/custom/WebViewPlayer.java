package com.fongmi.android.tv.ui.custom;

import android.content.Context;
import android.os.Build;
import android.util.AttributeSet;
import android.util.Log;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;

//import android.webkit.WebChromeClient;
//import android.webkit.WebSettings;
//import android.webkit.WebView;
//import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.ProgressBar;

import com.tencent.smtt.sdk.WebChromeClient;
import com.tencent.smtt.sdk.WebSettings;
import com.tencent.smtt.sdk.WebView;
import com.tencent.smtt.sdk.WebViewClient;

import com.fongmi.android.tv.bean.Channel;

public class WebViewPlayer extends FrameLayout {

    private static final String TAG = "WebViewPlayer";
    public WebView webView;
    private ProgressBar progressBar;
    private View touchInterceptor;
    private boolean isUserInteractionEnabled;

    public WebViewPlayer(Context context) {
        this(context, null);
    }

    public WebViewPlayer(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public WebViewPlayer(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init(context);
    }
    public void start(Channel result) {
        webView.loadUrl(result.getUrl(), result.getHeaders());
    }
    public void stop(){
        webView.loadUrl("about:blank");
    }
    private void init(Context context) {
        initWebView(context);
        initProgressBar(context);
        initTouchInterceptor(context);
        addViewsToLayout();
    }

    private void initWebView(Context context) {
        webView = new WebView(context);
        setupWebViewSettings();
        setDefaultWebClients();
    }

    private void setupWebViewSettings() {
        WebSettings settings = webView.getSettings();
        // 基本设置
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setUseWideViewPort(true);
        settings.setLoadWithOverviewMode(true);
        settings.setLoadsImagesAutomatically(false); // 禁用自动加载图片
        settings.setBlockNetworkImage(true); // 禁用网络图片加载
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setUserAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Safari/537.36 Edg/137.0.0.0");
        // 启用缓存
        settings.setCacheMode(WebSettings.LOAD_DEFAULT);
        // 启用 JavaScript 自动点击功能
        settings.setJavaScriptCanOpenWindowsAutomatically(true);
        // 安全设置
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setAllowFileAccessFromFileURLs(false);
        settings.setAllowUniversalAccessFromFileURLs(false);
        settings.setDatabaseEnabled(false);
        settings.setGeolocationEnabled(false);
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            settings.setSafeBrowsingEnabled(false);
        }
        
        // 媒体播放设置
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
            settings.setMediaPlaybackRequiresUserGesture(false);
        }

        // TV适配设置
        webView.setFocusable(true);
        webView.setFocusableInTouchMode(true);
    }

    private void setDefaultWebClients() {
        webView.setWebViewClient(new WebViewClient());
        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int newProgress) {
                if (progressBar != null) {
                    progressBar.setProgress(newProgress);
                    progressBar.setVisibility(newProgress == 100 ? View.GONE : View.VISIBLE);
                }
            }
        });
    }

    private void initProgressBar(Context context) {
        progressBar = new ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal);
        progressBar.setLayoutParams(new LayoutParams(LayoutParams.MATCH_PARENT, dpToPx(2)));
        progressBar.setMax(100);
        progressBar.setProgress(0);
    }

    private void initTouchInterceptor(Context context) {
        touchInterceptor = new View(context) {
            @Override
            public boolean onTouchEvent(MotionEvent event) {
                if (!isUserInteractionEnabled) {
                    ViewParent parent = getParent();
                    if (parent instanceof ViewGroup) {
                        return ((ViewGroup) parent).onTouchEvent(event);
                    }
                    return false;
                }
                return super.onTouchEvent(event);
            }
        };
        touchInterceptor.setLayoutParams(new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
    }

    private void addViewsToLayout() {
        addView(webView);
        addView(progressBar);
        addView(touchInterceptor);
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (webView != null && webView.dispatchKeyEvent(event)) {
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (webView != null && webView.onKeyDown(keyCode, event)) {
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }
    public void setWebViewClient(WebViewClient client) {
        if (webView != null) {
            webView.setWebViewClient(client);
        }
    }

    public void setWebChromeClient(WebChromeClient client) {
        if (webView != null) {
            webView.setWebChromeClient(client);
        }
    }

    public void setUserInteractionEnabled(boolean enabled) {
        isUserInteractionEnabled = enabled;
        if (touchInterceptor != null) {
            touchInterceptor.setVisibility(enabled ? View.GONE : View.VISIBLE);
        }
    }

    public void onResume() {
        if (webView != null) {
            webView.onResume();
            webView.resumeTimers();
        }
    }

    public void onPause() {
        if (webView != null) {
            webView.onPause();
            webView.pauseTimers();
        }
    }

    public void destroy() {
        if (webView != null) {
            webView.stopLoading();
            webView.setWebViewClient(null);
            webView.setWebChromeClient(null);
            webView.destroy();
            webView = null;
        }
        removeAllViews();
    }

    private int dpToPx(int dp) {
        return (int) (dp * getResources().getDisplayMetrics().density);
    }
}