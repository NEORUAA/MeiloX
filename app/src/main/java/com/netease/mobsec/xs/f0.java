package com.netease.mobsec.xs;



public class f0 {
    public static volatile java.lang.String a;
    public static volatile long b;
    public static final java.util.concurrent.locks.ReentrantReadWriteLock c = new java.util.concurrent.locks.ReentrantReadWriteLock();

    public static void a(java.lang.String str, long j) {
        java.util.concurrent.locks.ReentrantReadWriteLock.WriteLock writeLock;
        java.util.concurrent.locks.ReentrantReadWriteLock reentrantReadWriteLock = c;
        reentrantReadWriteLock.writeLock().lock();
        try {
            a = str;
            b = java.lang.System.currentTimeMillis() + j;
            writeLock = reentrantReadWriteLock.writeLock();
        } catch (java.lang.Exception unused) {
            writeLock = c.writeLock();
        } catch (java.lang.Throwable th) {
            c.writeLock().unlock();
            throw th;
        }
        writeLock.unlock();
    }

    public static java.lang.String a() {
        java.util.concurrent.locks.ReentrantReadWriteLock reentrantReadWriteLock = c;
        reentrantReadWriteLock.readLock().lock();
        try {
            if (java.lang.System.currentTimeMillis() > b) {
                reentrantReadWriteLock.readLock().unlock();
                return null;
            }
            java.lang.String str = a;
            reentrantReadWriteLock.readLock().unlock();
            return str;
        } catch (java.lang.Throwable th) {
            c.readLock().unlock();
            throw th;
        }
    }
}
