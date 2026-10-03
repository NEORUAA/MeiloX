package com.netease.mobsec.xs;



public class f {
    public static com.netease.mobsec.xs.h a(android.content.Context context, long j) throws Exception {
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            throw new java.lang.IllegalStateException(m(new char[]{'!', 251, 'l', 171, 243, 'Y', 237, 4, 245, 'S', '-', 241, 'o', 138, 179, 'i', 'S', 'i', 'U', 128, 141, '2', 's', '9', 253, 243, 141, 11, 'S', 208, 11, 235, 'U', 's', 251, 'i', 141, 235, '-', ')', 245, 232, 212, 235, 180, 232, 's', 217, '5', '`', 140, '+', 171, 153, 'E', 226, 181, 186, '\f', 250, 'K', 'J', 'M', '+'}, "̧̘ͤ͒ͻˇ"));
        }
        try {
            if ((context.getPackageManager().getPackageInfo(m(new char[]{'4', 240, 239, 27, 141, 214, 204, 's', 151, '\n', 21, 222, 'r', 145, 'o', 'R', 156, 166, 'l'}, "ɪ\u0381͚ʞ̥ʰ"), 0).applicationInfo.flags & 1) == 0) {
                throw new java.lang.IllegalStateException(m(new char[]{219, 'S', 'N', 207, 246, 177, '/', 172, 22, 238, '8', 'I', '-', 243, 201, 205, 'V', '1', 151, 216, 'o', 14, '\t', '9', 158, '3', 216, 214, 232, 219, '!', 201, 23, 142, 246, '1', 192, 168}, "͕̑͝ɱ͓\u0381"));
            }
            com.netease.mobsec.xs.g gVar = new com.netease.mobsec.xs.g(j);
            android.content.Intent intent = new android.content.Intent(m(new char[]{'T', 'T', 'l', ',', '<', 158, 205, 'M', '4', '=', 29, '_', 213, 21, 's', 229, '\f', 255, 181, 'M', '4', 135, 220, 31, 'T', 175, 245, 180, 28, 159, 141, 206, 't', '=', 4, '_', 243, 237, 179, 196, 170, 'E', 236, '-', 212, 20, '6', 249, 24, '3', 206}, "̧ͩͥͦʮ˳"));
            intent.setPackage(m(new char[]{230, 142, 'd', 20, 25, 'p', '_', 143, 140, 'U', '0', 145, 135, 207, 187, '\f', 168, 'Q', 135, 143, 28, 'o'}, "˝ɳʧɦˇʅ"));
            try {
                if (!context.bindService(intent, gVar, 1)) {
                    throw new java.io.IOException(m(new char[]{'\r', 209, 156, 220, 154, '!', 30, 'V', 5, '$', 'D', 153, '~', 177, 188, 253, 203, 161, 'p', 145, 'd', 4, 226, 129, 'N', 'q', 252, '\\', '['}, "ʘʊˣ˩ʰ̅"));
                }
                try {
                    com.netease.mobsec.xs.i iVar = new com.netease.mobsec.xs.i(gVar.a());
                    return new com.netease.mobsec.xs.h(iVar.f(), iVar.a(true));
                } catch (java.lang.Throwable e) {
                    throw e;
                }
            } finally {
                context.unbindService(gVar);
            }
        } catch (java.lang.Throwable e2) {
            throw e2;
        }
    }

    public static java.lang.String b(android.content.Context context) {
        try {
            android.content.ContentResolver resolver = context.getContentResolver();
            String value = android.provider.Settings.Global.getString(resolver, "oaid");
            if (value != null && value.length() == 32) return value;
            Object service = context.getSystemService("phone");
            if (service != null) return (String)service.getClass().getDeclaredMethod("getOaid").invoke(service);
        } catch (Exception error) { }
        return "";
    }

    public static java.lang.String c(android.content.Context context) {
        try {
            java.lang.String strG = com.ljyh.mei.data.network.sdk.RuntimeAccess.G(context.getContentResolver(), r(new char[]{162, 196, 133, 'd'}, "ʖʋ͈͌̕ˆ"), "com/netease/mobsec/xs/f.class:c:(Landroid/content/Context;)Ljava/lang/String; line-0");
            if (!android.text.TextUtils.isEmpty(strG) && strG.length() == 36) {
                if (!r(new char[]{170, 165, 252, '#', '@', 213, 154, 157, 163, '+', '@', 173, 154, 254, 252, 19, '@', 213, 'D', ']', 5, 218, '@', 236, 'j', 'e', 252, 226, '@', 181, 'Z', ']', 5, '+', 193, 'M'}, "̊ˑ˜ɾ˶͓").equals(strG)) {
                    return strG;
                }
            }
        } catch (java.lang.Exception unused) {
        }
        return null;
    }

    public static java.lang.String d(android.content.Context context) {
        try {
            java.lang.Class<?> cls = java.lang.Class.forName(f(new char[]{150, 'A', 'o', 163, 217, 19, 'o', 161, 23, 132, 136, 242, 181, '`', 230, '|', ')', 211, '.', 25, 170, 164, 255, 139, 247, 223, 142, 156, 24, 's', 169, 129, 'W', 'e'}, "ɻ̂Ͳˮ˃ɲ"));
            return (java.lang.String) com.ljyh.mei.data.network.sdk.RuntimeAccess.J(cls.getMethod(f(new char[]{'3', 234, 'y', 215, 'j', 166, 'w'}, "ʛʿ͛ɭɶɰ"), android.content.Context.class), cls.newInstance(), new java.lang.Object[]{context}, "com/netease/mobsec/xs/f.class:d:(Landroid/content/Context;)Ljava/lang/String; line-0");
        } catch (java.lang.Exception unused) {
            return null;
        }
    }

    public static java.lang.String e(android.content.Context context) {
        try {
            java.lang.Class<?> cls = java.lang.Class.forName(e(new char[]{130, 190, 246, '8', '\f', '1', 224, 182, '<', 'X', 213, 249, 238, 0, 166, 204, '_', 15, 129, 222, 206, 149, '\'', 'n', 'a', 0, '7', 'X', 172, ']', 130, 190, 221, 'X', 214, 144}, "͒͋ͣʒɯˑ"));
            java.lang.reflect.Constructor<?> declaredConstructor = cls.getDeclaredConstructor(android.content.Context.class);
            declaredConstructor.setAccessible(true);
            java.lang.reflect.Method declaredMethod = cls.getDeclaredMethod(e(new char[]{254, 129, '!', 176, 'C', 158, 160}, "͠ɞ͌˫ɨɽ"), android.content.Context.class);
            declaredMethod.setAccessible(true);
            return (java.lang.String) com.ljyh.mei.data.network.sdk.RuntimeAccess.J(declaredMethod, declaredConstructor.newInstance(context), new java.lang.Object[]{context}, "com/netease/mobsec/xs/f.class:e:(Landroid/content/Context;)Ljava/lang/String; line-0");
        } catch (java.lang.Exception unused) {
            return null;
        }
    }

    public static java.lang.String f(char[] cArr, java.lang.String str) {
        int length = str.length();
        for (int i = 0; i < cArr.length; i++) {
            char c = cArr[i];
            if (c > 255) {
                cArr[i] = (char) ((c ^ (255 & str.charAt(i % length))) & 65535);
            } else {
                int i2 = ((((((((c << 7) | (c >> 1)) & 255) + i) & 255) ^ i) & 255) + 18) & 255;
                int i3 = (((((i2 << 7) & 255) ^ ((i2 >> 1) & 255)) & 255) - 238) & 255;
                cArr[i] = (char) ((((((i3 << 5) & 255) | ((i3 >> 3) & 255)) & 255) ^ str.charAt(i % length)) & 255 & 255);
            }
        }
        return new java.lang.String(cArr);
    }

    public static java.lang.String g(android.content.Context context) {
        try {
            android.app.KeyguardManager keyguardManager = (android.app.KeyguardManager) context.getSystemService(n(new char[]{'N', 210, ',', 199, '\t', 240, 'W', 234}, "̿˽͗̚˿̨"));
            if (keyguardManager == null) {
                return null;
            }
            java.lang.reflect.Method declaredMethod = keyguardManager.getClass().getDeclaredMethod(n(new char[]{'{', 'D', 251, '\'', 'X', 243, 'o', 221, ';', 141}, "˂\u0381˅͚ͬ˿"), new java.lang.Class[0]);
            declaredMethod.setAccessible(true);
            return (java.lang.String) com.ljyh.mei.data.network.sdk.RuntimeAccess.J(declaredMethod, keyguardManager, new java.lang.Object[0], "com/netease/mobsec/xs/f.class:g:(Landroid/content/Context;)Ljava/lang/String; line-0");
        } catch (java.lang.Exception unused) {
            return null;
        }
    }

    public static java.lang.String h(char[] cArr, java.lang.String str) {
        int length = str.length();
        for (int i = 0; i < cArr.length; i++) {
            char c = cArr[i];
            if (c > 255) {
                cArr[i] = (char) ((c ^ (255 & str.charAt(i % length))) & 65535);
            } else {
                int i2 = ((((((((c << 7) | (c >> 1)) & 255) + i) & 255) ^ i) & 255) + 26) & 255;
                int i3 = (((((i2 << 7) & 255) ^ ((i2 >> 1) & 255)) & 255) - 230) & 255;
                cArr[i] = (char) ((((((i3 << 5) & 255) | ((i3 >> 3) & 255)) & 255) ^ str.charAt(i % length)) & 255 & 255);
            }
        }
        return new java.lang.String(cArr);
    }

    public static java.lang.String i(char[] cArr, java.lang.String str) {
        int length = str.length();
        for (int i = 0; i < cArr.length; i++) {
            char c = cArr[i];
            if (c > 255) {
                cArr[i] = (char) ((c ^ (255 & str.charAt(i % length))) & 65535);
            } else {
                int i2 = ((((((((c << 7) | (c >> 1)) & 255) + i) & 255) ^ i) & 255) + 111) & 255;
                int i3 = (((((i2 << 7) & 255) ^ ((i2 >> 1) & 255)) & 255) - 145) & 255;
                cArr[i] = (char) ((((((i3 << 5) & 255) | ((i3 >> 3) & 255)) & 255) ^ str.charAt(i % length)) & 255 & 255);
            }
        }
        return new java.lang.String(cArr);
    }

    public static java.lang.String j(char[] cArr, java.lang.String str) {
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

    public static java.lang.String k(android.content.Context context) {
        try {
            context.getPackageManager().getPackageInfo(d(new char[]{'b', 'D', 160, 143, 201, '\n', 157, 157, 161, 'o', 'X', 19, 163, 4, 'g', 'o', 151, '{', 226, 221, 224, 204, 15, 227, '%'}, "͋ɘͷʟʾɣ"), 0);
            try {
                android.database.Cursor cursorO = com.ljyh.mei.data.network.sdk.RuntimeAccess.O(context.getContentResolver(), android.net.Uri.parse(d(new char[]{184, '@', 'V', 'N', 'Q', 168, 15, 153, 134, 220, 'p', 184, 201, 24, 134, '\f', 209, 242, 174, 233, '!', 'U', 183, 136, 152, 233, 22, 175, '1', 'H', 15, 160, 0, 213, 135, 'P'}, "ʹ\u0378ͦ˓˺ʴ")), null, null, new java.lang.String[]{d(new char[]{171, '$', 154, 128}, "̝͗\u0380ͽɪ̋")}, null, "com/netease/mobsec/xs/f.class:k:(Landroid/content/Context;)Ljava/lang/String; line-0");
                if (cursorO == null || !cursorO.moveToFirst()) {
                    return null;
                }
                int columnIndex = cursorO.getColumnIndex(d(new char[]{254, '{', 131, 5, '\\'}, "ͻͰɝ̝̈˺"));
                java.lang.String string = columnIndex > 0 ? cursorO.getString(columnIndex) : null;
                cursorO.close();
                if (android.text.TextUtils.isEmpty(string)) {
                    return null;
                }
                return string;
            } catch (java.lang.Exception unused) {
                return null;
            }
        } catch (java.lang.Exception unused2) {
            return null;
        }
    }

    public static java.lang.String l(char[] cArr, java.lang.String str) {
        int length = str.length();
        for (int i = 0; i < cArr.length; i++) {
            char c = cArr[i];
            if (c > 255) {
                cArr[i] = (char) ((c ^ (255 & str.charAt(i % length))) & 65535);
            } else {
                int i2 = ((((((((c << 7) | (c >> 1)) & 255) + i) & 255) ^ i) & 255) + 112) & 255;
                int i3 = (((((i2 << 7) & 255) ^ ((i2 >> 1) & 255)) & 255) - 144) & 255;
                cArr[i] = (char) ((((((i3 << 5) & 255) | ((i3 >> 3) & 255)) & 255) ^ str.charAt(i % length)) & 255 & 255);
            }
        }
        return new java.lang.String(cArr);
    }

    public static java.lang.String m(android.content.Context context) throws android.os.RemoteException {
        android.content.ContentProviderClient contentProviderClientAcquireContentProviderClient = null;
        try {
            contentProviderClientAcquireContentProviderClient = context.getContentResolver().acquireContentProviderClient(android.net.Uri.parse(q(new char[]{'q', 't', 150, 'R', 28, 238, 147, 2, 'V', 210, 228, 206, 184, 't', 133, 'd', 'm', 15, 200, 237, 207, 237, '5', 'q', 209, 179, 213, 138, ']', 'O', 'Q', 'l', ']', 189, 6, 16}, "ʐɳ̣̿ʼʁ")));
        } catch (java.lang.Exception unused) {
        }
        if (contentProviderClientAcquireContentProviderClient == null) {
            return null;
        }
        android.os.Bundle bundleCall = contentProviderClientAcquireContentProviderClient.call(q(new char[]{175, 24, 191, 181, 175, 'g', 211}, "ʢɜ˲̙̇ɭ"), null, null);
        if (android.os.Build.VERSION.SDK_INT >= 24) {
            contentProviderClientAcquireContentProviderClient.close();
        } else {
            contentProviderClientAcquireContentProviderClient.release();
        }
        if (bundleCall != null && bundleCall.getInt(q(new char[]{190, 159, 248, ':'}, "̭̊̈́˯ʱɾ"), -1) == 0) {
            return bundleCall.getString(q(new char[]{155, 'Q'}, "͌ʖ͍ʘʔ̛"));
        }
        return null;
    }

    public static java.lang.String n(char[] cArr, java.lang.String str) {
        int length = str.length();
        for (int i = 0; i < cArr.length; i++) {
            char c = cArr[i];
            if (c > 255) {
                cArr[i] = (char) ((c ^ (255 & str.charAt(i % length))) & 65535);
            } else {
                int i2 = ((((((((c << 7) | (c >> 1)) & 255) + i) & 255) ^ i) & 255) + 95) & 255;
                int i3 = (((((i2 << 7) & 255) ^ ((i2 >> 1) & 255)) & 255) - 161) & 255;
                cArr[i] = (char) ((((((i3 << 5) & 255) | ((i3 >> 3) & 255)) & 255) ^ str.charAt(i % length)) & 255 & 255);
            }
        }
        return new java.lang.String(cArr);
    }

    public static java.lang.String o(char[] cArr, java.lang.String str) {
        int length = str.length();
        for (int i = 0; i < cArr.length; i++) {
            char c = cArr[i];
            if (c > 255) {
                cArr[i] = (char) ((c ^ (255 & str.charAt(i % length))) & 65535);
            } else {
                int i2 = ((((((((c << 7) | (c >> 1)) & 255) + i) & 255) ^ i) & 255) + 185) & 255;
                int i3 = (((((i2 << 7) & 255) ^ ((i2 >> 1) & 255)) & 255) - 71) & 255;
                cArr[i] = (char) ((((((i3 << 5) & 255) | ((i3 >> 3) & 255)) & 255) ^ str.charAt(i % length)) & 255 & 255);
            }
        }
        return new java.lang.String(cArr);
    }

    public static java.lang.String p(char[] cArr, java.lang.String str) {
        int length = str.length();
        for (int i = 0; i < cArr.length; i++) {
            char c = cArr[i];
            if (c > 255) {
                cArr[i] = (char) ((c ^ (255 & str.charAt(i % length))) & 65535);
            } else {
                int i2 = ((((((((c << 7) | (c >> 1)) & 255) + i) & 255) ^ i) & 255) + 73) & 255;
                int i3 = (((((i2 << 7) & 255) ^ ((i2 >> 1) & 255)) & 255) - 183) & 255;
                cArr[i] = (char) ((((((i3 << 5) & 255) | ((i3 >> 3) & 255)) & 255) ^ str.charAt(i % length)) & 255 & 255);
            }
        }
        return new java.lang.String(cArr);
    }

    public static java.lang.String q(char[] cArr, java.lang.String str) {
        int length = str.length();
        for (int i = 0; i < cArr.length; i++) {
            char c = cArr[i];
            if (c > 255) {
                cArr[i] = (char) ((c ^ (255 & str.charAt(i % length))) & 65535);
            } else {
                int i2 = ((((((((c << 7) | (c >> 1)) & 255) + i) & 255) ^ i) & 255) + 130) & 255;
                int i3 = (((((i2 << 7) & 255) ^ ((i2 >> 1) & 255)) & 255) - 126) & 255;
                cArr[i] = (char) ((((((i3 << 5) & 255) | ((i3 >> 3) & 255)) & 255) ^ str.charAt(i % length)) & 255 & 255);
            }
        }
        return new java.lang.String(cArr);
    }

    public static java.lang.String r(char[] cArr, java.lang.String str) {
        int length = str.length();
        for (int i = 0; i < cArr.length; i++) {
            char c = cArr[i];
            if (c > 255) {
                cArr[i] = (char) ((c ^ (255 & str.charAt(i % length))) & 65535);
            } else {
                int i2 = ((((((((c << 7) | (c >> 1)) & 255) + i) & 255) ^ i) & 255) + 111) & 255;
                int i3 = (((((i2 << 7) & 255) ^ ((i2 >> 1) & 255)) & 255) - 145) & 255;
                cArr[i] = (char) ((((((i3 << 5) & 255) | ((i3 >> 3) & 255)) & 255) ^ str.charAt(i % length)) & 255 & 255);
            }
        }
        return new java.lang.String(cArr);
    }

    public static java.lang.String a(android.content.Context context) {
        return a(context, com.netease.mobsec.xs.l.a(new char[]{'1', 'h', 'S', 'r', 147, 'O', 'q', 'W', '2', 163, 'J', 209, 144, 153, 'R'}, "̶Ͱ̫ʁ̠̳")) ? com.netease.mobsec.xs.l.a(new char[]{'Q', 'R', 'V', ']', 183, '~', 17, 'M', '?', 154, 'f', 224, 176, 's', 'G'}, "̵́˃˘́ʺ") : a(context, com.netease.mobsec.xs.l.a(new char[]{'I', '^', 221, '7', 130, 'W', 248, 'a', 228, 222, 155, 201, 'y', 224}, "͵ʡʗʫʧͲ")) ? com.netease.mobsec.xs.l.a(new char[]{'l', 212, 26, 233, 250, 172, 20, 205, 250, 241, 176, 245, 158, '4'}, "̍͜ˡ̵ɬ˔") : a(context, com.netease.mobsec.xs.l.a(new char[]{173, '&', 171, 'd', 220, 4, 213, 25, 168, 'u', '\r', '[', 30, 215, 'x', 'T', '.', 'e'}, "ɚͤ̑͛͢ʎ")) ? com.netease.mobsec.xs.l.a(new char[]{171, 225, 'J', '2', 149, ',', 219, 222, 'K', 227, 'D', 't', 24, ' ', '[', 2, 23, '\\'}, "ɪʵͣ\u0383̐ˈ") : com.netease.mobsec.xs.l.a(new char[]{']', 183, 'R', '`', 182, 'R', 5, 166, '3', 153, 'e', 204, 174, 'F', 'S'}, "˕˦̣˰̛̙");
    }

    public static java.lang.String b(char[] cArr, java.lang.String str) {
        int length = str.length();
        for (int i = 0; i < cArr.length; i++) {
            char c = cArr[i];
            if (c > 255) {
                cArr[i] = (char) ((c ^ (255 & str.charAt(i % length))) & 65535);
            } else {
                int i2 = ((((((((c << 7) | (c >> 1)) & 255) + i) & 255) ^ i) & 255) + 55) & 255;
                int i3 = (((((i2 << 7) & 255) ^ ((i2 >> 1) & 255)) & 255) - 201) & 255;
                cArr[i] = (char) ((((((i3 << 5) & 255) | ((i3 >> 3) & 255)) & 255) ^ str.charAt(i % length)) & 255 & 255);
            }
        }
        return new java.lang.String(cArr);
    }

    public static java.lang.String c(char[] cArr, java.lang.String str) {
        int length = str.length();
        for (int i = 0; i < cArr.length; i++) {
            char c = cArr[i];
            if (c > 255) {
                cArr[i] = (char) ((c ^ (255 & str.charAt(i % length))) & 65535);
            } else {
                int i2 = ((((((((c << 7) | (c >> 1)) & 255) + i) & 255) ^ i) & 255) + 221) & 255;
                int i3 = (((((i2 << 7) & 255) ^ ((i2 >> 1) & 255)) & 255) - 35) & 255;
                cArr[i] = (char) ((((((i3 << 5) & 255) | ((i3 >> 3) & 255)) & 255) ^ str.charAt(i % length)) & 255 & 255);
            }
        }
        return new java.lang.String(cArr);
    }

    public static java.lang.String d(char[] cArr, java.lang.String str) {
        int length = str.length();
        for (int i = 0; i < cArr.length; i++) {
            char c = cArr[i];
            if (c > 255) {
                cArr[i] = (char) ((c ^ (255 & str.charAt(i % length))) & 65535);
            } else {
                int i2 = ((((((((c << 7) | (c >> 1)) & 255) + i) & 255) ^ i) & 255) + 27) & 255;
                int i3 = (((((i2 << 7) & 255) ^ ((i2 >> 1) & 255)) & 255) - 229) & 255;
                cArr[i] = (char) ((((((i3 << 5) & 255) | ((i3 >> 3) & 255)) & 255) ^ str.charAt(i % length)) & 255 & 255);
            }
        }
        return new java.lang.String(cArr);
    }

    public static java.lang.String e(char[] cArr, java.lang.String str) {
        int length = str.length();
        for (int i = 0; i < cArr.length; i++) {
            char c = cArr[i];
            if (c > 255) {
                cArr[i] = (char) ((c ^ (255 & str.charAt(i % length))) & 65535);
            } else {
                int i2 = ((((((((c << 7) | (c >> 1)) & 255) + i) & 255) ^ i) & 255) + 166) & 255;
                int i3 = (((((i2 << 7) & 255) ^ ((i2 >> 1) & 255)) & 255) - 90) & 255;
                cArr[i] = (char) ((((((i3 << 5) & 255) | ((i3 >> 3) & 255)) & 255) ^ str.charAt(i % length)) & 255 & 255);
            }
        }
        return new java.lang.String(cArr);
    }

    public static java.lang.String f(android.content.Context context) {
        com.netease.mobsec.xs.c c0005a;
        try {
            context.getPackageManager().getPackageInfo(o(new char[]{'O', 22, 219, 'N', 'O', 'T', 'v', 164, 2, 'f', 247, 141}, "ɞˬˬɫː̱"), 0);
        } catch (java.lang.Exception unused) {
        }
        if (!p(context)) {
            return null;
        }
        android.content.Intent intent = new android.content.Intent();
        intent.setClassName(o(new char[]{155, 143, 200, 131, 235, 189, 'D', ',', '!', 19, 27, '$'}, "˼̰ʅ̑˯͵"), o(new char[]{214, '%', 20, 'L', '\t', 'p', '\b', 191, 197, 'l', '8', 241, 16, 158, 234, 180, 'y', 11, 206, 159, 197, '0', 'H', 16, 245, 191, '!', '\'', 201, 219, 233, '\\', 27}, "̚ʤ˾ɻ̆͒"));
        intent.setAction(o(new char[]{206, 'S', 155, 134, '1', 162, '0', 19, '{', 206, 'q', 'A', 254, 169, 'J', 143, 207, 'I', 249, 'y', 11, 148, 176, 'x', '0', 234, 187, 204, 222, '8', 241, 169, 154}, "ɚ̒˲̩͎ʂ"));
        intent.putExtra(o(new char[]{28, 'R', 's', 'f', '.', '&', 'k', 18, 'S', 238, 160, 159, 139, 232, 179, 172, 224, '_', '{', 203, 245, '.', '`', 230, '<'}, "˰̨̳̊ˆ˱"), context.getPackageName());
        com.netease.mobsec.xs.n nVar = new com.netease.mobsec.xs.n();
        if (context.bindService(intent, nVar, 1)) {
            try {
                android.os.IBinder iBinderA = nVar.a();
                int i = com.netease.mobsec.xs.c.a.a;
                if (iBinderA == null) {
                    c0005a = null;
                } else {
                    android.os.IInterface iInterfaceQueryLocalInterface = iBinderA.queryLocalInterface("com.bun.lib.MsaIdInterface");
                    c0005a = (iInterfaceQueryLocalInterface == null || !(iInterfaceQueryLocalInterface instanceof com.netease.mobsec.xs.c)) ? new com.netease.mobsec.xs.c.a.C0005a(iBinderA) : (com.netease.mobsec.xs.c) iInterfaceQueryLocalInterface;
                }
                java.lang.String strA = c0005a.a();
                try {
                    context.unbindService(nVar);
                } catch (java.lang.Exception unused2) {
                }
                return strA;
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
        return null;
    }

    public static java.lang.String g(char[] cArr, java.lang.String str) {
        int length = str.length();
        for (int i = 0; i < cArr.length; i++) {
            char c = cArr[i];
            if (c > 255) {
                cArr[i] = (char) ((c ^ (255 & str.charAt(i % length))) & 65535);
            } else {
                int i2 = ((((((((c << 7) | (c >> 1)) & 255) + i) & 255) ^ i) & 255) + 1) & 255;
                int i3 = (((((i2 << 7) & 255) ^ ((i2 >> 1) & 255)) & 255) - 255) & 255;
                cArr[i] = (char) ((((((i3 << 5) & 255) | ((i3 >> 3) & 255)) & 255) ^ str.charAt(i % length)) & 255 & 255);
            }
        }
        return new java.lang.String(cArr);
    }

    public static java.lang.String h(android.content.Context context) {
        com.netease.mobsec.xs.d c0006a;
        try {
            context.getPackageManager().getPackageInfo(j(new char[]{184, 131, 'g', 136, 192, 26, ')', 155, 241, 17, 225, ':', 169, 194, 176, 16, 192, 176, '9', 'Z', 241, '[', 'F', 145, 216, 169, 'p'}, "ɬ˺ʹʣ̬ʵ"), 0);
            android.content.Intent intent = new android.content.Intent();
            intent.setComponent(new android.content.ComponentName(j(new char[]{211, 215, '2', 'd', 16, 155, 'R', '&', 181, '5', 144, 187, 210, 230, 236, ',', 16, '*', 146, 246, '5', 207, 215, 16, '2', 29, '-'}, "ʔ͗ʗ\u0382ʪʹ"), j(new char[]{'/', '\t', 'K', 197, 188, 155, 150, 25, 172, 213, 229, 187, 127, '8', 213, 204, 204, '*', 167, 233, '|', '.', 26, 16, 'n', '\"', 229, 197, 153, 26, 177, 185, 138, 245, 137, 130, '5', 185, 213, 'N', 141, '*', 230}, "ʱˮ˞ʉˌʹ")));
            com.netease.mobsec.xs.n nVar = new com.netease.mobsec.xs.n();
            if (context.bindService(intent, nVar, 1)) {
                try {
                    android.os.IBinder iBinderA = nVar.a();
                    int i = com.netease.mobsec.xs.d.a.a;
                    if (iBinderA == null) {
                        c0006a = null;
                    } else {
                        android.os.IInterface iInterfaceQueryLocalInterface = iBinderA.queryLocalInterface("com.coolpad.deviceidsupport.IDeviceIdManager");
                        c0006a = (iInterfaceQueryLocalInterface == null || !(iInterfaceQueryLocalInterface instanceof com.netease.mobsec.xs.d)) ? new com.netease.mobsec.xs.d.a.C0006a(iBinderA) : (com.netease.mobsec.xs.d) iInterfaceQueryLocalInterface;
                    }
                    java.lang.String strA = c0006a.a(context.getPackageName());
                    try {
                        context.unbindService(nVar);
                    } catch (java.lang.Exception unused) {
                    }
                    return strA;
                } catch (java.lang.Exception unused2) {
                    context.unbindService(nVar);
                } catch (java.lang.Throwable th) {
                    try {
                        context.unbindService(nVar);
                    } catch (java.lang.Exception unused3) {
                    }
                    throw th;
                }
            }
        } catch (java.lang.Exception unused4) {
        }
        return null;
    }

    public static java.lang.String i(android.content.Context context) {
        com.netease.mobsec.xs.a c0003a;
        java.lang.String strA = null;
        try {
            context.getPackageManager().getPackageInfo(g(new char[]{225, 231, 'V', 'D', '|', 'Z', 1, '4', 255, 148, '$', 'J', 225, '<', '\'', 141, 235, 'J', 'w'}, "͜ˠ͎ʼ˵ˍ"), 0);
            android.content.Intent intent = new android.content.Intent(g(new char[]{210, 143, 254, 17, ',', 194, 'r', 'w', 233, 235, 187, ')', 179, 14, 31, 'j', '=', 131, ',', '/', '&', 'z', 28, ':', 't', 14}, "ʧʢ̌ˊͽ̏"));
            intent.setPackage(g(new char[]{'-', 229, 168, 28, '!', '|', 181, '>', 232, 156, 129, 'l', 29, '>', 153, 133, '?', 'l', 11}, "˺̰̘Ϳɘ˺"));
            com.netease.mobsec.xs.n nVar = new com.netease.mobsec.xs.n();
            if (context.bindService(intent, nVar, 1)) {
                try {
                    android.os.IBinder iBinderA = nVar.a();
                    int i = com.netease.mobsec.xs.a.AbstractBinderC0002a.a;
                    if (iBinderA == null) {
                        c0003a = null;
                    } else {
                        android.os.IInterface iInterfaceQueryLocalInterface = iBinderA.queryLocalInterface("com.android.creator.IdsSupplier");
                        c0003a = (iInterfaceQueryLocalInterface == null || !(iInterfaceQueryLocalInterface instanceof com.netease.mobsec.xs.a)) ? new com.netease.mobsec.xs.a.AbstractBinderC0002a.C0003a(iBinderA) : (com.netease.mobsec.xs.a) iInterfaceQueryLocalInterface;
                    }
                    strA = c0003a.a();
                } catch (java.lang.Exception unused) {
                } catch (java.lang.Throwable th) {
                    try {
                        context.unbindService(nVar);
                    } catch (java.lang.Exception unused2) {
                    }
                    throw th;
                }
                context.unbindService(nVar);
            }
        } catch (java.lang.Exception unused3) {
        }
        return strA;
    }

    public static java.lang.String j(android.content.Context context) {
        com.netease.mobsec.xs.i0 c0010a;
        try {
            context.getPackageManager().getPackageInfo(l(new char[]{136, 'K', 26, 'x', 135, 'g', 185, 27, 218, 177, 254, 216, 'x', 253, 'k', 145, '&', '9', '*', 227, 139, 16, 222}, "̺̐ʨ˶͛ʵ"), 0);
            android.content.Intent intent = new android.content.Intent();
            intent.setClassName(l(new char[]{148, '\\', 'r', 205, 164, 142, 205, ',', 's', '\\', 28, 241, 't', 236, 210, 'd', 197, 144, '.', 244, 178, 234, 255}, "˚ʘ˥ʝ̀˼"), l(new char[]{'F', 143, 200, 180, 'A', 245, 254, 'g', 169, 'u', 195, 'J', 'F', 174, ';', 'M', 227, 179, 221, '0', ';', 'T', '!', 'E', '#', 174, '+', 236, 225, 147, 254, 'N', 14, 149, 195, 205, 7, 15, '9'}, "̈́ˮ̶˔͵ˁ"));
            com.netease.mobsec.xs.n nVar = new com.netease.mobsec.xs.n();
            if (context.bindService(intent, nVar, 1)) {
                try {
                    android.os.IBinder iBinderA = nVar.a();
                    int i = com.netease.mobsec.xs.i0.a.a;
                    if (iBinderA == null) {
                        c0010a = null;
                    } else {
                        android.os.IInterface iInterfaceQueryLocalInterface = iBinderA.queryLocalInterface("com.zui.deviceidservice.IDeviceidInterface");
                        c0010a = (iInterfaceQueryLocalInterface == null || !(iInterfaceQueryLocalInterface instanceof com.netease.mobsec.xs.i0)) ? new com.netease.mobsec.xs.i0.a.C0010a(iBinderA) : (com.netease.mobsec.xs.i0) iInterfaceQueryLocalInterface;
                    }
                    java.lang.String strC = c0010a.c();
                    try {
                        context.unbindService(nVar);
                    } catch (java.lang.Exception unused) {
                    }
                    return strC;
                } catch (java.lang.Exception unused2) {
                    context.unbindService(nVar);
                } catch (java.lang.Throwable th) {
                    try {
                        context.unbindService(nVar);
                    } catch (java.lang.Exception unused3) {
                    }
                    throw th;
                }
            }
        } catch (java.lang.Exception unused4) {
        }
        return null;
    }

    public static java.lang.String k(char[] cArr, java.lang.String str) {
        int length = str.length();
        for (int i = 0; i < cArr.length; i++) {
            char c = cArr[i];
            if (c > 255) {
                cArr[i] = (char) ((c ^ (255 & str.charAt(i % length))) & 65535);
            } else {
                int i2 = ((((((((c << 7) | (c >> 1)) & 255) + i) & 255) ^ i) & 255) + 115) & 255;
                int i3 = (((((i2 << 7) & 255) ^ ((i2 >> 1) & 255)) & 255) - 141) & 255;
                cArr[i] = (char) ((((((i3 << 5) & 255) | ((i3 >> 3) & 255)) & 255) ^ str.charAt(i % length)) & 255 & 255);
            }
        }
        return new java.lang.String(cArr);
    }

    public static java.lang.String l(android.content.Context context) {
        try {
            android.content.ContentResolver contentResolver = context.getContentResolver();
            char[] cArr = new char[4];
            cArr[0] = 215;
            cArr[1] = 212;
            cArr[2] = 127;
            cArr[3] = 135;
            for (int i = 0; i < 4; i++) {
                char c = cArr[i];
                if (c > 255) {
                    cArr[i] = (char) ((c ^ (255 & "ɟͩ˄ʩʃɪ".charAt(i % 6))) & 65535);
                } else {
                    int i2 = ((((((((c << 7) | (c >> 1)) & 255) + i) & 255) ^ i) & 255) + 93) & 255;
                    int i3 = (((((i2 << 7) & 255) ^ ((i2 >> 1) & 255)) & 255) - 163) & 255;
                    cArr[i] = (char) ((((((i3 << 5) & 255) | ((i3 >> 3) & 255)) & 255) ^ "ɟͩ˄ʩʃɪ".charAt(i % 6)) & 255 & 255);
                }
            }
            return com.ljyh.mei.data.network.sdk.RuntimeAccess.G(contentResolver, new java.lang.String(cArr), "com/netease/mobsec/xs/f.class:l:(Landroid/content/Context;)Ljava/lang/String; line-2");
        } catch (java.lang.Exception unused) {
            return null;
        }
    }

    public static java.lang.String m(char[] cArr, java.lang.String str) {
        int length = str.length();
        for (int i = 0; i < cArr.length; i++) {
            char c = cArr[i];
            if (c > 255) {
                cArr[i] = (char) ((c ^ (255 & str.charAt(i % length))) & 65535);
            } else {
                int i2 = ((((((((c << 7) | (c >> 1)) & 255) + i) & 255) ^ i) & 255) + 125) & 255;
                int i3 = (((((i2 << 7) & 255) ^ ((i2 >> 1) & 255)) & 255) - 131) & 255;
                cArr[i] = (char) ((((((i3 << 5) & 255) | ((i3 >> 3) & 255)) & 255) ^ str.charAt(i % length)) & 255 & 255);
            }
        }
        return new java.lang.String(cArr);
    }

    public static java.lang.String n(android.content.Context context) {
        com.netease.mobsec.xs.g0 c0008a;
        try {
            context.getPackageManager().getPackageInfo(b(new char[]{191, 208, 247, 'h', 'k', 133, 129, 'P', 222, 'P', 212, 183, 207, 208, '!', 208, 187, '~', 150, 168, 224, '{', 11, '~', '_', '\n', 22, 'Q', 11, 5, 161, 145, 255, 'J', 180}, "̫ʯ̧ʳ̇ͅ"), 0);
            android.content.Intent intent = new android.content.Intent();
            intent.setClassName(b(new char[]{24, 'P', '{', 16, 156, '`', 'O', 208, 'Z', '\b', '*', 202, 169, 'P', 'd', 'y', 236, '[', 249, '(', 140, 162, 245, 'C', 249, 139, 154, 186, '\\', 161, 143, 17, 'z', 169, 'k'}, "ɸʳ͛˴ˌ̼"), b(new char[]{216, 216, ')', 147, 23, 171, 142, 'H', '(', 's', 'o', 'k', 232, 136, 255, 233, 150, 192, '9', 192, 6, 243, '/', 192, 184, 243, '_', '*', 246, 203, 'N', 'I', 167, 's', 216, 139, 236, 18, '9', 168, 248, 26, 251, 'K', 221, 18, 'N', 160, 22, 211, 223}, "ɢͮˮ̩ͩ͠"));
            com.netease.mobsec.xs.n nVar = new com.netease.mobsec.xs.n();
            if (context.bindService(intent, nVar, 1)) {
                try {
                    android.os.IBinder iBinderA = nVar.a();
                    int i = com.netease.mobsec.xs.g0.a.a;
                    if (iBinderA == null) {
                        c0008a = null;
                    } else {
                        android.os.IInterface iInterfaceQueryLocalInterface = iBinderA.queryLocalInterface("com.samsung.android.deviceidservice.IDeviceIdService");
                        c0008a = (iInterfaceQueryLocalInterface == null || !(iInterfaceQueryLocalInterface instanceof com.netease.mobsec.xs.g0)) ? new com.netease.mobsec.xs.g0.a.C0008a(iBinderA) : (com.netease.mobsec.xs.g0) iInterfaceQueryLocalInterface;
                    }
                    java.lang.String strB = c0008a.b();
                    try {
                        context.unbindService(nVar);
                    } catch (java.lang.Exception unused) {
                    }
                    return strB;
                } catch (java.lang.Exception unused2) {
                    context.unbindService(nVar);
                } catch (java.lang.Throwable th) {
                    try {
                        context.unbindService(nVar);
                    } catch (java.lang.Exception unused3) {
                    }
                    throw th;
                }
            }
        } catch (java.lang.Exception unused4) {
        }
        return null;
    }




    public static boolean o(android.content.Context context) {
        // Run in this application's main process. SDK contexts can wrap the package identity.
        String process = android.app.Application.getProcessName();
        return process != null && process.equals(context.getApplicationInfo().processName);

    }

    public static boolean p(android.content.Context context) {
        try {
            android.content.Intent intent = new android.content.Intent();
            intent.setClassName(o(new char[]{'C', 254, 22, 'o', '.', 'V', '|', 221, 199, 'G', 214, 6}, "ʿʵˎɰˉ͢"), o(new char[]{22, 227, 132, 132, 162, 181, 200, 248, 20, 20, 220, ',', 'Q', 'c', '{', 236, 'E', 'O', 14, 232, 245, 233, 156, 'L', 244, 2, 176, 206, 196, 143, 168, 25, 139}, "̀ʎɺ̹ʺ̴"));
            intent.setAction(o(new char[]{141, 173, 238, 209, 248, '=', 219, 205, 214, 25, 232, 'L', '=', 23, 239, 'H', 'h', ',', 179, 228, 22, '3', 254, 212, 147, 236, 153, '9', 135, 189, 'u', 150}, "ͬͿ̗̟ʈ\u0378"));
            intent.putExtra(o(new char[]{231, 186, 165, 'c', 162, '#', '9', 218, 173, 243, '<', 147, 249, 128, 'u', 169, 194, '{', '\b', 242, 154, '3', '<', 219, 'h'}, "ʂ˗ɽ̐ʵʎ"), context.getPackageName());
            intent.putExtra(o(new char[]{132, 175, 175, 135, 249, 194, '#', 183, 151, 207, 233, 243, 'b', 'U', 'o', 141, '\'', 26, '\\', 174, 22, 'n', 127, '{', 197, 238}, "ʵ̯̱̍ʐʍ"), true);
            context.startService(intent);
            return true;
        } catch (java.lang.Exception unused) {
            return false;
        }
    }

    public static java.lang.String a(android.view.Display display) {
        try {
            java.lang.reflect.Method declaredMethod = android.view.Display.class.getDeclaredMethod(i(new char[]{'$', 244, 184, 5, 199, 227, '\\', 206, 27, 201, 'H', '{', 229, 15, 174, 212, '\b', 'B', 28}, "\u0382̉˾ɢͤͺ"), new java.lang.Class[0]);
            declaredMethod.setAccessible(true);
            return (java.lang.String) com.ljyh.mei.data.network.sdk.RuntimeAccess.J(declaredMethod, display, new java.lang.Object[0], "com/netease/mobsec/xs/f.class:a:(Landroid/view/Display;)Ljava/lang/String; line-0");
        } catch (java.lang.Exception unused) {
            return i(new char[]{'i', 181, '!', 225}, "̗ͮʡʯ̓ɬ");
        }
    }

    public static java.lang.String a(java.lang.String str, java.lang.String str2) {
        try {
            java.lang.Class<?> cls = java.lang.Class.forName(c(new char[]{221, 195, '/', 130, '\'', ')', 'j', 179, 183, 'j', 'G', 207, 170, 24, '\t', 18, 166, 14, 251, '[', 'i', 219, 190, 136, 188, '(', '9'}, "̹ˉ͇ʏˍɚ"));
            return (java.lang.String) com.ljyh.mei.data.network.sdk.RuntimeAccess.J(cls.getMethod(c(new char[]{'s', 'B', 21}, "͢˞̦̓̓ͷ"), java.lang.String.class, java.lang.String.class), cls, new java.lang.Object[]{str, str2}, "com/netease/mobsec/xs/f.class:a:(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String; line-0");
        } catch (java.lang.Exception unused) {
            return str2;
        }
    }

    public static java.lang.String a(char[] cArr, java.lang.String str) {
        int length = str.length();
        for (int i = 0; i < cArr.length; i++) {
            char c = cArr[i];
            if (c > 255) {
                cArr[i] = (char) ((c ^ (255 & str.charAt(i % length))) & 65535);
            } else {
                int i2 = ((((((((c << 7) | (c >> 1)) & 255) + i) & 255) ^ i) & 255) + 250) & 255;
                int i3 = (((((i2 << 7) & 255) ^ ((i2 >> 1) & 255)) & 255) - 6) & 255;
                cArr[i] = (char) ((((((i3 << 5) & 255) | ((i3 >> 3) & 255)) & 255) ^ str.charAt(i % length)) & 255 & 255);
            }
        }
        return new java.lang.String(cArr);
    }

    public static void a(java.io.Closeable closeable) {
        if (closeable != null) {
            try {
                closeable.close();
            } catch (java.lang.Exception unused) {
            }
        }
    }

    public static void a(java.io.Closeable... closeableArr) {
        for (java.io.Closeable closeable : closeableArr) {
            if (closeable != null) {
                try {
                    closeable.close();
                } catch (java.io.IOException unused) {
                }
            }
        }
    }

    public static boolean a(java.lang.CharSequence charSequence) {
        return charSequence == null || charSequence.length() == 0;
    }

    public static boolean a(java.lang.String str) {
        java.net.URI uri = null;
        if (android.text.TextUtils.isEmpty(str)) {
            return false;
        }
        try {
            uri = new java.net.URI(str);
        } catch (java.lang.Exception unused) {
        }
        if (uri.getHost() == null) {
            return false;
        }
        if (!uri.getScheme().equalsIgnoreCase(a(new char[]{'A', 'H', '2', 'I'}, "ʀ͕͙̄ʚɠ"))) {
            if (!uri.getScheme().equalsIgnoreCase(a(new char[]{195, 177, 163, 235, 'E'}, "ʔ̘ʏˮͺ̌"))) {
                return false;
            }
        }
        return true;
    }

    public static byte[] a(java.io.InputStream inputStream) {
        java.io.ByteArrayOutputStream byteArrayOutputStream;
        try {
            byteArrayOutputStream = new java.io.ByteArrayOutputStream();
            byte[] bArr = new byte[1024];
            while (true) {
                int i = inputStream.read(bArr, 0, 1024);
                if (i == -1) {
                    break;
                }
                byteArrayOutputStream.write(bArr, 0, i);
            }
            a(inputStream);
        } catch (java.io.IOException unused) {
            a(inputStream);
            byteArrayOutputStream = null;
        } catch (java.lang.Throwable th) {
            a(inputStream);
            throw th;
        }
        return byteArrayOutputStream.toByteArray();
    }

    public static boolean a() {
        int iMyUid = android.os.Process.myUid() % 100000;
        return (iMyUid >= 99000 && iMyUid <= 99999) || (iMyUid >= 90000 && iMyUid <= 98999);
    }



    public static boolean a(android.content.Context context, java.lang.String str) {
        android.content.pm.PackageInfo packageInfo = null;
        if (android.text.TextUtils.isEmpty(str) || context == null) {
            packageInfo = null;
        } else {
            try {
                android.content.pm.PackageManager packageManager = context.getPackageManager();
                if (packageManager != null) {
                    packageInfo = packageManager.getPackageInfo(str, 128);
                }
            } catch (java.lang.Exception unused) {
            }
        }
        return packageInfo != null;
    }
}
