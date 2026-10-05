package com.netease.mobsec.h;



public class o implements java.util.concurrent.Callable<com.netease.mobsec.h.a> {
    final  com.netease.mobsec.h.p a;

    public o(com.netease.mobsec.h.p pVar) {
        this.a = pVar;
    }


    @Override // java.util.concurrent.Callable


    public com.netease.mobsec.h.a call() {
        int iA;
        int i;
        java.lang.String str;
        java.lang.String str2;
        java.lang.String str3;
        org.json.JSONObject jSONObjectA;
        try {
            java.lang.String str4 = java.lang.String.format("pn=%s", this.a.b);
            if (this.a.d) {
                str3 = com.netease.mobsec.h.f.a() + "/v2/config/android?" + str4;
                str2 = str3;
            } else {
                str2 = "https://ac.dun.163.com/v2/config/android?" + str4;
                str3 = "https://ac.dun.163yun.com/v2/config/android?" + str4;
            }
            org.json.JSONObject jSONObjectA2 = com.netease.mobsec.h.f.a(str3, 3000);
            if (jSONObjectA2 != null) {
                iA = com.netease.mobsec.h.p.u.a(jSONObjectA2);
                try {
                    jSONObjectA2.optString("msg");
                    if (iA == 420 && (jSONObjectA = com.netease.mobsec.h.f.a(str3, 3000)) != null) {
                        iA = com.netease.mobsec.h.p.u.a(jSONObjectA);
                        jSONObjectA.optString("msg");
                    }
                    if (com.netease.mobsec.h.p.w && this.a.b()) {
                        this.a.l();
                    }
                } catch (java.lang.Exception unused) {
                }
            } else {
                jSONObjectA = com.netease.mobsec.h.f.a(str2, 3000);
                if (jSONObjectA != null) {
                    iA = com.netease.mobsec.h.p.u.a(jSONObjectA);
                    jSONObjectA.optString("msg");
                    if (com.netease.mobsec.h.p.w) {
                        this.a.l();
                    }
                } else {
                    iA = 0;
                    if (com.netease.mobsec.h.p.w) {
                    }
                }
            }
        } catch (java.lang.Exception unused2) {
            iA = 0;
        }
        if (iA == 470) {
            this.a.i = false;
            boolean unused3 = com.netease.mobsec.h.p.A = false;
            i = com.netease.mobsec.h.t.b;
            str = com.netease.mobsec.h.t.h;
        } else {
            this.a.i = true;
            i = com.netease.mobsec.h.t.a;
            str = com.netease.mobsec.h.t.g;
        }
        return new com.netease.mobsec.h.a(i, str);
    }
}
