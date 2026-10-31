package com.fongmi.android.tv.ui.custom;

/**
 * 脚本执行能力抽象。
 * <p>
 * 系统内核与 X5 内核都能执行 JS，但回调类型不同（android.webkit.ValueCallback 与
 * com.tencent.smtt.sdk.ValueCallback），故统一用 {@link ParseKernel.Callback}。
 */
interface Evaluator {

    void evaluate(String script, ParseKernel.Callback callback);
}
