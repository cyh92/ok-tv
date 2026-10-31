package com.fongmi.android.tv.ui.custom;

import android.webkit.ValueCallback;

/**
 * 脚本执行能力抽象。
 * <p>
 * 系统内核与 X5 内核都能执行 JS，但类型不同（android.webkit.WebView 与
 * com.tencent.smtt.sdk.WebView），故由本接口统一，供 WebViewRules / WebViewClicker 使用。
 */
interface Evaluator {

    void evaluate(String script, ValueCallback<String> callback);
}
