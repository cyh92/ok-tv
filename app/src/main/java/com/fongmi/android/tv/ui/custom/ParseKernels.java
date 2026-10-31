package com.fongmi.android.tv.ui.custom;

import android.content.Context;

import com.fongmi.android.tv.setting.Setting;
import com.github.catvod.crawler.SpiderDebug;
import com.tencent.smtt.sdk.QbSdk;

/** 解析源内核选择。 */
final class ParseKernels {

    private static final String TAG = "ParseKernels";

    static ParseKernel create(Context context) {
        if (useX5()) {
            try {
                ParseKernel kernel = X5ParseKernel.create(context);
                SpiderDebug.log(TAG, "parse kernel = X5 (tbsVersion=%s, coreInited=%s)", QbSdk.getTbsVersion(context), QbSdk.isTbsCoreInited());
                return kernel;
            } catch (Throwable e) {
                SpiderDebug.log(TAG, "X5 kernel unavailable, fallback to system: " + e);
            }
        } else {
            SpiderDebug.log(TAG, "parse kernel = system (setting=%s)", Setting.getParseWebView());
        }
        return SystemParseKernel.create(context);
    }

    /**
     * 只依据设置项判断。
     * <p>
     * 不用 QbSdk.isTbsCoreInited()：那是运行期状态，X5 初始化是异步的，
     * 解析源发起时通常尚未就绪，会导致设置成 X5 也永远走系统内核。
     * X5 core 未安装时，X5 WebView 内部会自行回退到系统内核，不会崩溃。
     */
    static boolean useX5() {
        return Setting.getParseWebView() != 0;
    }

    private ParseKernels() {
    }
}
