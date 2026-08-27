package com.netease.cloudmusic.crypto.caesarson;

public final class CaesarsonCryptor {
    static {
        System.loadLibrary("caesarson");
    }

    private CaesarsonCryptor() {}

    public static String encrypt(String value) throws CaesarsonCryptoException {
        ErrorObject error = new ErrorObject();
        String result = native_encrypt(value, error);
        if (error.errorCode == 0) {
            return result;
        }
        throw new CaesarsonCryptoException(error.message);
    }

    public static void initWithConfig(String config) throws CaesarsonCryptoException {
        if (native_init(config) != 0) {
            throw new CaesarsonCryptoException();
        }
    }

    private static native String native_encrypt(String value, ErrorObject error);

    private static native int native_init(String config);
}
