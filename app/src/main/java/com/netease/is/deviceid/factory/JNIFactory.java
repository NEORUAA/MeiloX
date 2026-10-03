package com.netease.is.deviceid.factory;

/** Names and descriptors are part of libnetdev-1.0.0's JNI ABI. */
public final class JNIFactory {
    private static final JNIFactory INSTANCE = new JNIFactory();
    private JNIFactory() {}
    public static JNIFactory getInstance() { return INSTANCE; }
    public native String w1c2724538080aa1b(Object context);
    public native String w7dc0c8f734a2a016(Object context);
}
