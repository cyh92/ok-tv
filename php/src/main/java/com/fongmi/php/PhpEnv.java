package com.fongmi.php;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * PHP 运行环境管理。
 * 负责从 assets 复制 php.ini、准备临时目录、执行 PHP 脚本。
 *
 * 所有目录都放在应用内部存储 filesDir/php/ 下：不依赖任何存储权限，
 * 避免直播路径在未授权外部存储时静默失败。
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
    private String initError;

    private PhpEnv(Context context) {
        this.context = context.getApplicationContext();
    }

    public static synchronized PhpEnv get(Context context) {
        if (instance == null) instance = new PhpEnv(context);
        return instance;
    }

    /**
     * 初始化 PHP 环境（从 assets 复制 php.ini，准备目录）。
     * 在后台线程调用。
     *
     * web 根目录（脚本目录）与 tmp 目录都位于内部存储 filesDir/php/，无需存储权限。
     */
    public synchronized void init() {
        if (ready) return;
        try {
            // web 根目录：内部存储 files/php/
            phpDir = new File(context.getFilesDir(), "php");
            tmpDir = new File(phpDir, "tmp");
            iniFile = new File(phpDir, "php.ini");

            // 目录可用性校验：创建失败或不可写时直接抛异常，不把 ready 置位
            ensureWritable(phpDir);
            ensureWritable(tmpDir);

            // 版本变化时重新复制 php.ini
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
                // error_log 也要落成绝对路径，否则 PHP 会 fallback 到 stderr（Android 上被丢弃）
                content = content.replaceAll("error_log\\s*=.*", "error_log = \"" + new File(tmpDir, "php_error.log").getAbsolutePath() + "\"");
                writeFile(iniFile, content);
                prefs.edit().putInt("version", currentVersion).apply();
            }

            ready = true;
            initError = null;
            Log.i(TAG, "PHP 环境初始化完成, web根目录: " + phpDir.getAbsolutePath());
        } catch (Exception e) {
            ready = false;
            initError = e.getClass().getSimpleName() + ": " + e.getMessage();
            Log.e(TAG, "PHP 环境初始化失败", e);
        }
    }

    /**
     * 确认目录存在且真的可写。
     * mkdirs() 失败时不会抛异常（只返回 false），必须显式校验，
     * 否则后续 new FileOutputStream 才报错，定位困难。
     */
    private static void ensureWritable(File dir) throws IOException {
        if (!dir.isDirectory()) {
            dir.mkdirs();
            if (!dir.isDirectory()) throw new IOException("无法创建目录: " + dir.getAbsolutePath());
        }
        File probe = new File(dir, ".write_check");
        try {
            try (FileOutputStream out = new FileOutputStream(probe)) {
                out.write(0);
            }
        } finally {
            if (probe.exists() && !probe.delete()) probe.deleteOnExit();
        }
    }

    /**
     * 环境是否已就绪。
     */
    public static boolean isReady() {
        return instance != null && instance.ready;
    }

    /**
     * 初始化失败原因，未失败（或尚未初始化）时为 null。
     */
    public static String getInitError() {
        return instance == null ? null : instance.initError;
    }

    private static String notReadyMessage() {
        String reason = getInitError();
        return reason == null ? "PHP 环境未初始化" : "PHP 环境未初始化: " + reason;
    }

    /**
     * 获取 php.ini 路径（确保环境已初始化）。
     */
    public static String getIniPath() {
        if (instance == null || !instance.ready) throw new IllegalStateException(notReadyMessage());
        return instance.iniFile.getAbsolutePath();
    }

    /**
     * 获取脚本存放目录。
     */
    public static File getScriptDir() {
        if (instance == null || !instance.ready) throw new IllegalStateException(notReadyMessage());
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
        if (!ready) throw new IllegalStateException(notReadyMessage());

        // 将脚本写入临时文件
        File scriptFile = new File(phpDir, "remote_script.php");
        writeFile(scriptFile, scriptContent);

        // 执行（串行，避免并发踩坏 PHP 运行时）
        return PhpBridge.runPhpFileExclusive(
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
