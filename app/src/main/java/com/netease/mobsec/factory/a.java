package com.netease.mobsec.factory;



public class a {
    final com.netease.mobsec.factory.JNIFactory a = com.netease.mobsec.factory.JNIFactory.getInstance();
    final android.content.Context b;

    public a(android.content.Context context) {
        this.b = context;
    }

    public final java.lang.String a(java.lang.String[] strArr) {
        java.lang.String strW230921e1b36f7799;
        synchronized (com.netease.mobsec.factory.a.class) {
            strW230921e1b36f7799 = this.a.w230921e1b36f7799(this.b, strArr);
        }
        return strW230921e1b36f7799;
    }

    public final java.lang.String b(java.lang.String[] strArr) {
        java.lang.String strW238jfd9349jdj394;
        synchronized (com.netease.mobsec.factory.a.class) {
            strW238jfd9349jdj394 = this.a.w238jfd9349jdj394(this.b, strArr);
        }
        return strW238jfd9349jdj394;
    }
}
