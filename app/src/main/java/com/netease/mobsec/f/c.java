package com.netease.mobsec.f;



public class c {
    static volatile com.netease.mobsec.f.c c;
    static volatile com.netease.mobsec.f.f.a d;
    final com.netease.mobsec.f.b a;
    final android.content.Context b;

    c(android.content.Context context) {
        this.a = com.netease.mobsec.f.b.a(context);
        this.b = context;
        b().b(context);
    }

    com.netease.mobsec.f.a a(int i) {
        return this.a.a(i);
    }

    public static com.netease.mobsec.f.f.a b() {
        if (d == null) {
            d = new com.netease.mobsec.f.f.b();
        }
        return d;
    }

    public static com.netease.mobsec.f.c a(android.content.Context context) {
        if (c != null) {
            return c;
        }
        if (context == null) {
            return null;
        }
        try {
            synchronized (com.netease.mobsec.f.c.class) {
                if (c == null) {
                    c = new com.netease.mobsec.f.c(context);
                }
            }
            return c;
        } catch (java.lang.Throwable unused) {
            return null;
        }
    }

    public com.netease.mobsec.f.e.b a() {
        return (com.netease.mobsec.f.e.b) a(5);
    }
}
