package com.netease.mobsec;



public class WatchMan {
    static com.netease.mobsec.h.p a;

    WatchMan() {
    }

    static com.netease.mobsec.WatchMan a() {
        return com.netease.mobsec.d.a;
    }

    public static java.lang.String getDeviceId() {
        com.netease.mobsec.h.p pVar;
        return (a() == null || (pVar = a) == null) ? "" : pVar.g();
    }

    public static com.netease.mobsec.WatchManResult getToken(int i) {
        com.netease.mobsec.h.p pVar;
        return (a() == null || (pVar = a) == null) ? com.netease.mobsec.WatchManResult.error(com.netease.mobsec.h.t.c, com.netease.mobsec.h.t.i) : pVar.b(i);
    }

    public static void getTokenAsync(int i, com.netease.mobsec.GetTokenCallback getTokenCallback) {
        if (a() != null) {
            com.netease.mobsec.h.p pVar = a;
            if (pVar != null) {
                pVar.b(i, getTokenCallback);
            } else if (getTokenCallback != null) {
                getTokenCallback.onResult(com.netease.mobsec.h.t.c, com.netease.mobsec.h.t.i, "");
            }
        }
    }

    public static void init(android.content.Context context, java.lang.String str, com.netease.mobsec.WatchManConf watchManConf, com.netease.mobsec.InitCallback initCallback) {
        if (a() != null) {
            if (a == null) {
                a = com.netease.mobsec.h.p.a(context);
            }
            com.netease.mobsec.h.p pVar = a;
            if (pVar != null) {
                pVar.a(context, str, watchManConf, initCallback);
            }
        }
    }

    public static void setCustomTrackId(java.lang.String str) {
        com.netease.mobsec.h.p pVar;
        if (a() == null || (pVar = a) == null) {
            return;
        }
        pVar.d(str);
    }

    public static void setSeniorCollectStatus(boolean z) {
        com.netease.mobsec.h.p pVar;
        if (a() == null || (pVar = a) == null) {
            return;
        }
        pVar.b(z);
    }

    public static void getToken(int i, com.netease.mobsec.GetTokenCallback getTokenCallback) throws java.lang.InterruptedException {
        if (a() != null) {
            com.netease.mobsec.h.p pVar = a;
            if (pVar != null) {
                pVar.a(i, getTokenCallback);
            } else if (getTokenCallback != null) {
                getTokenCallback.onResult(com.netease.mobsec.h.t.c, com.netease.mobsec.h.t.i, "");
            }
        }
    }

    public static void getToken(com.netease.mobsec.GetTokenCallback getTokenCallback) throws java.lang.InterruptedException {
        if (a() != null) {
            com.netease.mobsec.h.p pVar = a;
            if (pVar != null) {
                pVar.a(9876, getTokenCallback);
            } else if (getTokenCallback != null) {
                getTokenCallback.onResult(com.netease.mobsec.h.t.c, com.netease.mobsec.h.t.i, "");
            }
        }
    }
}
