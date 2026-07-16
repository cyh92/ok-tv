package com.fongmi.android.tv.ui.custom;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.os.Build;
import android.util.AttributeSet;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.View.OnTouchListener;
import android.widget.FrameLayout;
import android.widget.ProgressBar;

import com.fongmi.android.tv.BuildConfig;
import com.fongmi.android.tv.setting.Setting;
import com.fongmi.android.tv.bean.Result;
import com.orhanobut.logger.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.util.Map;

public class WebViewPlayer extends FrameLayout {

    private static final String TAG = "WebViewPlayer";
    private static final String JS_FILE_PATH = "js/webview_player_impl.js";

    private ProgressBar progressBar;
    private boolean isUserInteractionEnabled = false;
    private VideoPlayerCallback callback;
    private String mDisableInteractionJs;

    private IWebViewKernel mKernel;
    private View mWebView;

    public interface VideoPlayerCallback {
        void onPageStarted();
        void onPageFinished(View webView);
        void onPageLoadProgress(int progress);
    }

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

    public void start(Result result) {
        if (result == null || result.getUrl() == null) {
            Logger.t(TAG).e("Invalid result: result or URL is null");
            return;
        }
        Logger.t(TAG).d("Starting WebView with URL: " + result.getUrl().v());
        Logger.t(TAG).d("用户交互状态: " + (isUserInteractionEnabled ? "启用" : "禁用"));
        Logger.t(TAG).d("触摸透明状态: " + isTouchTransparent());

        mKernel.loadUrl(result.getUrl().v(), result.getHeader());
        mWebView.setAlpha(0f);
        mWebView.animate()
                .alpha(1f)
                .setDuration(300)
                .start();
    }

    public void stop() {
        if (mKernel != null) {
            mKernel.stopLoading();
            mKernel.loadUrl("about:blank");
        }
        Logger.t(TAG).d("WebView stopped");
    }

    public void setCallback(VideoPlayerCallback callback) {
        this.callback = callback;
    }

    private void init(Context context) {
        int parseType = Setting.getParseWebView();
        boolean useX5 = parseType != 0
                && !"mobile".equals(BuildConfig.FLAVOR_mode)
                && com.tencent.smtt.sdk.QbSdk.isTbsCoreInited();

        if (useX5) {
            mKernel = new X5Kernel(context);
            Logger.t(TAG).d("当前内核：X5 WebView");
        } else {
            mKernel = new SystemKernel(context);
            Logger.t(TAG).d("当前内核：系统 WebView（parseType=" + parseType
                    + ", flavor=" + BuildConfig.FLAVOR_mode
                    + ", tbsInited=" + com.tencent.smtt.sdk.QbSdk.isTbsCoreInited() + "）");
        }
        mWebView = mKernel.getView();

        mWebView.setBackgroundColor(Color.BLACK);
        mWebView.setLayerType(View.LAYER_TYPE_HARDWARE, null);

        initProgressBar(context);
        setupInternalCallback();
        mKernel.configureSettings();
        addViewsToLayout();

        setClickable(false);
        setFocusable(false);
        setFocusableInTouchMode(false);
        Logger.t(TAG).d("初始化WebViewPlayer，默认禁用交互");
    }

    private void setupInternalCallback() {
        mKernel.setInternalClient(new InternalWebCallback() {
            @Override
            public void onPageStarted(String url, Bitmap favicon) {
                mWebView.setBackgroundColor(Color.BLACK);
                if (callback != null) {
                    callback.onPageStarted();
                }
            }

            @Override
            public void onPageFinished(String url) {
                if (!isUserInteractionEnabled) {
                    injectDisableInteractionScript();
                }
                if (callback != null) {
                    callback.onPageFinished(mWebView);
                }
            }

            @Override
            public void onProgressChanged(int progress) {
                if (progressBar != null) {
                    progressBar.setProgress(progress);
                    progressBar.setVisibility(progress == 100 ? View.GONE : View.VISIBLE);
                }
                if (callback != null) {
                    callback.onPageLoadProgress(progress);
                }
                Logger.t(TAG).d("Page load progress: " + progress + "%");
            }
        });
    }

    private void initProgressBar(Context context) {
        progressBar = new ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal);
        progressBar.setLayoutParams(new LayoutParams(LayoutParams.MATCH_PARENT, dpToPx(2)));
        progressBar.setMax(100);
        progressBar.setProgress(0);
    }

    private void addViewsToLayout() {
        addView(mWebView);
        addView(progressBar);
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent event) {
        if (!isUserInteractionEnabled) {
            Logger.t(TAG).d("禁用交互模式，跳过WebViewPlayer，直接让父视图处理: " + event.getAction());
            return false;
        }
        return super.dispatchTouchEvent(event);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (!isUserInteractionEnabled) {
            Logger.t(TAG).d("禁用交互模式，不处理触摸事件: " + event.getAction());
            return false;
        }
        return super.onTouchEvent(event);
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (mWebView != null && mWebView.dispatchKeyEvent(event)) {
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (mWebView != null && mWebView.onKeyDown(keyCode, event)) {
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    public void setUserInteractionEnabled(boolean enabled) {
        isUserInteractionEnabled = enabled;
        if (mWebView != null) {
            mWebView.setFocusable(enabled);
            mWebView.setFocusableInTouchMode(enabled);
        }

        if (!enabled) {
            setClickable(false);
            setFocusable(false);
            setFocusableInTouchMode(false);
        } else {
            setClickable(true);
            setFocusable(true);
            setFocusableInTouchMode(true);
        }

        Logger.t(TAG).d("用户交互设置: " + (enabled ? "启用" : "禁用") + ", 视图可点击: " + isClickable());
    }

    public void disableUserInteraction() {
        setUserInteractionEnabled(false);
    }

    public void enableUserInteraction() {
        setUserInteractionEnabled(true);
    }

    public boolean isUserInteractionEnabled() {
        return isUserInteractionEnabled;
    }

    public boolean isTouchTransparent() {
        return !isClickable() && !isFocusable();
    }

    private String loadJsFromAssets(String fileName) {
        InputStream inputStream = null;
        try {
            inputStream = getContext().getAssets().open(fileName);
            byte[] buffer = new byte[inputStream.available()];
            inputStream.read(buffer);
            return new String(buffer, "UTF-8");
        } catch (IOException e) {
            Logger.t(TAG).e("读取JS文件失败: " + fileName, e);
            return null;
        } finally {
            if (inputStream != null) {
                try {
                    inputStream.close();
                } catch (IOException ignored) {
                }
            }
        }
    }

    private void injectDisableInteractionScript() {
        if (mDisableInteractionJs == null) {
            mDisableInteractionJs = loadJsFromAssets(JS_FILE_PATH);
            if (mDisableInteractionJs == null) {
                Logger.t(TAG).e("JS文件读取失败，跳过注入");
                return;
            }
        }

        final String script = mDisableInteractionJs;

        mWebView.post(() -> {
            mKernel.evaluateJavascript(script);
            Logger.t(TAG).d("已从assets加载并注入禁用交互JS");
        });
    }

    public void onResume() {
        if (mKernel != null) {
            mKernel.onResume();
        }
    }

    public void onPause() {
        if (mKernel != null) {
            mKernel.onPause();
        }
    }

    public void destroy() {
        if (mKernel != null) {
            mKernel.destroy();
            mKernel = null;
            mWebView = null;
        }
        mDisableInteractionJs = null;
        removeAllViews();
    }

    private int dpToPx(int dp) {
        return (int) (dp * getResources().getDisplayMetrics().density);
    }

    private interface IWebViewKernel {
        View getView();

        void loadUrl(String url, Map<String, String> headers);

        void loadUrl(String url);
        void evaluateJavascript(String script);
        void stopLoading();

        void onResume();

        void onPause();

        void destroy();

        void configureSettings();

        void setInternalClient(InternalWebCallback callback);
    }

    private interface InternalWebCallback {
        void onPageStarted(String url, Bitmap favicon);

        void onPageFinished(String url);

        void onProgressChanged(int progress);
    }

    private class X5Kernel implements IWebViewKernel {
        private com.tencent.smtt.sdk.WebView webView;
        private InternalWebCallback callback;

        public X5Kernel(Context context) {
            webView = new com.tencent.smtt.sdk.WebView(context);
        }

        @Override
        public View getView() {
            return webView;
        }

        @Override
        public void loadUrl(String url, Map<String, String> headers) {
            webView.loadUrl(url, headers);
        }
        @Override
        public void evaluateJavascript(String script) {
            webView.evaluateJavascript(script, null);
        }
        @Override
        public void loadUrl(String url) {
            webView.loadUrl(url);
        }

        @Override
        public void stopLoading() {
            webView.stopLoading();
        }

        @Override
        public void onResume() {
            webView.onResume();
            webView.resumeTimers();
        }

        @Override
        public void onPause() {
            webView.onPause();
            webView.pauseTimers();
        }

        @Override
        public void destroy() {
            webView.stopLoading();
            webView.setWebViewClient(null);
            webView.setWebChromeClient(null);
            webView.destroy();
        }

        @Override
        public void configureSettings() {
            com.tencent.smtt.sdk.WebSettings settings = webView.getSettings();

            settings.setJavaScriptEnabled(true);
            settings.setDomStorageEnabled(true);
            settings.setUseWideViewPort(true);
            settings.setLoadWithOverviewMode(true);

            settings.setCacheMode(com.tencent.smtt.sdk.WebSettings.LOAD_DEFAULT);
            settings.setAppCacheEnabled(true);

            settings.setSupportZoom(false);
            settings.setBuiltInZoomControls(false);
            settings.setDisplayZoomControls(false);

            settings.setMediaPlaybackRequiresUserGesture(false);
            settings.setJavaScriptCanOpenWindowsAutomatically(true);

            String userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Safari/537.36 Edg/137.0.0.0";
            settings.setUserAgentString(userAgent);

            settings.setAllowFileAccess(false);
            settings.setAllowContentAccess(false);
            settings.setAllowFileAccessFromFileURLs(false);
            settings.setAllowUniversalAccessFromFileURLs(false);
            settings.setDatabaseEnabled(false);
            settings.setGeolocationEnabled(false);

            settings.setMixedContentMode(2);

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                settings.setSafeBrowsingEnabled(false);
            }

            webView.setFocusable(false);
            webView.setFocusableInTouchMode(false);
            webView.setScrollBarStyle(View.SCROLLBARS_OUTSIDE_OVERLAY);
            webView.setScrollbarFadingEnabled(true);

            webView.setOnTouchListener((v, event) -> false);

            Logger.t(TAG).d("X5内核配置完成");
        }

        @Override
        public void setInternalClient(InternalWebCallback callback) {
            this.callback = callback;
            webView.setWebViewClient(new com.tencent.smtt.sdk.WebViewClient() {
                @Override
                public void onPageStarted(com.tencent.smtt.sdk.WebView view, String url, Bitmap favicon) {
                    super.onPageStarted(view, url, favicon);
                    if (callback != null) callback.onPageStarted(url, favicon);
                }

                @Override
                public void onPageFinished(com.tencent.smtt.sdk.WebView view, String url) {
                    super.onPageFinished(view, url);
                    if (callback != null) callback.onPageFinished(url);
                }
            });

            webView.setWebChromeClient(new com.tencent.smtt.sdk.WebChromeClient() {
                @Override
                public void onProgressChanged(com.tencent.smtt.sdk.WebView view, int newProgress) {
                    super.onProgressChanged(view, newProgress);
                    if (callback != null) callback.onProgressChanged(newProgress);
                }
            });
        }
    }

    private class SystemKernel implements IWebViewKernel {
        private android.webkit.WebView webView;
        private InternalWebCallback callback;

        public SystemKernel(Context context) {
            webView = new android.webkit.WebView(context);
        }

        @Override
        public View getView() {
            return webView;
        }

        @Override
        public void loadUrl(String url, Map<String, String> headers) {
            webView.loadUrl(url, headers);
        }

        @Override
        public void loadUrl(String url) {
            webView.loadUrl(url);
        }

        @Override
        public void evaluateJavascript(String script) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
                webView.evaluateJavascript(script, null);
            } else {
                webView.loadUrl("javascript:" + script);
            }
        }

        @Override
        public void stopLoading() {
            webView.stopLoading();
        }

        @Override
        public void onResume() {
            webView.onResume();
            webView.resumeTimers();
        }

        @Override
        public void onPause() {
            webView.onPause();
            webView.pauseTimers();
        }

        @Override
        public void destroy() {
            webView.stopLoading();
            webView.setWebViewClient(null);
            webView.setWebChromeClient(null);
            webView.destroy();
        }

        @Override
        public void configureSettings() {
            android.webkit.WebSettings settings = webView.getSettings();

            settings.setJavaScriptEnabled(true);
            settings.setDomStorageEnabled(true);
            settings.setUseWideViewPort(true);
            settings.setLoadWithOverviewMode(true);

            settings.setCacheMode(android.webkit.WebSettings.LOAD_DEFAULT);
//            settings.setAppCacheEnabled(true);
            settings.setSupportZoom(false);
            settings.setBuiltInZoomControls(false);
            settings.setDisplayZoomControls(false);

            settings.setMediaPlaybackRequiresUserGesture(false);
            settings.setJavaScriptCanOpenWindowsAutomatically(true);

            String userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Safari/537.36 Edg/137.0.0.0";
            settings.setUserAgentString(userAgent);

            settings.setAllowFileAccess(false);
            settings.setAllowContentAccess(false);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) {
                settings.setAllowFileAccessFromFileURLs(false);
                settings.setAllowUniversalAccessFromFileURLs(false);
            }
            settings.setDatabaseEnabled(false);
            settings.setGeolocationEnabled(false);

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                settings.setMixedContentMode(android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                settings.setSafeBrowsingEnabled(false);
            }

            webView.setFocusable(false);
            webView.setFocusableInTouchMode(false);
            webView.setScrollBarStyle(View.SCROLLBARS_OUTSIDE_OVERLAY);
            webView.setScrollbarFadingEnabled(true);

            webView.setOnTouchListener((v, event) -> false);

            Logger.t(TAG).d("系统内核配置完成");
        }

        @Override
        public void setInternalClient(InternalWebCallback callback) {
            this.callback = callback;
            webView.setWebViewClient(new android.webkit.WebViewClient() {
                @Override
                public void onPageStarted(android.webkit.WebView view, String url, Bitmap favicon) {
                    super.onPageStarted(view, url, favicon);
                    if (callback != null) callback.onPageStarted(url, favicon);
                }

                @Override
                public void onPageFinished(android.webkit.WebView view, String url) {
                    super.onPageFinished(view, url);
                    if (callback != null) callback.onPageFinished(url);
                }
            });

            webView.setWebChromeClient(new android.webkit.WebChromeClient() {
                @Override
                public void onProgressChanged(android.webkit.WebView view, int newProgress) {
                    super.onProgressChanged(view, newProgress);
                    if (callback != null) callback.onProgressChanged(newProgress);
                }
            });
        }
    }
}