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
}
