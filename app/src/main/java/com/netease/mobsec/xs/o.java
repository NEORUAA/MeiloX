package com.netease.mobsec.xs;



public class o {
    public static final java.lang.String a = a(new char[]{'U', 174, 165, 'x'}, "ɲͤʢ̛ͫʨ");

    public static java.lang.String a(com.netease.mobsec.xs.e eVar, android.content.Context context) {
        java.lang.String strA = null;
        if (eVar == null) {
            return null;
        }
        try {
            java.lang.String packageName = context.getPackageName();
            strA = eVar.a(packageName, a(context, packageName), a(new char[]{221, '\r', 'o', 'I'}, "̺ʫ̛ʄ͕͗"));
        } catch (java.lang.Exception unused) {
            strA = null;
        }
        if (android.text.TextUtils.isEmpty(strA)) {
            return null;
        }
        return strA;
    }

    public static java.lang.String a(char[] cArr, java.lang.String str) {
        int length = str.length();
        for (int i = 0; i < cArr.length; i++) {
            char c = cArr[i];
            if (c > 255) {
                cArr[i] = (char) ((c ^ (255 & str.charAt(i % length))) & 65535);
            } else {
                int i2 = ((((((((c << 7) | (c >> 1)) & 255) + i) & 255) ^ i) & 255) + 35) & 255;
                int i3 = (((((i2 << 7) & 255) ^ ((i2 >> 1) & 255)) & 255) - 221) & 255;
                cArr[i] = (char) ((((((i3 << 5) & 255) | ((i3 >> 3) & 255)) & 255) ^ str.charAt(i % length)) & 255 & 255);
            }
        }
        return new java.lang.String(cArr);
    }



    public static synchronized java.lang.String a(android.content.Context context) {
        boolean z;
        java.lang.String strA = null;
        android.content.Intent intent = null;
        com.netease.mobsec.xs.n nVar = null;
        com.netease.mobsec.xs.e c0007a;
        android.content.pm.PackageInfo packageInfo = null;
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            return null;
        }
        try {
            packageInfo = context.getPackageManager().getPackageInfo(a(new char[]{'}', 175, '+', 194, 'Z', 199, '$', 15, 173, 's', '#', 246, '+', 212, 186, 'J', 171}, "́͛ʂ̣ʁ̀"), 0);
        } catch (java.lang.Exception unused) {
        }
        z = packageInfo != null && packageInfo.getLongVersionCode() >= 1;
        if (!z) {
            return null;
        }
        try {
            intent = new android.content.Intent();
            intent.setComponent(new android.content.ComponentName(a(new char[]{136, 'N', 'b', 'y', 175, '\f', '9', 'o', 227, 144, 137, 180, 'f', 'u', 130, 179, 255}, "ʹ͆˄ɬˬ̒"), a(new char[]{'0', 18, 146, 27, 196, 246, 'q', 168, 243, ';', 132, 199, '~', 178, 18, 2, 'C', 159, '3', 'j', 149, 11, '=', 'u', 176, '3', 177, 'S', 2, 156, 23, 'j', 149}, "ͻ̨͆ˡ˔ʋ")));
            intent.setAction(a(new char[]{141, 218, '#', 31, 254, 27, 226, 194, 197, 158, 206, 209, 251, ' ', '#', 30, 238, 242, 202, 1, 5, '>', '6', '0', 162, '%', 'v', 'a', 146, '5', '~', '~', 191, 'a', 'S', 174, 'Y', 30, 193, 161}, "ʃˮ˛ˆ͠;"));
            nVar = new com.netease.mobsec.xs.n();
        } catch (java.lang.Exception unused2) {
        }
        if (context.bindService(intent, nVar, 1)) {
            try {
                android.os.IBinder iBinderA = nVar.a();
                int i = com.netease.mobsec.xs.e.a.a;
                if (iBinderA == null) {
                    c0007a = null;
                } else {
                    android.os.IInterface iInterfaceQueryLocalInterface = iBinderA.queryLocalInterface("com.heytap.openid.IOpenID");
                    c0007a = (iInterfaceQueryLocalInterface == null || !(iInterfaceQueryLocalInterface instanceof com.netease.mobsec.xs.e)) ? new com.netease.mobsec.xs.e.a.C0007a(iBinderA) : (com.netease.mobsec.xs.e) iInterfaceQueryLocalInterface;
                }
                strA = a(c0007a, context);
                try {
                    context.unbindService(nVar);
                } catch (java.lang.Exception unused3) {
                }
            } catch (java.lang.Exception unused4) {
                context.unbindService(nVar);
            } catch (java.lang.Throwable th) {
                try {
                    context.unbindService(nVar);
                } catch (java.lang.Exception unused5) {
                }
                throw th;
            }
        } else {
            strA = null;
        }
        return strA;
    }

    public static java.lang.String a(android.content.Context context, java.lang.String str) {
        android.content.pm.Signature[] signatureArr;
        try {
            signatureArr = context.getPackageManager().getPackageInfo(str, 64).signatures;
        } catch (java.lang.Exception unused) {
            signatureArr = null;
        }
        if (signatureArr == null || signatureArr.length <= 0) {
            return null;
        }
        try {
            byte[] bArrDigest = java.security.MessageDigest.getInstance(a).digest(signatureArr[0].toByteArray());
            java.lang.StringBuilder sb = new java.lang.StringBuilder();
            for (byte b : bArrDigest) {
                sb.append(java.lang.Integer.toHexString((b & 255) | 256).substring(1, 3));
            }
            return sb.toString();
        } catch (java.lang.Exception unused2) {
            return null;
        }
    }
}
