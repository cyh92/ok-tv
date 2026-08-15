package com.fongmi.android.tv.ui.activity;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.view.View;

import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.databinding.ActivitySettingCustomBinding;
import com.fongmi.android.tv.event.RefreshEvent;
import com.fongmi.android.tv.impl.X5WebViewCallback;
import com.fongmi.android.tv.setting.Setting;
import com.fongmi.android.tv.ui.base.BaseActivity;
import com.fongmi.android.tv.ui.dialog.X5WebViewDialog;
import com.fongmi.android.tv.utils.Notify;
import com.fongmi.android.tv.utils.ResUtil;
import com.tencent.smtt.sdk.QbSdk;

public class SettingCustomActivity extends BaseActivity implements X5WebViewCallback {
    private ActivitySettingCustomBinding mBinding;
    private String[] parseWebview = {"系统", "X5 WebView"};

    private String[] historyText = {"开启", "关闭"};

    private String[] homeUI;

    @Override
    protected ViewBinding getBinding() {
        return mBinding = ActivitySettingCustomBinding.inflate(getLayoutInflater());
    }

    public static void start(Activity activity) {
        activity.startActivity(new Intent(activity, SettingCustomActivity.class));
    }

    @Override
    protected void initView(Bundle savedInstanceState) {
        mBinding.homeUI.requestFocus();
        mBinding.parseWebviewText.setText(parseWebview[Setting.getParseWebView()]);
        mBinding.homeHistoryText.setText(Setting.isHomeHistory()? historyText[0] : historyText[1]);
        mBinding.autoStartText.setText(Setting.isAutoStart()? "开启" : "关闭");
        mBinding.homeUIText.setText((homeUI = ResUtil.getStringArray(R.array.select_home_ui))[Setting.getHomeUI()]);
    }

    @Override
    protected void initEvent() {
        mBinding.parseWebview.setOnClickListener(this::setParseWebview);
        mBinding.homeHistory.setOnClickListener(this::setHomeHistory);
        mBinding.autoStart.setOnClickListener(this::setAutoStart);
        mBinding.homeUI.setOnClickListener(this::setHomeUI);
    }

    //首页UI
    private void setHomeUI(View view) {
        int index = Setting.getHomeUI();
        Setting.putHomeUI(index = index == homeUI.length - 1 ? 0 : ++index);
        mBinding.homeUIText.setText(homeUI[index]);
        RefreshEvent.home();
    }

    //设置开机自启动
    private void setAutoStart(View view) {
        Setting.putAutoStart(!Setting.isAutoStart());
        mBinding.autoStartText.setText(Setting.isAutoStart()? "开启" : "关闭");
    }
    //设置首页"最近观看"显示/隐藏
    private void setHomeHistory(View view) {
        Setting.putHomeHistory(!Setting.isHomeHistory());
        mBinding.homeHistoryText.setText(Setting.isHomeHistory()? historyText[0] : historyText[1]);
        RefreshEvent.history();
    }
    //设置嗅探浏览器内核
    private void setParseWebview(View view) {
        if (Build.VERSION.SDK_INT > 34) {
            Notify.show("Android 版本大于 14，跳过 X5 内核初始化");
            return;
        }
        int oldIndex = Setting.getParseWebView();
        int newIndex = oldIndex == parseWebview.length - 1 ? 0 : oldIndex + 1;
        Setting.putParseWebView(newIndex);
        mBinding.parseWebviewText.setText(parseWebview[newIndex]);
        if (newIndex == 1 && QbSdk.getTbsVersion(App.get()) <= 0) {
//            TbsDebugDialog.create(this).show();
            X5WebViewDialog.create(this).show();
        }
    }


    @Override
    public void onX5Success() {
        runOnUiThread(() -> {
            Setting.putParseWebView(1);
            mBinding.parseWebviewText.setText(parseWebview[1]);
            restartApp();
        });
    }

    private void restartApp() {
//        Intent intent = new Intent(this, HomeActivity.class);
//        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_CLEAR_TASK | Intent.FLAG_ACTIVITY_NEW_TASK);
//        startActivity(intent);
//        App.post(() -> System.exit(0), 100);
        Intent intent = this.getBaseContext().getPackageManager().getLaunchIntentForPackage(this.getBaseContext().getPackageName());
        ComponentName componentName = intent.getComponent();
        Intent mainIntent = Intent.makeRestartActivityTask(componentName);
        this.startActivity(mainIntent);
        Runtime.getRuntime().exit(100);
    }

    @Override
    public void onX5Error() {
        runOnUiThread(() -> {
            Setting.putParseWebView(0);
            mBinding.parseWebviewText.setText(parseWebview[0]);
        });
    }

    @Override
    public void onX5Cancel() {
        runOnUiThread(() -> {
            Setting.putParseWebView(0);
            mBinding.parseWebviewText.setText(parseWebview[0]);
        });
    }

}
