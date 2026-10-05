package com.netease.mobsec.xs;



public class n implements android.content.ServiceConnection {
    public static final java.util.concurrent.ThreadPoolExecutor a = new java.util.concurrent.ThreadPoolExecutor(0, 3, 60, java.util.concurrent.TimeUnit.SECONDS, new java.util.concurrent.LinkedBlockingQueue(2048), new java.util.concurrent.ThreadPoolExecutor.DiscardPolicy());
    public boolean b = false;
    public final java.util.concurrent.LinkedBlockingQueue<android.os.IBinder> c = new java.util.concurrent.LinkedBlockingQueue<>(1);


    public class a implements java.lang.Runnable {
        public final  android.os.IBinder a;

        public a(android.os.IBinder iBinder) {
            this.a = iBinder;
        }

        @Override // java.lang.Runnable
        public void run() {
            try {
                com.netease.mobsec.xs.n.this.c.offer(this.a);
            } catch (java.lang.Throwable unused) {
            }
        }
    }

    public android.os.IBinder a() throws java.lang.InterruptedException {
        if (this.b) {
            throw new java.lang.IllegalStateException();
        }
        this.b = true;
        return this.c.take();
    }

    @Override // android.content.ServiceConnection
    public void onServiceConnected(android.content.ComponentName componentName, android.os.IBinder iBinder) {
        a.execute(new com.netease.mobsec.xs.n.a(iBinder));
    }

    @Override // android.content.ServiceConnection
    public void onServiceDisconnected(android.content.ComponentName componentName) {
    }
}
