package com.netease.mobsec.h;



public class f {
    static java.lang.String a;

    public static java.lang.String a() {
        return a;
    }

    static org.json.JSONObject a(android.util.Pair<java.lang.Integer, java.lang.String> pair) {
        try {
            if (((java.lang.Integer) pair.first).intValue() == 200) {
                return new org.json.JSONObject((java.lang.String) pair.second);
            }
        } catch (java.lang.Exception unused) {
        }
        return null;
    }

    public static org.json.JSONObject a(java.lang.String str, int i) {
        try {
            com.netease.mobsec.AbstractNetClient abstractNetClientI = com.netease.mobsec.h.p.i();
            if (abstractNetClientI == null) {
                abstractNetClientI = new com.netease.mobsec.h.c();
            }
            android.util.Pair<java.lang.Integer, java.lang.String> pairSendGet = abstractNetClientI.sendGet(str, i);
            if (pairSendGet != null) {
                return a(pairSendGet);
            }
        } catch (java.lang.Exception unused) {
        }
        return null;
    }

    public static org.json.JSONObject a(java.lang.String str, java.lang.String str2, int i) {
        if (str2 == null || str2.equals("")) {
            return null;
        }
        java.lang.String str3 = "d=" + java.net.URLEncoder.encode(str2);
        try {
            com.netease.mobsec.AbstractNetClient abstractNetClientI = com.netease.mobsec.h.p.i();
            if (abstractNetClientI == null) {
                abstractNetClientI = new com.netease.mobsec.h.c();
            }
            android.util.Pair<java.lang.Integer, java.lang.String> pairSendPost = abstractNetClientI.sendPost(str, str3, i);
            if (pairSendPost != null) {
                return a(pairSendPost);
            }
            return null;
        } catch (java.lang.Exception unused) {
            return null;
        }
    }

    public static void a(java.lang.String str) {
        a = str;
    }
}
