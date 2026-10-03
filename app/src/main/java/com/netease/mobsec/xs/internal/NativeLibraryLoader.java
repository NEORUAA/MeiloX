package com.netease.mobsec.xs.internal;


public final class NativeLibraryLoader {
    public static void load(android.content.Context context, java.lang.String str) {
        java.lang.System.load(new java.io.File(context.getApplicationInfo().nativeLibraryDir, java.lang.System.mapLibraryName(str)).getAbsolutePath());
    }
}
