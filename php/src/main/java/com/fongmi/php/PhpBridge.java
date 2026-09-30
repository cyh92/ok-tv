package com.fongmi.php;

/**
 * PHP JNI 桥接类。
 * 对应 native-lib.cpp 中注册的 runPhpFile 方法。
 *
 * 每次调用都会启动一次 PHP embed 运行时，执行脚本后关闭。
 */
public class PhpBridge {

    static {
        System.loadLibrary("native-lib");
    }

    /**
     * 全局串行锁。
     *
     * PHP embed 运行时是进程级全局单例（native 层还会改写 php_embed_module 的全局字段
     * php_ini_path_override / ub_write），本身不支持并发。同一个进程里两个线程同时
     * php_embed_init/php_embed_shutdown 会直接内存踩踏导致进程崩溃（SIGSEGV）。
     * 所以所有执行入口都必须串行。
     */
    private static final Object LOCK = new Object();

    /**
     * 执行 PHP 脚本文件。
     *
     * @param path     PHP 脚本文件绝对路径
     * @param iniPath  php.ini 文件绝对路径
     * @param method   HTTP 方法（GET/POST）
     * @param query    查询字符串（如 "id=njzh&debug=0"）
     * @param body     POST 请求体
     * @return PHP 脚本的输出内容（echo/print 的内容）
     */
    public static native String runPhpFile(String path, String iniPath, String method, String query, String body);

    /**
     * 串行版 runPhpFile。**所有调用点都应走这里**，不要直接调 runPhpFile，
     * 否则会破坏「同一时刻只有一个 PHP 运行时」这个前提。
     */
    public static String runPhpFileExclusive(String path, String iniPath, String method, String query, String body) {
        synchronized (LOCK) {
            return runPhpFile(path, iniPath, method, query, body);
        }
    }
}
