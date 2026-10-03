package com.netease.mobsec.xs;



public class q {
    public static volatile boolean a;

    public static synchronized boolean a(android.content.Context context) {
        if (a) {
            return true;
        }
        synchronized (com.netease.mobsec.xs.b0.class) {
            if (!com.netease.mobsec.xs.b0.a) {
                try {
                    android.hardware.display.DisplayManager displayManager = (android.hardware.display.DisplayManager) context.getApplicationContext().getSystemService(com.netease.mobsec.xs.b0.a(new char[]{230, 'a', '!', 'r', 'N', 'B', '>'}, "̥ͬͰɮɱ̽"));
                    if (displayManager != null) {
                        android.os.HandlerThread c0Var = new android.os.HandlerThread(com.netease.mobsec.xs.b0.a(new char[]{'1', 184, '?', 18, '5', 255, 22, 171, 159, 129}, "˫ʻ\u0379Ϳˊ͉"));
                        c0Var.start();
                        displayManager.registerDisplayListener(new com.netease.mobsec.xs.b0.a(displayManager), new android.os.Handler(c0Var.getLooper()));
                        com.netease.mobsec.xs.b0.a = true;
                    }
                } catch (java.lang.Exception unused) {
                }
            }
        }
        synchronized (com.netease.mobsec.xs.e0.class) {
            if (com.netease.mobsec.xs.e0.d && !com.netease.mobsec.xs.e0.e) {
                com.netease.mobsec.xs.e0.e = true;
                new java.lang.Thread(new com.netease.mobsec.xs.c0(context)).start();
                new java.lang.Thread(new com.netease.mobsec.xs.d0(context)).start();
            }
        }
        a = com.netease.mobsec.xs.poly.a.e6615a3e4d79(context);
        return a;
    }
}
