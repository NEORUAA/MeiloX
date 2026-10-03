package com.netease.mobsec.h;



public class n implements java.util.concurrent.Callable<com.netease.mobsec.h.a> {
    final boolean a;
    final java.lang.String b;
    final  com.netease.mobsec.h.p c;

    public n(com.netease.mobsec.h.p pVar, boolean z, java.lang.String str) {
        this.c = pVar;
        this.a = z;
        this.b = str;
    }

    @Override // java.util.concurrent.Callable

    public com.netease.mobsec.h.a call() {
        try {
            if (this.a) {
                try {
                    this.c.a(3000);
                } catch (java.lang.Exception unused) {
                }
            }
            return this.c.c(this.b);
        } catch (java.lang.Exception unused2) {
            return null;
        }
    }
}
