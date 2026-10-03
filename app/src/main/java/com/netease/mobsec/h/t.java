package com.netease.mobsec.h;



public class t {
    public static int a = 200;
    public static int b = 1001;
    public static int c = 1002;
    public static int d = 1003;
    public static int e = 1004;
    public static int f = 1005;
    public static java.lang.String g = "ok";
    public static java.lang.String h = "pruduct number error";
    public static java.lang.String i = "init error";
    public static java.lang.String j = "init context is null,error";
    public static java.lang.String k = "init again error";
    static final java.lang.String l = java.lang.System.getProperty("line.separator");

    public static java.lang.String a() {
        try {
            byte[] bArr = new byte[24];
            byte[] bArrA = a(a(java.lang.System.currentTimeMillis()), a(java.util.UUID.randomUUID().getLeastSignificantBits()));
            byte[] bArr2 = new byte[bArrA.length + "7pNRBg3m2HgFFIuxSjcxnlGvyRGAnrBY".getBytes().length];
            java.lang.System.arraycopy(bArrA, 0, bArr2, 0, bArrA.length);
            java.lang.System.arraycopy("7pNRBg3m2HgFFIuxSjcxnlGvyRGAnrBY".getBytes(), 0, bArr2, bArrA.length, "7pNRBg3m2HgFFIuxSjcxnlGvyRGAnrBY".getBytes().length);
            java.lang.System.arraycopy(b(bArr2), 0, bArr, 0, 8);
            java.lang.System.arraycopy(bArrA, 0, bArr, 8, 16);
            return a(bArr);
        } catch (java.lang.Exception unused) {
            return "";
        }
    }

    public static java.lang.String b() {
        return java.util.UUID.randomUUID().toString().replace("-", "");
    }

    public static boolean c() {
        return android.os.Looper.myLooper() == android.os.Looper.getMainLooper();
    }

    public static java.lang.String a(byte[] bArr) {
        return android.util.Base64.encodeToString(bArr, 0).replaceAll("\n", "");
    }

    public static byte[] b(byte[] bArr) {
        byte[] bArr2 = new byte[0];
        try {
            java.security.MessageDigest messageDigest = java.security.MessageDigest.getInstance("MD5");
            messageDigest.reset();
            messageDigest.update(bArr);
            return messageDigest.digest();
        } catch (java.lang.Exception unused) {
            return bArr2;
        }
    }

    public static byte[] a(long j2) {
        byte[] bArr = new byte[8];
        for (int i2 = 7; i2 >= 0; i2--) {
            bArr[i2] = (byte) (255 & j2);
            j2 >>= 8;
        }
        return bArr;
    }

    public static byte[] a(byte[] bArr, byte[] bArr2) {
        int length = bArr.length * 2;
        byte[] bArr3 = new byte[length];
        for (int i2 = 0; i2 < length; i2++) {
            if (i2 % 2 == 0) {
                int i3 = i2 / 2;
                byte b2 = bArr2[i3];
                byte b3 = bArr[i3];
                bArr3[i2] = (byte) (((b3 & 128) >>> 0) | ((b2 & 128) >>> 1) | ((b2 & 16) >>> 4) | ((b2 & 32) >>> 3) | ((b2 & 64) >>> 2) | ((b3 & 16) >>> 3) | ((b3 & 32) >>> 2) | ((b3 & 64) >>> 1) | bArr3[i2]);
            } else {
                int i4 = i2 / 2;
                byte b4 = bArr2[i4];
                byte b5 = bArr[i4];
                bArr3[i2] = (byte) (((b5 & 8) << 4) | ((b4 & 8) << 3) | ((b4 & 1) << 0) | ((b4 & 2) << 1) | ((b4 & 4) << 2) | ((b5 & 1) << 1) | ((b5 & 2) << 2) | ((b5 & 4) << 3) | bArr3[i2]);
            }
        }
        return bArr3;
    }
}
