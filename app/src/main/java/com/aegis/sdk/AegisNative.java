package com.aegis.sdk;

/** JNI ABI exposed by NetEase's AegisSDK runtime. */
public final class AegisNative {
    static {
        System.loadLibrary("AegisSDK");
    }

    private AegisNative() {}

    public static native void destroyEngine();

    public static native String encrypt(String data);

    public static native int initializeEngine(
            String publicKeyPath,
            String staticKey,
            String deviceId,
            String platform,
            String userAgent,
            String signKey,
            Object networkLayer,
            int updateIntervalMinutes);

    public static native int onNetworkResponse(long callbackHandle, int code, String response);

    public static native void setSession(String sessionId, String sessionKey);

    public static native void setTrackingListener(AegisTrackingListener listener);

    public static native int updatePublicKey(boolean activated);

    public interface AegisTrackingListener {
        void onTrack(String event, String payload);
    }
}
