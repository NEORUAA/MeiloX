package com.netease.mobsec.h;



public class m extends java.lang.Thread {
    final  com.netease.mobsec.h.p a;

    public m(com.netease.mobsec.h.p pVar) {
        this.a = pVar;
    }

    @Override // java.lang.Thread, java.lang.Runnable
    public void run() {
        try {
            this.a.a(10000);
        } catch (java.lang.Exception unused) {
        }
    }
}
