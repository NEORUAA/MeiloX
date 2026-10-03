package com.netease.mobsec.h;



public class q {
    static final char[] a = {'0', '1', '2', '3', '4', '5', '6', '7', '8', '9', 'a', 'b', 'c', 'd', 'e', 'f'};

    static int a(char c) {
        if (c >= '0' && c <= '9') {
            return c - '0';
        }
        char c2 = 'A';
        if (c < 'A' || c > 'F') {
            c2 = 'a';
            if (c < 'a' || c > 'f') {
                return 0;
            }
        }
        return (c - c2) + 10;
    }

    public static java.lang.String b(java.lang.String str) throws java.lang.Exception {
        if (str == null) {
            return "";
        }
        byte[] bArr = {31, 125, -12, 60, 32, 48};
        try {
            byte[] bytes = str.getBytes("UTF-8");
            byte[] bArr2 = new byte[bytes.length];
            int i = 0;
            int i2 = 0;
            while (i < bytes.length) {
                int i3 = i2 + 1;
                byte b = (byte) (bArr[i2 % 6] ^ bytes[i]);
                bArr2[i] = b;
                bArr2[i] = (byte) (0 - b);
                i++;
                i2 = i3;
            }
            return a(bArr2);
        } catch (java.lang.Exception e) {
            throw new java.lang.Exception("Configuration encrypt error", e);
        }
    }

    static byte[] c(java.lang.String str) {
        int length = str.length();
        byte[] bArr = new byte[(length + 1) / 2];
        int i = 1;
        int i2 = 0;
        if (length % 2 == 1) {
            bArr[0] = (byte) a(str.charAt(0));
            i2 = 1;
        } else {
            i = 0;
        }
        while (i < length) {
            int i3 = i + 1;
            bArr[i2] = (byte) ((a(str.charAt(i)) << 4) | a(str.charAt(i3)));
            i2++;
            i = i3 + 1;
        }
        return bArr;
    }

    public static java.lang.String a(java.lang.String str) throws java.lang.Exception {
        if (str == null) {
            return "";
        }
        byte[] bArr = {31, 125, -12, 60, 32, 48};
        try {
            byte[] bArrC = c(str);
            byte[] bArr2 = new byte[bArrC.length];
            int i = 0;
            int i2 = 0;
            while (i < bArrC.length) {
                byte b = (byte) (0 - bArrC[i]);
                bArr2[i] = b;
                bArr2[i] = (byte) (bArr[i2 % 6] ^ b);
                i++;
                i2++;
            }
            return new java.lang.String(bArr2, "UTF-8");
        } catch (java.lang.Exception e) {
            throw new java.lang.Exception("Configuration decrypt error", e);
        }
    }

    public static java.lang.String a(byte[] bArr) {
        char[] cArr = new char[bArr.length * 2];
        for (int i = 0; i < bArr.length; i++) {
            int i2 = i * 2;
            char[] cArr2 = a;
            byte b = bArr[i];
            cArr[i2] = cArr2[(b >>> 4) & 15];
            cArr[i2 + 1] = cArr2[b & 15];
        }
        return new java.lang.String(cArr);
    }
}
