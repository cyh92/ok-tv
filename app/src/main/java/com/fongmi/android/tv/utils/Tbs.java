package com.fongmi.android.tv.utils;

import android.os.Build;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.BuildConfig;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.impl.X5WebViewCallback;
import com.fongmi.android.tv.setting.Setting;
import com.github.catvod.utils.Path;
import com.orhanobut.logger.Logger;
import com.tencent.smtt.sdk.QbSdk;
import com.tencent.smtt.sdk.TbsCommonCode;
import com.tencent.smtt.sdk.TbsDownloader;
import com.tencent.smtt.sdk.TbsListener;
import com.tencent.smtt.export.external.TbsCoreSettings;

import java.io.File;
import java.util.HashMap;

public class Tbs {
    private static final String TAG = Tbs.class.getSimpleName();

    private static boolean isCpu64Bit() {
        for (String abi : Build.SUPPORTED_ABIS) {
            if (abi.contains("64")) return true;
        }
        return false;
    }

    private static void tbsInit() {
        HashMap<String, Object> map = new HashMap<>();
        map.put(TbsCoreSettings.TBS_SETTINGS_USE_PRIVATE_CLASSLOADER, true);
        map.put(TbsCoreSettings.TBS_SETTINGS_USE_SPEEDY_CLASSLOADER, true);
        map.put(TbsCoreSettings.TBS_SETTINGS_USE_DEXLOADER_SERVICE, true);
        QbSdk.initTbsSettings(map);
        TbsDownloader.stopDownload();
        QbSdk.PreInitCallback callback = new QbSdk.PreInitCallback() {
            @Override
            public void onViewInitFinished(boolean finished) {
                if (finished) Notify.show(R.string.x5webview_enabled);
            }

            @Override
            public void onCoreInitFinished() {
                Logger.t(TAG).d("X5 Core init finished");
            }
        };
        QbSdk.initX5Environment(App.get(), callback);
    }

    public static void init() {
        if (Setting.getParseWebView() == 0) return;
        if (QbSdk.isTbsCoreInited()) return;
        App.post(() -> tbsInit());
    }

    public static String url() {
        String base = "https://gitee.com/api/v5/repos/cyh92/live/releases/624126/attach_files/";
        String fileId = isCpu64Bit() ? "2685847" : "2685844";
        return base + fileId + "/download?access_token=" + BuildConfig.GITEE_Token;
    }

    public static File file() {
        return Path.cache("TBScore.apk");
    }

    public static void remove() {
        File file = file();
        if (file.exists()) file.delete();
    }

    public static void install(X5WebViewCallback callback) {
        if (QbSdk.canLoadX5(App.get())) return;
        HashMap<String, Object> map = new HashMap<>();
        map.put(TbsCoreSettings.TBS_SETTINGS_USE_PRIVATE_CLASSLOADER, true);
        map.put(TbsCoreSettings.TBS_SETTINGS_USE_SPEEDY_CLASSLOADER, true);
        map.put(TbsCoreSettings.TBS_SETTINGS_USE_DEXLOADER_SERVICE, true);

        QbSdk.initTbsSettings(map);
        TbsListener tbsListener = new TbsListener() {

            /**
             * @param stateCode 用户可处理错误码请参考{@link TbsCommonCode}
             */
            @Override
            public void onDownloadFinish(int stateCode) {
                Logger.t(TAG).d("onDownloadFinish:" + stateCode);
            }

            /**
             * @param stateCode 用户可处理错误码请参考{@link TbsCommonCode}
             */
            @Override
            public void onInstallFinish(int stateCode) {
                Logger.t(TAG).d("onInstallFinish:" + stateCode);
                if (stateCode == TbsCommonCode.INSTALL_SUCCESS) callback.onX5Success();
                else callback.onX5Error();
            }

            /**
             * 首次安装应用，会触发内核下载，此时会有内核下载的进度回调。
             * @param progress 0 - 100
             */
            @Override
            public void onDownloadProgress(int progress) {
                Logger.t(TAG).d("onDownloadProgress:" + progress);
            }
        };
        QbSdk.setTbsListener(tbsListener);
        int version = isCpu64Bit() ? 46295 : 45912;
        QbSdk.reset(App.get());
        QbSdk.installLocalTbsCore(App.get(), version, file().getAbsolutePath());
    }

}
