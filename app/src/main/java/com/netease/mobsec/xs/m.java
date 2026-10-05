package com.netease.mobsec.xs;



public class m {
    public static final boolean a;
    public static final boolean b;
    public static final java.lang.String c;
    public static final java.lang.String d;
    public static final java.lang.String e;

    static {
        int i = android.os.Build.VERSION.SDK_INT;
        a = i >= 29;
        b = i >= 28;
        c = android.os.Build.MANUFACTURER.toUpperCase();
        d = android.os.Build.BRAND.toUpperCase();
        e = android.os.Build.PRODUCT.toUpperCase();
    }

    public static java.lang.String a(char[] cArr, java.lang.String str) {
        int length = str.length();
        for (int i = 0; i < cArr.length; i++) {
            char c2 = cArr[i];
            if (c2 > 255) {
                cArr[i] = (char) ((c2 ^ (255 & str.charAt(i % length))) & 65535);
            } else {
                int i2 = ((((((((c2 << 7) | (c2 >> 1)) & 255) + i) & 255) ^ i) & 255) + 254) & 255;
                int i3 = (((((i2 << 7) & 255) ^ ((i2 >> 1) & 255)) & 255) - 2) & 255;
                cArr[i] = (char) ((((((i3 << 5) & 255) | ((i3 >> 3) & 255)) & 255) ^ str.charAt(i % length)) & 255 & 255);
            }
        }
        return new java.lang.String(cArr);
    }

    public static boolean b() throws java.lang.ClassNotFoundException {
        java.lang.String strA = com.netease.mobsec.xs.f.a(a(new char[]{253, 11, 'd', 'z', 145, 192, '&', 'R', 'l', 3, 'Q', 'A', 238, ';', 236, 195, 'r', 193, 183, 'B', 245}, "˽ɷ̭ʱ͙ˌ"), a(new char[]{177, 141, 'F', 196, 222, 29, 210}, "ɘ͢\u0379ɨ\u0378;"));
        return !android.text.TextUtils.isEmpty(strA) && a(new char[]{230, 201, 245, 'C', 127, 135, 253, 153}, "ʐʿˊ̹͑͟").equalsIgnoreCase(strA);
    }

    public static boolean c() {
        return a(a(new char[]{'9', 170, '&', 175, 185, 'H'}, "̥͐͟Ͳʻ̭")) || a(a(new char[]{143, 31, ':', 'P', 31, 186, 'T', 222}, "͑˷̥ɭ͕ə")) || a(a(new char[]{'w', 202, 169}, "́̓ʧ̰ʜ͓"));
    }

    public static boolean d() {
        return a(a(new char[]{'q', 240, 241, 26, 239}, "ͦͦ͢ʊˊʡ")) || android.os.Build.DISPLAY.toUpperCase().contains(a(new char[]{'O', 150, ']', '7', '9'}, "̸͜ʓ̴ʬɺ"));
    }

    public static boolean e() {
        return !android.text.TextUtils.isEmpty(com.netease.mobsec.xs.f.a(a(new char[]{0, 140, '+', 172, 205, 185, 162, 172, 128, 20, '=', 233, '#', 27, 192, '\f', 29, 166, 138, 'd', 3, 't', 'D'}, "˝ͫ˖̨˧ʘ"), ""));
    }

    public static boolean f() {
        return !android.text.TextUtils.isEmpty(com.netease.mobsec.xs.f.a(a(new char[]{137, '\b', 193, 196, 30, 28, 186, 150, 193, 234, 229, '\"', 153, '7', 159, '2', 237, 'K', ')', 'G', 'Y', '='}, "ʙʐʃɤɤɨ"), "")) || a(new char[]{228, 206, 'h'}, "ʈͿʭ˧˿̵").equals(c);
    }

    public static boolean g() {
        return a(a(new char[]{249, 145, 129, 'N', 130}, "̡ɹ˩ͻ˲Ͳ"));
    }

    public static boolean h() {
        java.lang.String strA = a(new char[]{17, 229, 'f', 'H'}, "ɧʞ̓ʮɢͷ");
        java.lang.String str = c;
        return strA.equals(str) || a(new char[]{233, 204, 4, 'O', 200, 243, 'Z'}, "ʡ͈ͅɪ̪̊").equals(str) || a(new char[]{'(', 'O', 199, 152, '[', '2'}, "ʲɿ̨͟ʷ̔").equals(str) || e.startsWith(a(new char[]{253, 138, 'p', 5, 197, 148, 'f'}, "ˀ͚ͦɘ\u0381̑")) || !android.text.TextUtils.isEmpty(com.netease.mobsec.xs.f.a(a(new char[]{141, 204, 218, 127, 241, 147, '6', 149, 186, 0, 211, 204, 157, 253, 210, 254, 154, 'S', 197, 226, 178, 129, 'r', 11}, ";ͩɘ˙̵͚"), ""));
    }

    public static boolean i() {
        return a(a(new char[]{29, 182, 30, 213, 'z', '@', 135}, "˛̴ʝ˝ʦ˯"));
    }

    public static boolean j() {
        return a(a(new char[]{3, '\b', '5', 154, 'w', '(', 3, 'v', 188}, "ˤʲˈʆ̨̏")) || a(a(new char[]{221, 130, 234, '+', 2, 216, 132, 27, 202}, "ˊʖʺ̍˾ʯ"));
    }

    public static boolean k() {
        return a(a(new char[]{'>', 19, 144, 213}, "ˇ̑Ͳˁ͏ˮ")) || !android.text.TextUtils.isEmpty(com.netease.mobsec.xs.f.a(a(new char[]{'4', 'n', 'Y', 'J', 134, 'O', 143, 'F', 'Q', 210, 'v', '/', 182, 177, 210, 141, 198, 'P'}, "̳ɜ̤̈́ʺ͌"), ""));
    }

    public static boolean l() {
        java.lang.String strA = a(new char[]{162, 5, 163, '3', '1', '\b'}, "ˬʎ˽˖ɤ̩");
        java.lang.String str = c;
        return strA.equals(str) || a(new char[]{1, '\t', 'G', '5', 163, '_', 193, 'Y', '-', 28}, "˥ʫ͛ˊ˷˩").equals(str) || a(new char[]{164, 255, 'g', 'M', 249}, "ʖ˺̇͟ʶ˗").equals(str) || a(new char[]{'U', 252, '0', 'L', 173}, "̇˂̖ͨ˘ʧ").equals(d);
    }

    public static boolean m() {
        return a(a(new char[]{'m', 26, 188}, "͑˄ʀ̡͟˴"));
    }

    public static boolean a() {
        return a(a(new char[]{'g', 174, '6', 252, '+'}, "ʟɰ˓ʅ̲ɽ"));
    }

    public static boolean a(java.lang.String str) {
        if (str.isEmpty()) {
            return false;
        }
        return str.equals(c) || str.equals(d);
    }




    public static java.lang.String a(android.content.Context context) throws Exception {
        java.lang.String string = null;
        java.lang.String string2 = null;
        com.netease.mobsec.xs.l.a aVar = null;
        java.lang.String string3 = null;
        java.lang.String string4 = null;
        com.netease.mobsec.xs.j.a aVar2 = null;
        com.netease.mobsec.xs.b c0004a;
        if (android.os.Looper.myLooper() != android.os.Looper.getMainLooper()) {
            if (a(a(new char[]{'R', 155, '3', 251}, "ͳˏɬʜ̼ʢ")) && a) {
                try {
                    context.getPackageManager().getPackageInfo(com.netease.mobsec.xs.f.k(new char[]{'2', '*', '\f', 'W', 15, '0', 'm', 176, 221, 143, 182, 227, 'j', 148, 242, 236, 'A', 'J', '3', 163, 21, 'O', 'f', 147, 245, 17, 230, 227, 196}, "͔\u0378͋ɞ˯ɴ"), 0);
                    android.content.Intent intent = new android.content.Intent();
                    intent.setAction(com.netease.mobsec.xs.f.k(new char[]{'j', 208, 138, '&', 'o', 182, 27, '3', 162, 255, 193, 132, 2, 'q', ':', 220, '>', 'L', '\n', 184, 6, 'j', 195, 248, '(', '/', 200, 178, 130, 152}, "ʛɣʔ˘˰ʈ"));
                    intent.setComponent(new android.content.ComponentName(com.netease.mobsec.xs.f.k(new char[]{'1', 158, 231, 141, 154, '1', 'p', 28, 247, ']', 'd', 208, 'w', ' ', 208, 166, 204, 'Q', '&', 30, 158, 141, ';', 176, 240, 164, 178, 153, '9'}, "ͼ˕\u0378̌ʛɬ"), com.netease.mobsec.xs.f.k(new char[]{221, 26, 'E', 139, '^', 143, 139, 160, 226, 'k', 144, '-', 'T', 164, '4', 176, 'P', 180, 149, 211, ':', '{', 208, 'M', 155, '!', 223, 143, 178, 198, 175, 160, 173, 'P', 'G', 'M', 140, 'J', 'Z', 209, 239, 143, 244, 254, 'F', 175, 140, '-', ';', 250, 'J', 240, 30}, "̇˹ʒɼɸ́")));
                    com.netease.mobsec.xs.n nVar = new com.netease.mobsec.xs.n();
                    if (context.bindService(intent, nVar, 1)) {
                        try {
                            android.os.IBinder iBinderA = nVar.a();
                            int i = com.netease.mobsec.xs.b.a.a;
                            if (iBinderA == null) {
                                c0004a = null;
                            } else {
                                android.os.IInterface iInterfaceQueryLocalInterface = iBinderA.queryLocalInterface("com.asus.msa.SupplementaryDID.IDidAidlInterface");
                                c0004a = (iInterfaceQueryLocalInterface == null || !(iInterfaceQueryLocalInterface instanceof com.netease.mobsec.xs.b)) ? new com.netease.mobsec.xs.b.a.C0004a(iBinderA) : (com.netease.mobsec.xs.b) iInterfaceQueryLocalInterface;
                            }
                            java.lang.String strB = c0004a.b();
                            try {
                                context.unbindService(nVar);
                                return strB;
                            } catch (java.lang.Exception unused) {
                                return strB;
                            }
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
            if ((a(a(new char[]{211, '0', 5, 241, 's'}, "Ͷͮ͆ͩ͌͠")) || !android.text.TextUtils.isEmpty(com.netease.mobsec.xs.f.a(a(new char[]{'/', 160, 'O', 'V', 241, '$', 212, 0, '7', 184, 211, '{', 254, '`', 'W', 167, 154, 'd', 'E', 'a', '_', '&'}, "ɫˋʹ͚̐ʩ"), ""))) && a && com.netease.mobsec.xs.j.a(context)) {
                if (android.os.Build.VERSION.SDK_INT >= 24) {
                    try {
                        string3 = android.provider.Settings.Global.getString(context.getContentResolver(), com.netease.mobsec.xs.j.a);
                        string4 = android.provider.Settings.Global.getString(context.getContentResolver(), com.netease.mobsec.xs.j.b);
                    } catch (java.lang.Throwable unused5) {
                    }
                    aVar2 = (android.text.TextUtils.isEmpty(string3) || android.text.TextUtils.isEmpty(string4)) ? null : new com.netease.mobsec.xs.j.a(string3, java.lang.Boolean.parseBoolean(string4));
                }
                if (aVar2 == null) {
                    return null;
                }
                return aVar2.a;
            }
            if (!(a(a(new char[]{138, 229, 180, 139, 5, '6'}, "ʻʛ˄̋\u0382ʸ")) || a(a(new char[]{241, '<', 26, 200, 15}, "ͧˎ̾ʪˊ˒"))) && !(!android.text.TextUtils.isEmpty(com.netease.mobsec.xs.f.a(a(new char[]{'i', '\r', 'S', 145, 170, 243, 154, 'T', '3', 238, 152, 'l', 'y', 189, 'K', 241, 209, '2', 202, 'B', 'k'}, "ʘɧ̶͎̔̀"), "")))) {
                if (c() && a) {
                    return com.netease.mobsec.xs.f.j(context);
                }
                if (d() && a) {
                    return com.netease.mobsec.xs.f.k(context);
                }
                if (g() && a) {
                    return com.netease.mobsec.xs.f.m(context);
                }
                if (h() && a) {
                    return com.netease.mobsec.xs.o.a(context);
                }
                if (i() && a) {
                    return com.netease.mobsec.xs.f.n(context);
                }
                if (k() && b) {
                    return com.netease.mobsec.xs.p.a(context);
                }
                if ((l() || e()) && b) {
                    return com.netease.mobsec.xs.f.d(context);
                }
                if (m() && a) {
                    java.lang.String strE = com.netease.mobsec.xs.f.e(context);
                    return (strE == null || strE.length() < 32) ? com.netease.mobsec.xs.f.f(context) : strE;
                }
                java.lang.String strA = a(new char[]{177, 240, '\t', '|', 251, 219, 144, '.'}, "ɫɢʵʑʢɚ");
                java.lang.String str = c;
                if ((strA.equals(str) || b()) && a) {
                    return com.netease.mobsec.xs.f.i(context);
                }
                if (j() && a) {
                    return com.netease.mobsec.xs.f.c(context);
                }
                if (a() && a) {
                    return com.netease.mobsec.xs.f.b(context);
                }
                if (a(new char[]{'P', 'q', 141, 189, '}', 198, 176}, "ɡɤ̃ʁ̷͜").equals(str) && a) {
                    return com.netease.mobsec.xs.f.h(context);
                }
                if (a(new char[]{176, 'm', 172, 26, 217}, "ɵə̌ʊʫ˷").equals(str) && a) {
                    return com.netease.mobsec.xs.f.g(context);
                }
                if (!f() || android.os.Build.VERSION.SDK_INT < 32) {
                    return null;
                }
                return com.netease.mobsec.xs.f.l(context);
            }
            java.lang.String str2 = com.netease.mobsec.xs.l.a;
            if (android.os.Build.VERSION.SDK_INT >= 24) {
                try {
                    string = android.provider.Settings.Global.getString(context.getContentResolver(), com.netease.mobsec.xs.l.a);
                    string2 = android.provider.Settings.Global.getString(context.getContentResolver(), com.netease.mobsec.xs.l.b);
                } catch (java.lang.Throwable unused6) {
                }
                if (android.text.TextUtils.isEmpty(string) || android.text.TextUtils.isEmpty(string2)) {
                    aVar = com.netease.mobsec.xs.l.b(context);
                } else {
                    com.netease.mobsec.xs.l.c.execute(new com.netease.mobsec.xs.k(context));
                    aVar = new com.netease.mobsec.xs.l.a(string, java.lang.Boolean.parseBoolean(string2));
                }
            }
            if (aVar == null) {
                return null;
            }
            return aVar.a;
        }
        throw new java.lang.IllegalArgumentException(a(new char[]{'O', 20, 195, 204, 'F', 'e', 149, 20, '@', 'j', '>', 148, 255, 213, 'z', 'U', 167, 'e', 166, 229, 1, 189, 189, 221, 15, 'T'}, "ɹ̡˓ͦʿʥ"));
    }
}
