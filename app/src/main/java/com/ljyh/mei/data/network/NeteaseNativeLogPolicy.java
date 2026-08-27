package com.ljyh.mei.data.network;

import androidx.annotation.Keep;

@Keep
public final class NeteaseNativeLogPolicy {
    static {
        System.loadLibrary("netease_log_policy");
    }

    private NeteaseNativeLogPolicy() {}

    public static native void installSecurityFilter();
}
