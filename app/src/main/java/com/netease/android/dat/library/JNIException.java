package com.netease.android.dat.library;

/** Exception type required by libnedat's JNI interface. */
public final class JNIException extends RuntimeException {
    public JNIException(String message) { super(message); }
}
