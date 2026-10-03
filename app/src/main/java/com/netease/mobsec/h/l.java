package com.netease.mobsec.h;



class l implements java.lang.Runnable {
    final  com.netease.mobsec.InitCallback a;
    final  com.netease.mobsec.h.p b;

    l(com.netease.mobsec.h.p pVar, com.netease.mobsec.InitCallback initCallback) {
        this.b = pVar;
        this.a = initCallback;
    }

    @Override // java.lang.Runnable
    public void run() {
        com.netease.mobsec.h.a aVarH = this.b.h();
        if (com.netease.mobsec.h.p.r) {
            this.b.a(aVarH, this.a);
        } else {
            com.netease.mobsec.h.p.y.post(new com.netease.mobsec.h.k(this, aVarH));
        }
    }
}
