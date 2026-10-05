# Normal compiled code is optimized together with its host dependencies.
# Retain the ABI used by unchanged native libraries, JavaScript and SDK bridges.
# JNI_OnLoad registers methods even when the Java caller does not invoke them.
-keepclasseswithmembers,includedescriptorclasses class * { native <methods>; }
# libcaesarson uses GetFieldID/SetField on this result object. Keeping the
# native method's descriptor preserves its class name, but not its fields.
-keep class com.netease.cloudmusic.crypto.caesarson.ErrorObject {
    int errorCode;
    java.lang.String message;
    <init>();
}
-keep class com.netease.mobsec.poly.** { *; }
-keep class com.netease.mobsec.xs.poly.** { *; }
-keep class com.netease.is.deviceid.poly.** { *; }
-keep class com.netease.android.dat.library.JNIException { *; }
-keep class com.netease.android.dat.library.NativeSignatureUtil { *; }
-keep class com.netease.mobsec.xs.f { *; }
-keep class com.netease.mobsec.xs.m { *; }
-keep class com.netease.mobsec.WatchMan { public *; }
-keep class com.netease.mobsec.WatchManConf { public *; }
-keep class com.netease.mobsec.InitCallback { *; }
-keep class com.netease.mobsec.GetTokenCallback { *; }
-keep class com.netease.mobsec.AbstractNetClient { *; }
-keep class com.netease.mobsec.xs.NEDevice { public *; }
-keep class com.netease.mobsec.xs.network.Result { public *; }
-keep class com.netease.is.deviceid.NEDeviceID { public *; }
-keepclassmembers class com.ljyh.mei.data.network.CaptchaBridge {
    @android.webkit.JavascriptInterface <methods>;
}
