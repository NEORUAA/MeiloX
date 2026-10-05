package com.netease.is.deviceid;

import android.content.Context;
import com.netease.is.deviceid.factory.JNIFactory;

/** Only the two device identity operations used by music authentication. */
public final class NEDeviceID {
    static { System.loadLibrary("netdev-1.0.0"); }
    public static synchronized String getWifi(Context context) {
        return JNIFactory.getInstance().w7dc0c8f734a2a016(context);
    }
    public static synchronized String getLocalID(Context context) {
        return JNIFactory.getInstance().w1c2724538080aa1b(context);
    }
}
