package com.fongmi.android.tv.player.ku9;

import android.util.Base64;

import org.json.JSONTokener;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;
import java.util.regex.Pattern;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * 酷9（Ku9）桥的通用工具实现，供 Ku9Bridge 的 @JavascriptInterface 方法转发。
 * 约定（与酷9播放器脚本引擎对齐）：
 * - 加解密的输入/输出格式数字位：0 = base64，1 = hex；
 * - 时间戳单位为毫秒；
 * - AES/DES 密钥按算法位数取 UTF-8 字节后截断或补零（不足补 0x00）；
 * - 失败一律返回空串 / "0" / false，不向 JS 抛异常。
 */
public final class Ku9Util {

    private static final char[] HEX = "0123456789abcdef".toCharArray();
    private static final Pattern BASE64 = Pattern.compile("^[A-Za-z0-9+/]*={0,2}$");
    private static final long DAY_MILLIS = 24 * 60 * 60 * 1000L;

    private Ku9Util() {
    }

    // ---------- 哈希 ----------

    public static String digest(String algorithm, String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance(algorithm);
            return toHex(digest.digest(utf8(value)));
        } catch (Exception e) {
            return "";
        }
    }

    public static String sha1(String value) {
        return digest("SHA-1", value);
    }

    public static String sha256(String value) {
        return digest("SHA-256", value);
    }

    public static String sha512(String value) {
        return digest("SHA-512", value);
    }

    // ---------- Base64 ----------

    public static String encodeBase64(String value) {
        try {
            return Base64.encodeToString(utf8(value), Base64.NO_WRAP);
        } catch (Exception e) {
            return "";
        }
    }

    public static String decodeBase64(String value) {
        try {
            return new String(base64(value), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "";
        }
    }

    public static boolean isBase64(String value) {
        if (value == null) return false;
        String trim = value.trim();
        if (trim.isEmpty() || trim.length() % 4 != 0) return false;
        return BASE64.matcher(trim).matches();
    }

    // ---------- 类型判断 ----------

    public static boolean isJsonObject(String value) {
        try {
            return new JSONTokener(str(value)).nextValue() instanceof org.json.JSONObject;
        } catch (Exception e) {
            return false;
        }
    }

    public static boolean isJsonArray(String value) {
        try {
            return new JSONTokener(str(value)).nextValue() instanceof org.json.JSONArray;
        } catch (Exception e) {
            return false;
        }
    }

    // ---------- OpenSSL（AES / DES 系） ----------

    public static String opensslEncrypt(String data, String type, String key, double outputType, String iv) {
        return openssl(data, type, key, outputType, iv, true);
    }

    public static String opensslDecrypt(String data, String type, String key, double inputType, String iv) {
        return openssl(data, type, key, inputType, iv, false);
    }

    private static String openssl(String data, String type, String key, double format, String iv, boolean encrypt) {
        try {
            CipherSpec spec = parseSpec(type);
            byte[] keyBytes = padTruncate(key, spec.keyLength);
            Cipher cipher = Cipher.getInstance(spec.algorithm + "/" + spec.mode + "/PKCS5Padding");
            if (spec.mode.equals("CBC")) {
                byte[] ivBytes = padTruncate(iv, spec.blockSize);
                cipher.init(encrypt ? Cipher.ENCRYPT_MODE : Cipher.DECRYPT_MODE, new SecretKeySpec(keyBytes, spec.algorithm), new IvParameterSpec(ivBytes));
            } else {
                cipher.init(encrypt ? Cipher.ENCRYPT_MODE : Cipher.DECRYPT_MODE, new SecretKeySpec(keyBytes, spec.algorithm));
            }
            byte[] input = encrypt ? utf8(data) : decodeFormat(data, (int) format);
            byte[] output = cipher.doFinal(input);
            return encrypt ? encodeFormat(output, (int) format) : new String(output, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "";
        }
    }

    /** 解析 "AES-128-ECB" / "AES-256-CBC" / "DES-EDE3-CBC" / "DES-ECB" 等算法描述。 */
    private static CipherSpec parseSpec(String type) throws Exception {
        String spec = str(type).trim().toUpperCase(Locale.ROOT).replaceAll("[\\s_]", "");
        if (spec.isEmpty()) throw new IllegalArgumentException("empty type");
        String[] parts = spec.split("-");
        String algorithm;
        String mode = "ECB";
        int keyLength;
        if (parts[0].equals("AES")) {
            algorithm = "AES";
            int bits = parts.length > 1 ? Integer.parseInt(parts[1]) : 128;
            if (parts.length > 2) mode = normalizeMode(parts[2]);
            keyLength = bits / 8;
        } else if (parts[0].equals("DES")) {
            algorithm = "DES";
            keyLength = 8;
            if (parts.length > 1 && parts[1].equals("EDE3")) {
                algorithm = "DESede";
                keyLength = 24;
            }
            if (parts.length > 2) mode = normalizeMode(parts[parts.length - 1]);
        } else {
            throw new IllegalArgumentException("unsupported algorithm: " + parts[0]);
        }
        return new CipherSpec(algorithm, mode, keyLength, algorithm.equals("AES") ? 16 : 8);
    }

    private static String normalizeMode(String value) {
        return value.equals("CBC") ? "CBC" : "ECB";
    }

    private static final class CipherSpec {
        final String algorithm;
        final String mode;
        final int keyLength;
        final int blockSize;

        CipherSpec(String algorithm, String mode, int keyLength, int blockSize) {
            this.algorithm = algorithm;
            this.mode = mode;
            this.keyLength = keyLength;
            this.blockSize = blockSize;
        }
    }

    // ---------- RC4 ----------

    public static String rc4Encrypt(String data, String key, double inputFormat, double outputFormat, String charset) {
        try {
            Charset cs = charset(charset);
            byte[] plain = decodeText(data, (int) inputFormat, cs);
            byte[] result = rc4(plain, str(key).getBytes(cs));
            return encodeFormat(result, (int) outputFormat);
        } catch (Exception e) {
            return "";
        }
    }

    public static String rc4Decrypt(String data, String key, double inputFormat, double outputFormat, String charset) {
        try {
            Charset cs = charset(charset);
            byte[] cipherText = decodeFormat(data, (int) inputFormat);
            byte[] result = rc4(cipherText, str(key).getBytes(cs));
            return new String(result, cs);
        } catch (Exception e) {
            return "";
        }
    }

    /** 加密输入：0 = 按 charset 的明文文本，1 = hex，2 = base64。 */
    private static byte[] decodeText(String data, int inputFormat, Charset cs) {
        if (inputFormat == 1) return hex(data);
        if (inputFormat == 2) return base64(data);
        return str(data).getBytes(cs);
    }

    private static byte[] rc4(byte[] data, byte[] key) {
        if (key.length == 0) return data;
        int[] s = new int[256];
        for (int i = 0; i < 256; i++) s[i] = i;
        int j = 0;
        for (int i = 0; i < 256; i++) {
            j = (j + s[i] + (key[i % key.length] & 0xff)) & 0xff;
            int t = s[i];
            s[i] = s[j];
            s[j] = t;
        }
        byte[] out = new byte[data.length];
        int i = 0;
        j = 0;
        for (int k = 0; k < data.length; k++) {
            i = (i + 1) & 0xff;
            j = (j + s[i]) & 0xff;
            int t = s[i];
            s[i] = s[j];
            s[j] = t;
            out[k] = (byte) (data[k] ^ s[(s[i] + s[j]) & 0xff]);
        }
        return out;
    }

    // ---------- 时间 ----------

    public static String toTimestamp(String dateStr, String inputFormat, String timezone) {
        try {
            SimpleDateFormat sdf = new SimpleDateFormat(formatOf(inputFormat), Locale.ROOT);
            sdf.setTimeZone(zone(timezone));
            return String.valueOf(sdf.parse(str(dateStr)).getTime());
        } catch (Exception e) {
            return "0";
        }
    }

    public static String toDate(double timestamp, String outputFormat, String timezone) {
        try {
            SimpleDateFormat sdf = new SimpleDateFormat(formatOf(outputFormat), Locale.ROOT);
            sdf.setTimeZone(zone(timezone));
            return sdf.format(new Date((long) timestamp));
        } catch (Exception e) {
            return "";
        }
    }

    public static String formatDateTime(String dateStr, String inputFormat, String outputFormat, double daysOffset, String inputTimezone, String outputTimezone) {
        try {
            SimpleDateFormat in = new SimpleDateFormat(formatOf(inputFormat), Locale.ROOT);
            in.setTimeZone(zone(inputTimezone));
            long millis = in.parse(str(dateStr)).getTime() + (long) daysOffset * DAY_MILLIS;
            SimpleDateFormat out = new SimpleDateFormat(formatOf(outputFormat), Locale.ROOT);
            out.setTimeZone(zone(outputTimezone));
            return out.format(new Date(millis));
        } catch (Exception e) {
            return "";
        }
    }

    private static String formatOf(String format) {
        String value = str(format).trim();
        if (value.isEmpty()) return "yyyy-MM-dd HH:mm:ss";
        if (value.equalsIgnoreCase("ISO")) return "yyyy-MM-dd'T'HH:mm:ss";
        return value;
    }

    private static TimeZone zone(String timezone) {
        String value = str(timezone).trim();
        if (value.isEmpty()) return TimeZone.getDefault();
        TimeZone zone = TimeZone.getTimeZone(value);
        if (!value.equals("GMT") && !value.equals("UTC") && zone.getRawOffset() == 0) return TimeZone.getDefault();
        return zone;
    }

    // ---------- 内部 ----------

    private static Charset charset(String name) {
        String value = str(name).trim();
        if (value.isEmpty()) return StandardCharsets.UTF_8;
        try {
            return Charset.forName(value);
        } catch (Exception e) {
            return StandardCharsets.UTF_8;
        }
    }

    private static String str(String value) {
        return value == null ? "" : value;
    }

    private static byte[] utf8(String value) {
        return str(value).getBytes(StandardCharsets.UTF_8);
    }

    /** UTF-8 字节截断或补零到指定长度（不足补 0x00）。 */
    private static byte[] padTruncate(String value, int length) {
        return Arrays.copyOf(utf8(value), length);
    }

    /** 加解密数据格式：0 = base64，1 = hex。 */
    private static byte[] decodeFormat(String value, int format) {
        return format == 1 ? hex(value) : base64(value);
    }

    private static String encodeFormat(byte[] value, int format) {
        return format == 1 ? toHex(value) : Base64.encodeToString(value, Base64.NO_WRAP);
    }

    private static byte[] base64(String value) {
        int flags = Base64.NO_WRAP;
        String text = str(value);
        if (text.indexOf('-') >= 0 || text.indexOf('_') >= 0) flags |= Base64.URL_SAFE;
        return Base64.decode(text, flags);
    }

    private static byte[] hex(String value) {
        String clean = str(value).trim();
        if (clean.length() % 2 != 0) clean = "0" + clean;
        byte[] out = new byte[clean.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(clean.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }

    private static String toHex(byte[] bytes) {
        char[] result = new char[bytes.length * 2];
        for (int i = 0; i < bytes.length; i++) {
            int value = bytes[i] & 0xff;
            result[i * 2] = HEX[value >>> 4];
            result[i * 2 + 1] = HEX[value & 0x0f];
        }
        return new String(result);
    }
}
