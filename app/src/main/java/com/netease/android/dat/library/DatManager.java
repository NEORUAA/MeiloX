package com.netease.android.dat.library;

import android.content.Context;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

/** Minimal DAT key reader; keeps the JNI ABI of the bundled native library. */
public final class DatManager implements AutoCloseable {
    private long nativePtr;
    private final File directory;

    static { System.loadLibrary("nedat"); }

    public DatManager(Context context, String appSign) throws IOException {
        directory = new File(context.getFilesDir(), "nedat-auth");
        if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("DAT directory");
        importAsset(context, "app_sign_private.dat", "music_app_sign_private");
        nativePtr = nativeCreateDatManager();
        try {
            if (!nativeInit(new File(directory, "music_app_sign_private").getPath(),
                    appSign, context, nativePtr)) throw new JNIException("DAT initialization failed");
        } catch (RuntimeException | Error error) { close(); throw error; }
    }

    public String readKey(Context context, String asset, String name) throws IOException {
        importAsset(context, asset, name);
        String key = nativeGetDataFromDatFile(new File(directory, name).getPath(), nativePtr);
        if (key == null || key.isEmpty()) throw new JNIException("DAT key is empty");
        return key;
    }

    private void importAsset(Context context, String asset, String name) throws IOException {
        try (var in = context.getAssets().open(asset);
             var out = new FileOutputStream(new File(directory, name))) { in.transferTo(out); }
    }

    @Override public void close() {
        if (nativePtr != 0) { long ptr = nativePtr; nativePtr = 0; nativeDestroy(ptr); }
    }
    private static native long nativeCreateDatManager();
    private native void nativeDestroy(long ptr);
    private native String nativeGetDataFromDatFile(String path, long ptr);
    private static native String nativeGetVersion();
    private native boolean nativeInit(String path, String appSign, Context context, long ptr);
}
