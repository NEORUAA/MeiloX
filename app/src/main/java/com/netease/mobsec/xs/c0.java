package com.netease.mobsec.xs;



public class c0 implements java.lang.Runnable {
    public final  android.content.Context a;

    public c0(android.content.Context context) {
        this.a = context;
    }

    @Override // java.lang.Runnable
    public void run() {
        java.lang.String str;
        try {
            str = com.netease.mobsec.xs.f.a(this.a, 2000L).a;
        } catch (java.lang.Exception unused) {
            str = null;
        }
        com.netease.mobsec.xs.e0.b = str;
        java.util.concurrent.CountDownLatch countDownLatch = com.netease.mobsec.xs.e0.a;
        if (countDownLatch != null) {
            countDownLatch.countDown();
        }
    }
}
