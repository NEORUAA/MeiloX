package com.netease.mobsec.factory;



public class JNIFactory {
    static com.netease.mobsec.factory.JNIFactory a;

    JNIFactory() {
    }

    public static com.netease.mobsec.factory.JNIFactory getInstance() {
        if (a == null) {
            a = new com.netease.mobsec.factory.JNIFactory();
        }
        return a;
    }

    public native java.lang.String w230921e1b36f7799(java.lang.Object obj, java.lang.String[] strArr);

    public native java.lang.String w238jfd9349jdj394(java.lang.Object obj, java.lang.String[] strArr);
}
