package com.fongmi.php;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;

/**
 * PHP 运行环境管理。
 * 负责从 assets 复制 php.ini、准备临时目录、执行 PHP 脚本。
 */
public class PhpEnv {

    private static final String TAG = "PhpEnv";
    private static final String PREFS = "php_env";
    private static PhpEnv instance;

    private final Context context;
    private File phpDir;
    private File tmpDir;
    private File iniFile;
    private boolean ready;

    private PhpEnv(Context context) {
        this.context = context.getApplicationContext();
    }

    public static synchronized PhpEnv get(Context context) {
        if (instance == null) instance = new PhpEnv(context);
        return instance;
    }

    /**
     * 初始化 PHP 环境（从 assets 复制 php.ini，准备临时目录）。
     * 在后台线程调用。
     */
    public synchronized void init() {
        if (ready) return;
        try {
            phpDir = new File(context.getFilesDir(), "php");
            tmpDir = new File(phpDir, "tmp");
            tmpDir.mkdirs();

            iniFile = new File(phpDir, "php.ini");

            // 版本变化时重新复制
            SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            int savedVersion = prefs.getInt("version", -1);
            int currentVersion = context.getPackageManager()
                    .getPackageInfo(context.getPackageName(), 0).versionCode;

            if (savedVersion != currentVersion || !iniFile.exists()) {
                copyAsset("php/php.ini", iniFile);
                // 修改临时目录路径
                String content = readFile(iniFile);
                content = content.replaceAll("upload_tmp_dir\\s*=.*", "upload_tmp_dir = \"" + tmpDir.getAbsolutePath() + "\"");
                content = content.replaceAll("session.save_path\\s*=.*", "session.save_path = \"" + tmpDir.getAbsolutePath() + "\"");
                content = content.replaceAll("sys_temp_dir\\s*=.*", "sys_temp_dir = \"" + tmpDir.getAbsolutePath() + "\"");
                writeFile(iniFile, content);
                prefs.edit().putInt("version", currentVersion).apply();
            }

            ready = true;
            Log.i(TAG, "PHP 环境初始化完成: " + phpDir.getAbsolutePath());
        } catch (Exception e) {
            Log.e(TAG, "PHP 环境初始化失败", e);
        }
    }

    /**
     * 获取 php.ini 路径（确保环境已初始化）。
     */
    public static String getIniPath() {
        if (instance == null || !instance.ready) {
            throw new IllegalStateException("PHP 环境未初始化");
        }
        return instance.iniFile.getAbsolutePath();
    }

    /**
     * 获取脚本存放目录。
     */
    public static File getScriptDir() {
        if (instance == null || !instance.ready) {
            throw new IllegalStateException("PHP 环境未初始化");
        }
        return instance.phpDir;
    }

    /**
     * 执行 PHP 脚本。
     *
     * @param scriptContent PHP 脚本内容
     * @param queryString   查询字符串（如 "id=njzh"）
     * @return PHP 输出内容
     */
    public String execute(String scriptContent, String queryString) throws Exception {
        if (!ready) init();
        if (!ready) throw new Exception("PHP 环境未初始化");

        // 将脚本写入临时文件
        File scriptFile = new File(phpDir, "remote_script.php");
        writeFile(scriptFile, scriptContent);

        // 执行
        return PhpBridge.runPhpFile(
                scriptFile.getAbsolutePath(),
                iniFile.getAbsolutePath(),
                "GET",
                queryString == null ? "" : queryString,
                ""
        );
    }

    private void copyAsset(String assetPath, File dest) throws Exception {
        try (InputStream in = context.getAssets().open(assetPath);
             FileOutputStream out = new FileOutputStream(dest)) {
            byte[] buffer = new byte[8192];
            int len;
            while ((len = in.read(buffer)) > 0) out.write(buffer, 0, len);
        }
    }

    private String readFile(File file) throws Exception {
        java.io.FileInputStream fis = new java.io.FileInputStream(file);
        byte[] data = new byte[(int) file.length()];
        fis.read(data);
        fis.close();
        return new String(data, "UTF-8");
    }

    private void writeFile(File file, String content) throws Exception {
        FileOutputStream out = new FileOutputStream(file);
        out.write(content.getBytes("UTF-8"));
        out.close();
    }
}
