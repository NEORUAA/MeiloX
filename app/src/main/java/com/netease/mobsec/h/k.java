package com.netease.mobsec.h;



class k implements java.lang.Runnable {
    final  com.netease.mobsec.h.a a;
    final  com.netease.mobsec.h.l b;

    k(com.netease.mobsec.h.l lVar, com.netease.mobsec.h.a aVar) {
        this.b = lVar;
        this.a = aVar;
    }

    @Override // java.lang.Runnable
    public void run() {
        com.netease.mobsec.h.l lVar = this.b;
        lVar.b.a(this.a, lVar.a);
    }
}
