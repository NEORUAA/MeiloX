package com.netease.mobsec.xs;



public class d0 implements java.lang.Runnable {
    public final  android.content.Context a;

    public d0(android.content.Context context) {
        this.a = context;
    }

    @Override // java.lang.Runnable
    public void run() {
        java.util.concurrent.CountDownLatch countDownLatch;
        try {
            com.netease.mobsec.xs.e0.c = com.netease.mobsec.xs.m.a(this.a);
            countDownLatch = com.netease.mobsec.xs.e0.a;
            if (countDownLatch == null) {
                return;
            }
        } catch (java.lang.Exception unused) {
            countDownLatch = com.netease.mobsec.xs.e0.a;
            if (countDownLatch == null) {
                return;
            }
        } catch (java.lang.Throwable th) {
            java.util.concurrent.CountDownLatch countDownLatch2 = com.netease.mobsec.xs.e0.a;
            if (countDownLatch2 != null) {
                countDownLatch2.countDown();
            }
            throw th;
        }
        countDownLatch.countDown();
    }
}
