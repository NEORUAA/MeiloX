package com.netease.mobsec.xs;



public class j {
    public static final java.lang.String a = a(new char[]{229, '6', 170, 't'}, "͚̇˗̛͉̘");
    public static final java.lang.String b = a(new char[]{15, '.', 142, '\b', 'N', 246, 183, 175, 'v', 2, '6', 164, 16, 14, '\t', 217}, "ʾʛɷ˭̃˝");


    public static final class a {
        public final java.lang.String a;

        public a(java.lang.String str, boolean z) {
            this.a = str;
        }
    }

    public static java.lang.String a(char[] cArr, java.lang.String str) {
        int length = str.length();
        for (int i = 0; i < cArr.length; i++) {
            char c = cArr[i];
            if (c > 255) {
                cArr[i] = (char) ((c ^ (255 & str.charAt(i % length))) & 65535);
            } else {
                int i2 = ((((((((c << 7) | (c >> 1)) & 255) + i) & 255) ^ i) & 255) + 135) & 255;
                int i3 = (((((i2 << 7) & 255) ^ ((i2 >> 1) & 255)) & 255) - 121) & 255;
                cArr[i] = (char) ((((((i3 << 5) & 255) | ((i3 >> 3) & 255)) & 255) ^ str.charAt(i % length)) & 255 & 255);
            }
        }
        return new java.lang.String(cArr);
    }

    public static boolean a(android.content.Context context) throws android.content.pm.PackageManager.NameNotFoundException {
        android.content.Intent intent = new android.content.Intent("com.google.android.gms.ads.identifier.service.START");
        intent.setPackage("com.google.android.gms");
        return !context.getPackageManager().queryIntentServices(intent, 0).isEmpty();
    }
}
