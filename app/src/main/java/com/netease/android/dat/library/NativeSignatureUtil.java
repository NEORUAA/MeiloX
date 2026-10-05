package com.netease.android.dat.library;

/** Callback invoked by libnedat while validating the DAT app signature. */
public final class NativeSignatureUtil {
    private static final char[] HEX = "0123456789ABCDEF".toCharArray();
    private NativeSignatureUtil() {}

    public static String getSignature(Object signature) {
        return signature instanceof byte[] bytes ? bytesToHex(bytes) : null;
    }
    public static String bytesToHex(byte[] bytes) {
        char[] result = new char[bytes.length * 2];
        for (int i = 0; i < bytes.length; i++) {
            result[i] = HEX[(bytes[i] >>> 4) & 15];
            result[i + bytes.length] = HEX[bytes[i] & 15];
        }
        return new String(result);
    }
    public static byte[] hexStrToBytes(String value) {
        byte[] bytes = new byte[value.length() / 2];
        for (int i = 0; i < bytes.length; i++) bytes[i] = (byte)Integer.parseInt(value.substring(i * 2, i * 2 + 2), 16);
        return bytes;
    }
}
