package com.netease.mobsec.f;



public class b {
    static com.netease.mobsec.f.b c = null;
    public static int d = 2;
    public static int e;
    final android.content.Context a;
    java.util.List<com.netease.mobsec.f.e.a> b;

    b(android.content.Context context) {
        this.a = context;
        a();
    }

    public final com.netease.mobsec.f.a a(int i) {
        return this.b.get(i);
    }

    public static com.netease.mobsec.f.b a(android.content.Context context) {
        if (c == null) {
            c = new com.netease.mobsec.f.b(context);
        }
        return c;
    }

    void a() {
        this.b = new java.util.ArrayList(8);
        for (int i = 0; i < 8; i++) {
            this.b.add(null);
        }
        this.b.set(5, new com.netease.mobsec.f.e.a(this.a));
    }
}
