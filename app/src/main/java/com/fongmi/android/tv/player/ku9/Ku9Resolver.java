package com.fongmi.android.tv.player.ku9;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Color;
import android.os.Handler;
import android.os.Looper;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.exception.ExtractException;

import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * ku9:// 脚本执行引擎（移植自 WebViewTvLive 的 Ku9ScriptResolver，改用系统内核）。
 *
 * 流程：
 * 1. fetch() 在播放器后台线程调用（与 TVBus 相同契约），先下载脚本文本（带缓存）；
 * 2. 在 UI 线程创建/复用一只离屏系统 WebView，加载空白宿主页；
 * 3. onPageFinished 后 evaluateJavascript 注入：window.ku9 桥 + ES5 polyfill +
 *    done/fail + 用户脚本 + main(item)（支持同步返回值与 Promise）；
 * 4. 脚本经 ku9Bridge 同步抓数据后调 done()/fail()，由 Java 桥回收结果；
 * 5. fetch 线程 CountDownLatch 等待（30s 超时），拿到结果解析：
 *    - 普通 http(s) 地址 → 直接返回；
 *    - 整段 #EXTM3U 内容 → 交给本地 Ku9PlaylistServer 动态列表，按 TARGETDURATION/2
 *      周期性重跑脚本刷新（失败保留旧列表，不打断播放）。
 * 6. generation 代际计数 + 单飞 request 保证切源/换台时旧执行全部作废。
 */
public class Ku9Resolver {

    private static final int TIMEOUT_MS = 30_000;
    private static final int CREATE_TIMEOUT_MS = 10_000;
    private static final int REFRESH_MIN_MS = 2_000;
    private static final int REFRESH_MAX_MS = 5_000;
    private static final int REFRESH_FAIL_MS = 5_000;
    private static final String BASE_URL = "https://ku9.local/";
    private static final String PAGE = "<!DOCTYPE html><html><head><meta charset=\"utf-8\"></head><body></body></html>";
    private static final String TEMPLATE_ASSET = "js/ku9.js";
    private static final String SCRIPT_TAG = "/*__KU9_SCRIPT__*/";
    private static final String ITEM_TAG = "/*__KU9_ITEM__*/";

    /** 注入模板缓存（assets 内容固定，进程内只读一次）。 */
    private static volatile String template;

    private final Handler handler;
    private final Ku9Bridge bridge;
    private final AtomicReference<WebView> webView = new AtomicReference<>();
    private volatile int generation;
    private volatile Request current;
    private volatile Request pendingExecute;
    private volatile Ku9PlaylistServer server;

    private static class Loader {
        static volatile Ku9Resolver INSTANCE = new Ku9Resolver();
    }

    public static Ku9Resolver get() {
        return Loader.INSTANCE;
    }

    private Ku9Resolver() {
        handler = new Handler(Looper.getMainLooper());
        bridge = new Ku9Bridge(this);
    }

    /** 同步阻塞解析，返回可播放地址；失败抛 ExtractException。 */
    public String fetch(String sourceUrl) throws Exception {
        if (sourceUrl == null || sourceUrl.isEmpty()) throw new ExtractException("源地址为空");
        if (!sourceUrl.startsWith("http://") && !sourceUrl.startsWith("https://")) throw new ExtractException("JS脚本地址必须以 http(s) 开头");
        final int gen = next();
        String script = Ku9ScriptLoader.get().load(sourceUrl);
        ensureWebView();
        if (gen != generation) throw new ExtractException("任务已取消");
        JSONObject item = new JSONObject();
        item.put("url", sourceUrl);
        item.put("name", "");
        Request request = new Request(gen, script, item.toString());
        startExecution(request);
        if (!request.latch.await(TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            if (gen == generation) current = null;
            throw new ExtractException("JS脚本执行超时: " + sourceUrl);
        }
        if (gen != generation || current != request) throw new ExtractException("任务已取消");
        if (request.error != null) throw new ExtractException(request.error);
        if (request.value == null) throw new ExtractException("JS脚本未返回结果");
        return resolve(request, request.value);
    }

    /** 取消在途任务并停止动态列表服务（保留 WebView 复用）。 */
    public void stop() {
        next();
        closeServer();
    }

    /** 彻底释放（销毁 WebView、关闭服务）。 */
    public void destroy() {
        next();
        closeServer();
        if (Looper.myLooper() == Looper.getMainLooper()) destroyWebView();
        else handler.post(this::destroyWebView);
    }

    // ---------- 状态控制 ----------

    private synchronized int next() {
        generation++;
        Request r = current;
        current = null;
        if (r != null) r.latch.countDown();
        pendingExecute = null;
        return generation;
    }

    private void closeServer() {
        Ku9PlaylistServer s = server;
        server = null;
        if (s != null) s.close();
    }

    private void startExecution(Request request) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            handler.post(() -> startExecution(request));
            return;
        }
        WebView view = webView.get();
        if (view == null) {
            request.error = "WebView 不可用";
            request.latch.countDown();
            return;
        }
        pendingExecute = request;
        current = request;
        try {
            view.loadDataWithBaseURL(BASE_URL, PAGE, "text/html", "utf-8", null);
        } catch (Throwable t) {
            pendingExecute = null;
            request.error = String.valueOf(t.getMessage());
            request.latch.countDown();
        }
    }

    private void evaluate(Request request) {
        WebView view = webView.get();
        if (view == null) return;
        try {
            view.evaluateJavascript(buildJavascript(request), null);
        } catch (Throwable t) {
            request.error = String.valueOf(t.getMessage());
            request.latch.countDown();
        }
    }

    private void ensureWebView() throws Exception {
        if (webView.get() != null) return;
        if (Looper.myLooper() == Looper.getMainLooper()) {
            createWebView();
            return;
        }
        final CountDownLatch gate = new CountDownLatch(1);
        final AtomicReference<Throwable> fail = new AtomicReference<>();
        handler.post(() -> {
            try {
                createWebView();
            } catch (Throwable t) {
                fail.set(t);
            } finally {
                gate.countDown();
            }
        });
        if (!gate.await(CREATE_TIMEOUT_MS, TimeUnit.MILLISECONDS)) throw new ExtractException("WebView 初始化超时");
        if (fail.get() != null) throw new ExtractException("WebView 初始化失败: " + fail.get().getMessage());
    }

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    private void createWebView() {
        if (webView.get() != null) return;
        Context context = App.activity();
        if (context == null) context = App.get();
        WebView view = new WebView(context);
        WebSettings settings = view.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(false);
        settings.setDatabaseEnabled(false);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        settings.setMediaPlaybackRequiresUserGesture(true);
        settings.setCacheMode(WebSettings.LOAD_NO_CACHE);
        view.setBackgroundColor(Color.TRANSPARENT);
        view.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                Request request = pendingExecute;
                if (request == null) return;
                pendingExecute = null;
                evaluate(request);
            }
        });
        view.addJavascriptInterface(bridge, Ku9Bridge.NAME);
        webView.set(view);
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void destroyWebView() {
        WebView view = webView.getAndSet(null);
        if (view == null) return;
        try {
            view.stopLoading();
            view.loadUrl("about:blank");
        } catch (Throwable ignored) {
        }
        try {
            view.removeJavascriptInterface(Ku9Bridge.NAME);
        } catch (Throwable ignored) {
        }
        view.destroy();
    }

    // ---------- JS 桥回调（均在主线程执行） ----------

    void onComplete(String json) {
        handler.post(() -> {
            Request r = current;
            if (r == null || r.gen != generation) return;
            r.error = null;
            r.value = json;
            r.latch.countDown();
            if (r.playlist) continuePlaylist(r, json);
        });
    }

    void onFail(String error) {
        handler.post(() -> {
            Request r = current;
            if (r == null || r.gen != generation) return;
            r.error = error;
            r.value = null;
            r.latch.countDown();
            if (r.playlist) scheduleRefresh(r, REFRESH_FAIL_MS);
        });
    }

    // ---------- 结果解析 ----------

    private String resolve(Request request, String json) throws Exception {
        Object value = new JSONTokener(json).nextValue();
        if (value instanceof JSONObject) {
            String text = firstString((JSONObject) value, "url", "playUrl", "playurl", "m3u8", "content");
            if (text == null) {
                JSONArray urls = ((JSONObject) value).optJSONArray("urls");
                if (urls != null && urls.length() > 0) text = urls.optString(0);
            }
            value = text;
        }
        if (!(value instanceof String)) throw new ExtractException("JS脚本未返回可播放地址");
        String text = ((String) value).trim();
        if (text.isEmpty()) throw new ExtractException("JS脚本返回空地址");
        if (text.startsWith("#EXTM3U")) return startPlaylist(request, text);
        if (text.startsWith("http://") || text.startsWith("https://")) return text;
        throw new ExtractException("无法识别JS返回的播放地址: " + shorten(text));
    }

    private String startPlaylist(Request request, String content) throws Exception {
        Ku9PlaylistServer s = server;
        if (s == null) {
            synchronized (this) {
                if (server == null) server = new Ku9PlaylistServer();
                s = server;
            }
        }
        try {
            s.start();
        } catch (Exception e) {
            throw new ExtractException("启动本地动态列表服务失败: " + e.getMessage());
        }
        s.update(content);
        request.playlist = true;
        scheduleRefresh(request, refreshDelay(content));
        return s.url();
    }

    /** 动态列表刷新阶段的成功回调：更新内容并续订下一次刷新。 */
    private void continuePlaylist(Request request, String json) {
        try {
            Object value = new JSONTokener(json).nextValue();
            if (value instanceof JSONObject) {
                String text = firstString((JSONObject) value, "url", "playUrl", "playurl", "m3u8", "content");
                if (text == null) throw new Exception("no playlist");
                value = text;
            }
            if (value instanceof String && ((String) value).startsWith("#EXTM3U")) {
                Ku9PlaylistServer s = server;
                if (s != null) {
                    s.update((String) value);
                    scheduleRefresh(request, refreshDelay((String) value));
                }
            } else {
                scheduleRefresh(request, REFRESH_FAIL_MS);
            }
        } catch (Exception e) {
            scheduleRefresh(request, REFRESH_FAIL_MS);
        }
    }

    private void scheduleRefresh(Request request, long delay) {
        Runnable task = request.refresh;
        if (task == null) {
            task = () -> {
                if (request == current && request.gen == generation && request.playlist) startExecution(request);
            };
            request.refresh = task;
        }
        long ms = Math.max(REFRESH_MIN_MS, Math.min(REFRESH_MAX_MS, delay));
        handler.removeCallbacks(task);
        handler.postDelayed(task, ms);
    }

    private long refreshDelay(String m3u8) {
        Matcher matcher = Pattern.compile("#EXT-X-TARGETDURATION:\\s*(\\d+)").matcher(m3u8);
        if (matcher.find()) {
            long duration = Long.parseLong(matcher.group(1));
            if (duration > 0) return duration * 500L; // targetDuration/2
        }
        return REFRESH_FAIL_MS;
    }

    private String firstString(JSONObject object, String... keys) {
        for (String key : keys) {
            Object value = object.opt(key);
            if (value instanceof String && ((String) value).trim().length() > 0) return ((String) value).trim();
        }
        return null;
    }

    private String shorten(String text) {
        return text.length() > 100 ? text.substring(0, 100) + "..." : text;
    }

    // ---------- JS 注入 ----------

    /** 读取 assets/js/ku9.js 注入模板（进程内缓存）。 */
    private static String loadTemplate() {
        String t = template;
        if (t == null) {
            synchronized (Ku9Resolver.class) {
                if ((t = template) == null) {
                    try (InputStream input = App.get().getAssets().open(TEMPLATE_ASSET)) {
                        t = readAll(input);
                    } catch (IOException e) {
                        throw new IllegalStateException("读取 ku9 注入模板失败: " + TEMPLATE_ASSET, e);
                    }
                    template = t;
                }
            }
        }
        return t;
    }

    private static String readAll(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int len;
        while ((len = input.read(buffer)) > 0) output.write(buffer, 0, len);
        return output.toString("UTF-8");
    }

    /** 将模板中的占位符替换为用户脚本与 item，切分拼接以避免简单 replace 的误替换。 */
    private String buildJavascript(Request request) {
        String shell = loadTemplate();
        int i = shell.indexOf(SCRIPT_TAG);
        int j = shell.indexOf(ITEM_TAG);
        if (i < 0 || j < 0 || i > j) throw new IllegalStateException("ku9.js 模板缺少占位符");
        return shell.substring(0, i)
                + request.script
                + shell.substring(i + SCRIPT_TAG.length(), j)
                + request.item
                + shell.substring(j + ITEM_TAG.length());
    }

    private static class Request {

        final int gen;
        final String script;
        final String item;
        final CountDownLatch latch = new CountDownLatch(1);
        volatile boolean playlist;
        volatile Runnable refresh;
        volatile String value;
        volatile String error;

        Request(int gen, String script, String item) {
            this.gen = gen;
            this.script = script;
            this.item = item;
        }
    }
}
