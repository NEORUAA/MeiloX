package com.netease.mobsec.h;



public class p {
    static com.netease.mobsec.h.p j = null;
    static short k = 0;
    static final java.lang.String l = "200";
    static final java.lang.String m = "A4.4.7.16";
    static java.lang.String n = "";
    static java.lang.String o = null;
    static boolean p = true;
    static boolean q = true;
    static boolean r = false;
    static com.netease.mobsec.f.c s = null;


    static com.netease.mobsec.h.b u = null;
    static volatile boolean w;
    static com.netease.mobsec.AbstractNetClient x;
    com.netease.mobsec.f.e.b c;
    static final android.os.Handler y = new android.os.Handler(android.os.Looper.getMainLooper());
    static volatile boolean A = false;
    int a = 5;
    java.lang.String b = "";
    boolean d = false;
    volatile int e = 0;
    java.lang.String f = "";
    java.lang.String g = "";
    java.lang.String h = "";
    boolean i = true;

    p(android.content.Context context) {
        k = (short) 0;
        if (context == null) {
            return;
        }
        c(context);
    }


    public int a(int i) {
        java.lang.String strB = "";
        java.lang.String strM;
        java.lang.String str;
        org.json.JSONObject jSONObjectA;
        int iB;
        org.json.JSONObject jSONObjectA2;
        int iB2;
        try {
            strB = b("");
        } catch (java.lang.Exception unused) {
        }
        if (android.text.TextUtils.isEmpty(strB)) {
            return 0;
        }
        java.lang.String str2 = "https://ac.dun.163yun.com/v2/m/d";
        if (this.d) {
            strM = com.netease.mobsec.h.f.a() + "/v2/m/d";
            str = strM;
        } else {
            strM = u.m();
            str = "https://ac.dun.163yun.com/v2/m/d";
        }
        if (strM.isEmpty()) {
            str = "https://ac.dun.163.com/v2/m/d";
        } else {
            str2 = strM;
        }
        org.json.JSONObject jSONObjectA3 = com.netease.mobsec.h.f.a(str2, strB, i);
        int i2 = this.e == 0 ? 3000 : this.e;
        if (jSONObjectA3 != null) {
            int iB3 = u.b(jSONObjectA3);
            if (iB3 == 200) {
                a();
                return iB3;
            }
            if (iB3 == 420 && (jSONObjectA2 = com.netease.mobsec.h.f.a(str2, b(""), i2)) != null && (iB2 = u.b(jSONObjectA2)) == 200) {
                a();
                return iB2;
            }
        } else {
            java.lang.String strB2 = b("");
            if (strB2 != null && (jSONObjectA = com.netease.mobsec.h.f.a(str, strB2, i2)) != null && (iB = u.b(jSONObjectA)) == 200) {
                a();
                return iB;
            }
        }
        return 0;
    }

    public static int b(android.content.Context context) {
        return (context.getResources().getConfiguration().screenLayout & 15) >= 3 ? 8 : 5;
    }


    public com.netease.mobsec.h.a c(java.lang.String str) {
        java.lang.String strA = "";
        java.lang.String strK;
        java.lang.String str2;
        org.json.JSONObject jSONObjectA;
        java.lang.String strOptString = "";
        int iB = 0;
        try {
            strA = a(str);
        } catch (java.lang.Exception unused) {
        }
        if (strA != null && !strA.isEmpty()) {
            java.lang.String str3 = "https://ac.dun.163yun.com/v2/m/b";
            if (this.d) {
                strK = com.netease.mobsec.h.f.a() + "/v2/m/b";
                str2 = strK;
            } else {
                strK = u.k();
                str2 = "https://ac.dun.163yun.com/v2/m/b";
            }
            if (strK.isEmpty()) {
                str2 = "https://ac.dun.163.com/v2/m/b";
            } else {
                str3 = strK;
            }
            org.json.JSONObject jSONObjectA2 = com.netease.mobsec.h.f.a(str3, strA, this.e);
            if (jSONObjectA2 != null) {
                iB = u.b(jSONObjectA2);
                strOptString = jSONObjectA2.optString("msg");
                if (iB == 420 && (jSONObjectA = com.netease.mobsec.h.f.a(str3, a(str), this.e)) != null) {
                    iB = u.b(jSONObjectA);
                    strOptString = jSONObjectA.optString("msg");
                }
            } else {
                jSONObjectA = com.netease.mobsec.h.f.a(str2, a(str), this.e);
                if (jSONObjectA != null) {
                    iB = u.b(jSONObjectA);
                    strOptString = jSONObjectA.optString("msg");
                }
            }
            return new com.netease.mobsec.h.a(iB, strOptString);
        }
        return null;
    }


    public com.netease.mobsec.h.a h() {
        java.util.concurrent.FutureTask futureTask = new java.util.concurrent.FutureTask(new com.netease.mobsec.h.o(this));
        new java.lang.Thread(futureTask).start();
        try {
            return (com.netease.mobsec.h.a) futureTask.get(800L, java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (java.lang.Exception unused) {
            return null;
        }
    }

    public static com.netease.mobsec.AbstractNetClient i() {
        return x;
    }

    short j() {
        if (k >= 65535) {
            k = (short) 0;
        }
        short s2 = k;
        k = (short) (s2 + 1);
        return s2;
    }

    java.lang.String k() {
        if (o == null) {
            o = com.netease.mobsec.h.t.a();
        }
        return o;
    }


    public void l() {
        new com.netease.mobsec.h.m(this).start();
    }

    public java.lang.String g() {
        try {
            return u.b();
        } catch (java.lang.Exception unused) {
            return "";
        }
    }





    public com.netease.mobsec.WatchManResult b(int i) {
        if (!this.i || u == null) return com.netease.mobsec.WatchManResult.error(t.c, t.i);
        String nonce = t.a();
        long configured = u.r();
        this.e = i == 9876 ? (configured > 0 ? (int)configured : 3000)
                : i < 100 ? (int)configured : Math.min(i, 10000);
        com.netease.mobsec.h.a result = null;
        boolean timeout = false;
        java.util.concurrent.FutureTask<com.netease.mobsec.h.a> task =
                new java.util.concurrent.FutureTask<>(new n(this, b(), nonce));
        new Thread(task, "netease-watchman-token").start();
        try { result = task.get(this.e, java.util.concurrent.TimeUnit.MILLISECONDS); }
        catch (java.util.concurrent.TimeoutException error) { timeout = true; }
        catch (InterruptedException error) { Thread.currentThread().interrupt(); }
        catch (Exception error) { }
        return com.netease.mobsec.WatchManResult.info(t.a, t.g,
                a(u.a(), nonce, timeout || result == null || result.a() != 200));
    }

    public void d(java.lang.String str) {
        if (str == null) {
            str = "";
        }
        this.h = str;
    }

    java.lang.String b(java.lang.String str) throws com.netease.mobsec.SecException {
        java.lang.String strB = "";
        java.lang.String string = java.lang.Long.toString(u.p());
        short sJ = j();
        java.lang.String strB2 = com.netease.mobsec.h.t.b();
        java.lang.String strA = u.a();
        java.lang.String strValueOf = java.lang.String.valueOf(p);
        try {
            if (this.c != null) {
                n = u.s();
                java.lang.String[] strArr = {l, m, "", str, strB2, string, java.lang.Short.toString(sJ), n, strA, this.b, this.h, this.f, this.g, strValueOf};
                synchronized (com.netease.mobsec.h.p.class) {
                    strB = this.c.b(strArr);
                }
            }
            return strB;
        } catch (java.lang.Exception unused) {
            throw new com.netease.mobsec.SecException(com.netease.mobsec.b.f);
        }
    }

    void c(android.content.Context context) {
        if (s == null) {
            s = com.netease.mobsec.f.c.a(context);
        }
        com.netease.mobsec.f.c cVar = s;
        if (cVar != null && this.c == null) {
            this.c = cVar.a();
        }
        if (u == null) {
            com.netease.mobsec.h.b bVar = new com.netease.mobsec.h.b(context);
            u = bVar;
            n = bVar.s();
        }
    }



    public void b(int i, com.netease.mobsec.GetTokenCallback getTokenCallback) {
        if (getTokenCallback == null) throw new IllegalArgumentException("callback is required");
        new Thread(() -> a(i, getTokenCallback), "netease-watchman-callback").start();
    }

    public static com.netease.mobsec.h.p a(android.content.Context context) {
        if (j == null) {
            synchronized (com.netease.mobsec.h.p.class) {
                if (j == null) {
                    j = new com.netease.mobsec.h.p(context);
                }
            }
        }
        return j;
    }

    public void b(boolean z2) {
        q = false;
    }


    public boolean b() {
        try {
            long jG = u.g();
            long jF = jG - u.f();
            boolean zEquals = u.t().equals("");
            boolean zEquals2 = u.a().equals("");
            boolean zEquals3 = u.c().equals("");
            boolean z2 = jG < u.f() || jF < 300 || u.h() > u.f();
            if (!zEquals && !zEquals2 && !z2 && !zEquals3) {
                return false;
            }
            return u.l();
        } catch (java.lang.Exception unused) {
            return false;
        }
    }

    java.lang.String a(java.lang.String str) throws com.netease.mobsec.SecException {
        java.lang.String strA = "";
        java.lang.String string = java.lang.Long.toString(u.p());
        short sJ = j();
        java.lang.String strB = com.netease.mobsec.h.t.b();
        java.lang.String strA2 = u.a();
        java.lang.String strValueOf = java.lang.String.valueOf(q);
        try {
            java.lang.String strB2 = "";
            if (this.c != null) {
                java.lang.String strS = u.s();
                n = strS;
                java.lang.String[] strArr = {l, m, "", strS, java.lang.Short.toString(sJ), string, strB, str, strA2, this.b, this.h, strValueOf, strB2};
                synchronized (com.netease.mobsec.h.p.class) {
                    strA = this.c.a(strArr);
                }
            }
            return strA;
        } catch (java.lang.Exception unused) {
            throw new com.netease.mobsec.SecException(com.netease.mobsec.b.f);
        }
    }

    java.lang.String a(java.lang.String str, java.lang.String str2, java.lang.String str3) {
        return com.netease.mobsec.h.t.a(com.netease.mobsec.h.t.b((str + str2 + str3 + "mvcgwOjB5yIpKyKGbqbmp7w3PBjknDGx").getBytes()));
    }


    public java.lang.String a(java.lang.String str, java.lang.String str2, boolean z2) {
        boolean z3;
        if (str2.isEmpty()) {
            str2 = com.netease.mobsec.h.t.a();
            if (str2.isEmpty()) {
                str2 = k();
            }
            z3 = true;
        } else {
            z3 = false;
        }
        return a(str, str2, str.isEmpty() ? true : z3, z2);
    }

    java.lang.String a(java.lang.String str, java.lang.String str2, boolean z2, boolean z3) {
        try {
            org.json.JSONObject jSONObject = new org.json.JSONObject();
            jSONObject.put("r", this.a);
            jSONObject.put("d", str);
            jSONObject.put("b", str2);
            if (z2 || z3) {
                jSONObject.put("t", a("" + this.a, str, str2));
            }
            return com.netease.mobsec.h.q.b(jSONObject.toString());
        } catch (java.lang.Exception e) {
            throw new java.lang.RuntimeException(e);
        }
    }

    void a() {
        u.a(u.f() + ((u.n() * 5) / 6000));
        com.netease.mobsec.h.b bVar = u;
        bVar.b(bVar.f());
    }













    public void a(int i, com.netease.mobsec.GetTokenCallback getTokenCallback) {
        if (getTokenCallback == null) throw new IllegalArgumentException("callback is required");
        com.netease.mobsec.WatchManResult result = b(i);
        getTokenCallback.onResult(result.getCode(), result.getMsg(), result.getToken());
    }



    public synchronized void a(android.content.Context context, java.lang.String str, com.netease.mobsec.WatchManConf watchManConf, com.netease.mobsec.InitCallback initCallback) {
        try {
        } catch (java.lang.Exception e) {
            if (initCallback != null) {
                initCallback.onResult(com.netease.mobsec.h.t.e, e.getMessage());
            }
            this.i = false;
            A = false;
        }
        if (A) {
            if (initCallback != null) {
                initCallback.onResult(com.netease.mobsec.h.t.f, com.netease.mobsec.h.t.k);
            }
            return;
        }
        if (context == null) {
            this.i = false;
            if (initCallback != null) {
                initCallback.onResult(com.netease.mobsec.h.t.d, com.netease.mobsec.h.t.j);
            }
            return;
        }
        this.a = b(context);
        if (android.text.TextUtils.isEmpty(str)) {
            if (initCallback != null) {
                initCallback.onResult(com.netease.mobsec.h.t.b, com.netease.mobsec.h.t.h);
            }
            this.i = false;
            return;
        }
        this.b = str;
        A = true;
        if (watchManConf != null) {
            this.f = watchManConf.getChannel() != null ? watchManConf.getChannel() : "";
            this.h = !android.text.TextUtils.isEmpty(watchManConf.getCustomTrackId()) ? watchManConf.getCustomTrackId() : "";
            p = watchManConf.getCollectApk();
            q = false;
            r = watchManConf.isOnCoroutines();
            w = watchManConf.getInitDInfo();
            if (watchManConf.getExtraData() != null) {
                if (watchManConf.getExtraData().size() > 0) {
                    try {
                        this.g = new org.json.JSONObject(watchManConf.getExtraData()).toString();
                    } catch (java.lang.Exception unused) {
                    }
                } else {
                    this.g = "";
                }
            }
            if (watchManConf.getAbstractNetClient() != null) {
                x = watchManConf.getAbstractNetClient();
            }
            if (!android.text.TextUtils.isEmpty(watchManConf.getUrl())) {
                com.netease.mobsec.h.f.a(watchManConf.getUrl());
                this.d = true;
            }
        }
        if (u == null) {
            c(context);
        }
        if (u.o() > u.f()) {
            if (w && b()) {
                l();
            }
            if (initCallback != null) {
                initCallback.onResult(com.netease.mobsec.h.t.a, com.netease.mobsec.h.t.g);
            }
        } else {
            a(initCallback);
        }
    }

    void a(com.netease.mobsec.InitCallback initCallback) {
        new java.lang.Thread(new com.netease.mobsec.h.l(this, initCallback)).start();
    }


    public void a(com.netease.mobsec.h.a aVar, com.netease.mobsec.InitCallback initCallback) {
        if (aVar == null) {
            if (initCallback != null) {
                initCallback.onResult(com.netease.mobsec.h.t.a, com.netease.mobsec.h.t.g);
            }
        } else if (android.text.TextUtils.isEmpty(aVar.b())) {
            if (initCallback != null) {
                initCallback.onResult(com.netease.mobsec.h.t.a, com.netease.mobsec.h.t.g);
            }
        } else if (initCallback != null) {
            initCallback.onResult(aVar.a(), aVar.b());
        }
    }
}
