package com.netease.mobsec.xs;



public class e0 {
    public static final java.util.concurrent.CountDownLatch a = new java.util.concurrent.CountDownLatch(2);
    public static volatile java.lang.String b = null;
    public static volatile java.lang.String c = null;
    public static final boolean d;
    public static volatile boolean e;

    static {
        d = android.os.Build.VERSION.SDK_INT >= 28;
        e = false;
    }

    public static java.lang.String a() {
        java.lang.String str = b;
        return !((android.text.TextUtils.isEmpty(str) || str.length() != 36) ? false : str.matches(com.netease.mobsec.xs.f.a(new char[]{210, 27, 178, '?', 'R', 3, 220, 'e', 200, '?', 233, '}', 30, 134, ';', '?', 134, 176, 156, 214, 196, '?', 157, 246, 164, 129, '?', 153, 'S', 'y', 156, 226, 178, 190, 211, 250, 'd', 236, '8', 190, 'i', 133, '&', 23, 186, 190, '~', 'P', '$', 'V', '<', 190, '%', 246, 28, 129, 198, 25, 'S', 137, '$', 234, 'R', 190, 'R', 3, 221, 'u', 185, '?', 216, '|', '.', 182, 211, 25, 144}, "̫ʌ̈́˕͈͖"))) ? "" : str;
    }

    public static java.lang.String b() {
        java.lang.String str = c;
        java.lang.String str2 = c;
        return !((android.text.TextUtils.isEmpty(str2) || str2.length() < 16) ? false : str2.startsWith(com.netease.mobsec.xs.f.a(new char[]{204, 136, 223, '\f', '*', 192, 180, 'x'}, "ɵ̮̓ʯʀ˔")) ^ true) ? "" : str;
    }

    public static void c() throws java.lang.InterruptedException {
        if (d && e) {
            try {
                java.util.concurrent.CountDownLatch countDownLatch = a;
                if (countDownLatch != null) {
                    countDownLatch.await(3L, java.util.concurrent.TimeUnit.SECONDS);
                }
            } catch (java.lang.InterruptedException unused) {
            }
        }
    }
}
