package com.netease.mobsec.xs;



public class l {
    public static final java.lang.String a = a(new char[]{248, 213, 'z', 143, 250, '8', 203, 'J'}, "ˣ̊˼̗ͫͿ");
    public static final java.lang.String b = a(new char[]{242, 'U', '&', 11, 237, 'M', 210, '+', '-', 242, 214, 18, '0', 'R', 183}, "̳̆ɞ̻ˏ˄");
    public static final java.lang.String d = a(new char[]{162, '7', 3, 'h', 'u', 186, 162, '\'', '`', 'h', 'M', 194, 162, 'i', 3, 'X', '5', 202, 'B', 246, 3, 24, '-', 18, 'b', 214, 3, '(', 21, 186, 'b', 198, 3, 233, 236, 'B'}, "ˠʵ˳̯͇̂");
    public static final java.util.concurrent.ThreadPoolExecutor c = new java.util.concurrent.ThreadPoolExecutor(0, 3, 60, java.util.concurrent.TimeUnit.SECONDS, new java.util.concurrent.LinkedBlockingQueue(2048), new java.util.concurrent.ThreadPoolExecutor.DiscardPolicy());
    public static final android.net.Uri e = new android.net.Uri.Builder().scheme(a(new char[]{198, 202, 'f', 28, 160, 224, '_'}, "ʒ́͝ʈʥʼ")).authority(a(new char[]{160, 30, 11, 'f', '\b', 'S', 226, '!', '\n', 'o', 225, 205, '!', 223, 26, 'V', 27, 178, 'b', 247, '{', 'n', 233, 170, 194, 223, '(', 'o', 'j', '5', 194}, "ʣʣɩ̡˴̓")).path(a(new char[]{175, 189, 139, 170, 130, 208, 'm', 226, '\n', 179, 224}, "̆˖ɩ̘ʫͭ")).build();


    public static final class a {
        public final java.lang.String a;

        public a(java.lang.String str, boolean z) {
            this.a = str;
        }
    }

    public static java.lang.String a(char[] cArr, java.lang.String str) {
        int length = str.length();
        for (int i = 0; i < cArr.length; i++) {
            char c2 = cArr[i];
            if (c2 > 255) {
                cArr[i] = (char) ((c2 ^ (255 & str.charAt(i % length))) & 65535);
            } else {
                int i2 = ((((((((c2 << 7) | (c2 >> 1)) & 255) + i) & 255) ^ i) & 255) + 148) & 255;
                int i3 = (((((i2 << 7) & 255) ^ ((i2 >> 1) & 255)) & 255) - 108) & 255;
                cArr[i] = (char) ((((((i3 << 5) & 255) | ((i3 >> 3) & 255)) & 255) ^ str.charAt(i % length)) & 255 & 255);
            }
        }
        return new java.lang.String(cArr);
    }

    public static com.netease.mobsec.xs.l.a b(android.content.Context context) throws android.content.pm.PackageManager.NameNotFoundException {
        com.netease.mobsec.xs.h0 c0009a;
        if (a(context)) {
            if (!a(context)) {
                return new com.netease.mobsec.xs.l.a(d, true);
            }
            try {
                android.database.Cursor cursorO = com.ljyh.mei.data.network.sdk.RuntimeAccess.O(context.getContentResolver(), e, null, null, null, null, "com/netease/mobsec/xs/l.class:b:(Landroid/content/Context;)Lcom/netease/mobsec/xs/l$a; line-1");
                if (cursorO != null && cursorO.moveToFirst()) {
                    int columnIndexOrThrow = cursorO.getColumnIndexOrThrow(a(new char[]{'B', 'L', ')', 16}, "ʢ͟ͼ̸̌˫"));
                    int columnIndexOrThrow2 = cursorO.getColumnIndexOrThrow(a(new char[]{'{', '?', 250, 147, 'E', 252, 'h', '`', 'Z', 186, 223}, "˫ʬˮˁ\u0381ɫ"));
                    java.lang.String string = cursorO.getString(columnIndexOrThrow);
                    com.netease.mobsec.xs.l.a aVar = new com.netease.mobsec.xs.l.a(string, d.equalsIgnoreCase(string) || java.lang.Boolean.parseBoolean(cursorO.getString(columnIndexOrThrow2)));
                    com.netease.mobsec.xs.f.a(cursorO);
                    return aVar;
                }
                com.netease.mobsec.xs.f.a(cursorO);
                return new com.netease.mobsec.xs.l.a(d, true);
            } catch (java.lang.Throwable unused) {
                com.netease.mobsec.xs.f.a((java.io.Closeable) null);
                return new com.netease.mobsec.xs.l.a(d, true);
            }
        }
        try {
            context.getPackageManager().getPackageInfo(com.netease.mobsec.xs.f.a(context), 128);
            com.netease.mobsec.xs.n nVar = new com.netease.mobsec.xs.n();
            android.content.Intent intent = new android.content.Intent(a(new char[]{229, 232, 195, 0, 225, 'x', '<', 25, 129, 0, 160, 'h', '$', 182, 160, 'f', 193, 'G', 157, 'i', 203, 205, '|', 179, 129, 236, '\\', '3', 'f', 253, 225, '2', 'd', 28, 30, 'C'}, "ʙͼʤ˵ʯͯ"));
            intent.setPackage(com.netease.mobsec.xs.f.a(context));
            if (context.bindService(intent, nVar, 1)) {
                try {
                    android.os.IBinder iBinderA = nVar.a();
                    int i = com.netease.mobsec.xs.h0.a.a;
                    if (iBinderA == null) {
                        c0009a = null;
                    } else {
                        android.os.IInterface iInterfaceQueryLocalInterface = iBinderA.queryLocalInterface("com.uodis.opendevice.aidl.OpenDeviceIdentifierService");
                        c0009a = (iInterfaceQueryLocalInterface == null || !(iInterfaceQueryLocalInterface instanceof com.netease.mobsec.xs.h0)) ? new com.netease.mobsec.xs.h0.a.C0009a(iBinderA) : (com.netease.mobsec.xs.h0) iInterfaceQueryLocalInterface;
                    }
                    com.netease.mobsec.xs.l.a aVar2 = new com.netease.mobsec.xs.l.a(c0009a.e(), c0009a.d());
                    try {
                        context.unbindService(nVar);
                    } catch (java.lang.Exception unused2) {
                    }
                    return aVar2;
                } catch (java.lang.Exception unused3) {
                    context.unbindService(nVar);
                } catch (java.lang.Throwable th) {
                    try {
                        context.unbindService(nVar);
                    } catch (java.lang.Exception unused4) {
                    }
                    throw th;
                }
            }
        } catch (java.lang.Exception unused5) {
        }
        return null;
    }

    public static boolean a(android.content.Context context) {
        android.net.Uri uri;
        android.content.pm.PackageManager packageManager;
        android.content.pm.ProviderInfo providerInfoResolveContentProvider;
        android.content.pm.ApplicationInfo applicationInfo;
        android.os.Bundle bundle = null;
        java.lang.Object obj;
        if (context == null || e == null) {
            return false;
        }
        try {
            bundle = context.getPackageManager().getApplicationInfo(com.netease.mobsec.xs.f.a(context), 128).metaData;
        } catch (java.lang.Throwable unused) {
        }
        int i = (bundle == null || (obj = bundle.get(a(new char[]{139, 218, 179, 183, 'Y', 139, 'W', 3, 'P', 'v', 164, 'T', 'Y', '-', 'P'}, "ɸ˒̺ˢɾ˜"))) == null) ? -1 : java.lang.Integer.parseInt(obj.toString());
        if (i < 30462100 || (uri = e) == null) {
            return false;
        }
        try {
            java.lang.String authority = uri.getAuthority();
            if (authority != null && (providerInfoResolveContentProvider = (packageManager = context.getPackageManager()).resolveContentProvider(authority, 0)) != null && (applicationInfo = providerInfoResolveContentProvider.applicationInfo) != null) {
                java.lang.String str = applicationInfo.packageName;
                if (android.text.TextUtils.isEmpty(str)) {
                    return false;
                }
                if (packageManager.checkSignatures(context.getPackageName(), str) != 0) {
                    if ((applicationInfo.flags & 1) != 1) {
                        return false;
                    }
                }
                return true;
            }
            return false;
        } catch (java.lang.Exception unused2) {
            return false;
        }
    }
}
