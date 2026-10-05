package com.netease.mobsec.xs;



public final class g implements android.content.ServiceConnection {
    public long a;
    public boolean b = false;
    public final java.util.concurrent.LinkedBlockingQueue<android.os.IBinder> c = new java.util.concurrent.LinkedBlockingQueue<>(1);

    public g(long j) {
        this.a = j;
    }

    public android.os.IBinder a() throws InterruptedException {
        if (this.b) {
            throw new java.lang.IllegalStateException();
        }
        this.b = true;
        return this.c.poll(this.a, java.util.concurrent.TimeUnit.MILLISECONDS);
    }

    @Override // android.content.ServiceConnection
    public void onServiceConnected(android.content.ComponentName componentName, android.os.IBinder iBinder) {
        try {
            this.c.put(iBinder);
        } catch (java.lang.InterruptedException unused) {
        }
    }

    @Override // android.content.ServiceConnection
    public void onServiceDisconnected(android.content.ComponentName componentName) {
    }
}
