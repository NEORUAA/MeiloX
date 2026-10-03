package com.netease.urs.jni;

import android.content.Context;

/** Native signature helper used by URS; no dynamically loaded Java code. */
public final class NativeJni {
    static { System.loadLibrary("ursandroidunity"); }
    public static native String getConsts(int index);
    public static native Object getSignatureMd5Bytes(Context context, boolean flag);
}
