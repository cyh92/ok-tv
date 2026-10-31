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
                SpiderDebug.log(TAG, "parse kernel = X5");
                return kernel;
            } catch (Throwable e) {
                SpiderDebug.log(TAG, "X5 kernel unavailable, fallback to system: " + e);
            }
        }
        SpiderDebug.log(TAG, "parse kernel = system");
        return SystemParseKernel.create(context);
    }

    static boolean useX5() {
        return Setting.getParseWebView() != 0 && QbSdk.isTbsCoreInited();
    }

    private ParseKernels() {
    }
}
